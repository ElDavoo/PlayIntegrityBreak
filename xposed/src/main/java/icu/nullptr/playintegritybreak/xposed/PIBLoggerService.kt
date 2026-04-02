package icu.nullptr.playintegritybreak.xposed

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.IPIBService
import icu.nullptr.playintegritybreak.common.JsonConfig
import org.frknkrc44.pib_oss.common.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PIBLoggerService : IPIBService.Stub() {
    private const val TAG = "PIB-LoggerService"
    private const val RUNTIME_LOG_FILE = "integrity_runtime.log"
    private const val RUNTIME_LOG_OLD_FILE = "integrity_runtime.old.log"
    private const val CONFIG_SNAPSHOT_FILE = "integrity_config_snapshot.json"
    private const val HEARTBEAT_INTERVAL_MS = 10_000L

    private val initialized = AtomicBoolean(false)
    private val heartbeatLoopStarted = AtomicBoolean(false)
    private val binderPublished = AtomicBoolean(false)
    private val capturedEvents = AtomicLong(0)
    private val lastHealthcheckTimestamp = AtomicLong(0)
    private val configLock = Any()
    private val logLock = Any()

    private val heartbeatHandler by lazy { Handler(Looper.getMainLooper()) }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get()) {
                heartbeatLoopStarted.set(false)
                return
            }

            touchHealthcheck()
            tryPublishBinderToClientApp()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    @Volatile
    private var config = JsonConfig().apply {
        detailLog = true
        errorOnlyLog = false
    }

    @Volatile
    private var runtimeLogFile: File? = null

    @Volatile
    private var configSnapshotFile: File? = null

    data class IntegrityPolicy(
        val enabled: Boolean,
        val logRequest: Boolean,
        val logResponse: Boolean,
        val errorOnly: Boolean,
        val rewriteResponse: Boolean,
        val rewriteErrorCode: Int,
        val rewriteRemediable: Boolean,
    )

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) {
            touchHealthcheck()
            return
        }

        ensureLogFile()
        loadPersistedConfigSnapshot()
        touchHealthcheck()
        startHeartbeatLoop()
        tryPublishBinderToClientApp()
        logI(TAG, "PIB logger service initialized")
    }

    fun isActive(): Boolean = initialized.get()

    fun isErrorOnlyLogging(): Boolean = synchronized(configLock) { config.errorOnlyLog }

    fun isDetailLogging(): Boolean = synchronized(configLock) { config.detailLog }

    fun appendParsedLog(parsedMsg: String) {
        touchHealthcheck()
        synchronized(logLock) {
            val file = ensureLogFile() ?: return
            val maxLogSizeKb = synchronized(configLock) { config.maxLogSize }
            if (maxLogSizeKb > 0 && file.length() / 1024 > maxLogSizeKb) {
                rotateLogs(file)
            }

            try {
                FileWriter(file, true).use { it.write(parsedMsg) }
                capturedEvents.incrementAndGet()
            } catch (_: IOException) {
            }
        }
    }

    fun resolvePolicy(callerPkg: String): IntegrityPolicy {
        touchHealthcheck()

        val unknownCaller = callerPkg.isBlank() || callerPkg == "unknown"
        if (unknownCaller) {
            return synchronized(configLock) {
                IntegrityPolicy(
                    enabled = true,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = config.errorOnlyLog,
                    rewriteResponse = config.defaultHookRewriteEnabled,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                )
            }
        }

        synchronized(configLock) {
            val appConfig = config.scope[callerPkg]
            val hasScopedApps = config.scope.isNotEmpty()
            val defaultRewriteEnabled = config.defaultHookRewriteEnabled
            val appOverrideEnabled = appConfig?.let {
                it.rewriteIntegrityResponseOverridden
                        || it.rewriteIntegrityResponse
                        || it.rewriteIntegrityErrorCode != config.defaultHookRewriteErrorCode
                        || it.rewriteIntegrityErrorRemediable != config.defaultHookRewriteRemediable
                        || !it.integrityLoggerEnabled
            } == true

            val resolvedRewriteResponse = if (appOverrideEnabled) {
                appConfig.rewriteIntegrityResponse
            } else {
                defaultRewriteEnabled
            }
            val resolvedRewriteErrorCode = if (appOverrideEnabled) {
                appConfig.rewriteIntegrityErrorCode
            } else {
                config.defaultHookRewriteErrorCode
            }
            val resolvedRewriteRemediable = if (appOverrideEnabled) {
                appConfig.rewriteIntegrityErrorRemediable
            } else {
                config.defaultHookRewriteRemediable
            }

            if (appConfig == null && hasScopedApps && !defaultRewriteEnabled) {
                return IntegrityPolicy(
                    enabled = false,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = true,
                    rewriteResponse = false,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                )
            }

            return IntegrityPolicy(
                enabled = appConfig?.integrityLoggerEnabled ?: true,
                // Request/response logging is always enabled; only logger enable/error-only may filter output.
                logRequest = true,
                logResponse = true,
                errorOnly = config.errorOnlyLog,
                rewriteResponse = resolvedRewriteResponse,
                rewriteErrorCode = resolvedRewriteErrorCode,
                rewriteRemediable = resolvedRewriteRemediable,
            )
        }
    }

    fun recordIntegrityRequest(callerPkg: String) {
        touchHealthcheck()
        val app = getCurrentApplication() ?: return
        IntegrityEventStore.recordRequest(app, callerPkg)
    }

    fun recordIntegrityResponse(
        callerPkg: String,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        touchHealthcheck()
        val app = getCurrentApplication() ?: return
        IntegrityEventStore.recordResponse(
            app = app,
            packageName = callerPkg,
            success = success,
            errorCode = errorCode,
            retriable = retriable,
            source = source,
        )
    }

    fun tryPublishBinderToClientApp(): Boolean {
        touchHealthcheck()
        val app = getCurrentApplication() ?: return false
        val extras = Bundle().apply { putBinder("binder", this@PIBLoggerService) }
        val uri = Uri.parse("content://${Constants.PROVIDER_AUTHORITY}")

        return runCatching {
            app.contentResolver.call(uri, "link", null, extras) != null
        }.onSuccess { success ->
            if (success && binderPublished.compareAndSet(false, true)) {
                logI(TAG, "Published logger binder to app")
            }
        }.onFailure {
            if (binderPublished.getAndSet(false)) {
                logW(TAG, "Failed to publish logger binder", it)
            }
        }.getOrDefault(false)
    }

    override fun writeConfig(json: String) {
        val parsedConfig = runCatching {
            JsonConfig.parse(json).apply {
                configVersion = BuildConfig.CONFIG_VERSION
            }
        }.onFailure {
            logE(TAG, "Failed to parse config", it)
        }.getOrNull() ?: return

        synchronized(configLock) {
            config = parsedConfig
        }

        persistConfigSnapshot(parsedConfig.toString())
    }

    override fun getServiceVersion(): Int = BuildConfig.SERVICE_VERSION

    override fun getServiceHealthcheckTimestamp(): Long = lastHealthcheckTimestamp.get()

    override fun getFilterCount(): Int {
        val inMemoryCount = capturedEvents.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val app = getCurrentApplication() ?: return inMemoryCount
        val dbCount = IntegrityEventStore.countEvents(app)
        return dbCount.coerceAtLeast(inMemoryCount)
    }

    override fun getLogs(): String {
        synchronized(logLock) {
            val file = ensureLogFile() ?: return ""
            return runCatching { file.readText() }.getOrDefault("")
        }
    }

    override fun clearLogs() {
        synchronized(logLock) {
            val file = ensureLogFile() ?: return
            runCatching {
                file.writeText("")
                File(file.parentFile, RUNTIME_LOG_OLD_FILE).delete()
                capturedEvents.set(0)
            }
        }
        getCurrentApplication()?.let(IntegrityEventStore::clear)
    }

    override fun readConfig(): String = synchronized(configLock) { config.toString() }

    override fun log(level: Int, tag: String, message: String) {
        logWithLevel(level, tag, message)
    }

    override fun getPackageNames(userId: Int): Array<String> {
        val app = getCurrentApplication() ?: return emptyArray()
        val pm = app.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrElse { emptyList() }
            .map { it.packageName }
            .toTypedArray()
    }

    override fun getPackageInfo(packageName: String, userId: Int): PackageInfo? {
        val app = getCurrentApplication() ?: return null
        val pm = app.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    override fun getLogFileLocation(): String = synchronized(logLock) {
        ensureLogFile()?.absolutePath ?: "unavailable"
    }

    private fun startHeartbeatLoop() {
        if (!heartbeatLoopStarted.compareAndSet(false, true)) return
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun touchHealthcheck() {
        lastHealthcheckTimestamp.set(System.currentTimeMillis())
    }

    private fun rotateLogs(current: File) {
        runCatching {
            val old = File(current.parentFile, RUNTIME_LOG_OLD_FILE)
            if (old.exists()) old.delete()
            if (current.exists()) current.renameTo(old)
            current.writeText("")
        }
    }

    private fun ensureLogFile(): File? {
        runtimeLogFile?.let { return it }

        val app = getCurrentApplication() ?: return null
        val baseDir = app.getExternalFilesDir(null) ?: app.filesDir
        if (!baseDir.exists()) {
            runCatching { baseDir.mkdirs() }
        }

        val file = File(baseDir, RUNTIME_LOG_FILE)
        if (!file.exists()) {
            runCatching { file.createNewFile() }
        }
        runtimeLogFile = file
        return file
    }

    private fun ensureConfigSnapshotFile(): File? {
        configSnapshotFile?.let { return it }

        val app = getCurrentApplication() ?: return null
        val baseDir = app.getExternalFilesDir(null) ?: app.filesDir
        if (!baseDir.exists()) {
            runCatching { baseDir.mkdirs() }
        }

        val file = File(baseDir, CONFIG_SNAPSHOT_FILE)
        if (!file.exists()) {
            runCatching { file.createNewFile() }
        }
        configSnapshotFile = file
        return file
    }

    private fun loadPersistedConfigSnapshot() {
        val file = ensureConfigSnapshotFile() ?: return
        if (!file.exists() || file.length() == 0L) return

        runCatching {
            JsonConfig.parse(file.readText()).apply {
                configVersion = BuildConfig.CONFIG_VERSION
            }
        }.onSuccess { restored ->
            synchronized(configLock) {
                config = restored
            }
        }.onFailure {
            logW(TAG, "Failed to load persisted config snapshot", it)
        }
    }

    private fun persistConfigSnapshot(text: String) {
        val file = ensureConfigSnapshotFile() ?: return
        runCatching {
            file.writeText(text)
        }.onFailure {
            logW(TAG, "Failed to persist config snapshot", it)
        }
    }

    private fun getCurrentApplication(): Application? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication: Method = activityThread.getDeclaredMethod("currentApplication")
            currentApplication.invoke(null) as? Application
        }.getOrNull()
    }
}
