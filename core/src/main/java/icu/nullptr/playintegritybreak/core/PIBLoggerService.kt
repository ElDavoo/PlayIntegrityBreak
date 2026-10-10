package icu.nullptr.playintegritybreak.core

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.IIntegrityCheckCallback
import icu.nullptr.playintegritybreak.common.IPIBService
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.common.PlayStoreVerdict
import it.eldavo.pib.common.BuildConfig
import kotlin.concurrent.thread
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
    private const val EVENT_TYPE_REQUEST = "request"
    private const val EVENT_TYPE_RESPONSE = "response"
    private const val MAX_BUFFERED_EVENTS = 2_000
    private const val PLAY_INTEGRITY_VERSION_MAJOR = "playcore.integrity.version.major"
    private const val PLAY_INTEGRITY_VERSION_MINOR = "playcore.integrity.version.minor"
    private const val PLAY_INTEGRITY_VERSION_PATCH = "playcore.integrity.version.patch"

    private val initialized = AtomicBoolean(false)
    private val heartbeatLoopStarted = AtomicBoolean(false)
    private val binderPublished = AtomicBoolean(false)
    private val configSnapshotRestored = AtomicBoolean(false)
    private val publishFlushRunning = AtomicBoolean(false)
    private val capturedEvents = AtomicLong(0)
    private val lastHealthcheckTimestamp = AtomicLong(0)
    private val lastProviderMissingLogTimestamp = AtomicLong(0)
    private val logLock = Any()
    private val pendingEventLock = Any()
    private val pendingEvents = ArrayDeque<PendingIntegrityEvent>()

    private val heartbeatHandler by lazy { Handler(Looper.getMainLooper()) }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get()) {
                heartbeatLoopStarted.set(false)
                return
            }

            touchHealthcheck()
            tryPublishBinderToClientApp()
            flushPendingEvents()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    @Volatile
    private var config = JsonConfig(detailLog = true)

    @Volatile
    private var runtimeLogFile: File? = null

    @Volatile
    private var configSnapshotFile: File? = null

    private data class PendingIntegrityEvent(
        val timestampMs: Long,
        val packageName: String,
        val playIntegrityVersionMajor: Int?,
        val playIntegrityVersionMinor: Int?,
        val playIntegrityVersionPatch: Int?,
        val userId: String?,
        val eventType: String,
        val success: Boolean?,
        val errorCode: Int?,
        val retriable: Boolean?,
        val source: String,
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

    fun isErrorOnlyLogging(): Boolean = config.errorOnlyLog

    fun isDetailLogging(): Boolean = config.detailLog

    fun appendParsedLog(parsedMsg: String) {
        touchHealthcheck()
        synchronized(logLock) {
            val file = ensureLogFile() ?: return
            val maxLogSizeKb = config.maxLogSize
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

    fun resolvePolicy(callerPkg: String): JsonConfig.Policy {
        touchHealthcheck()
        loadPersistedConfigSnapshot()
        return config.policyFor(callerPkg)
    }

    fun recordIntegrityRequest(
        callerPkg: String,
        playIntegrityVersionMajor: Int?,
        playIntegrityVersionMinor: Int?,
        playIntegrityVersionPatch: Int?,
    ) {
        touchHealthcheck()
        enqueuePendingEvent(
            PendingIntegrityEvent(
                timestampMs = System.currentTimeMillis(),
                packageName = callerPkg,
                playIntegrityVersionMajor = playIntegrityVersionMajor,
                playIntegrityVersionMinor = playIntegrityVersionMinor,
                playIntegrityVersionPatch = playIntegrityVersionPatch,
                userId = currentUserId(),
                eventType = EVENT_TYPE_REQUEST,
                success = null,
                errorCode = null,
                retriable = null,
                source = "request-intercepted",
            )
        )
    }

    fun recordIntegrityResponse(
        callerPkg: String,
        playIntegrityVersionMajor: Int?,
        playIntegrityVersionMinor: Int?,
        playIntegrityVersionPatch: Int?,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        touchHealthcheck()
        enqueuePendingEvent(
            PendingIntegrityEvent(
                timestampMs = System.currentTimeMillis(),
                packageName = callerPkg,
                playIntegrityVersionMajor = playIntegrityVersionMajor,
                playIntegrityVersionMinor = playIntegrityVersionMinor,
                playIntegrityVersionPatch = playIntegrityVersionPatch,
                userId = currentUserId(),
                eventType = EVENT_TYPE_RESPONSE,
                success = success,
                errorCode = errorCode,
                retriable = retriable,
                source = source,
            )
        )
    }

    fun tryPublishBinderToClientApp(): Boolean {
        touchHealthcheck()
        val app = getCurrentApplication() ?: return false
        val extras = Bundle().apply { putBinder(Constants.PROVIDER_EXTRA_BINDER, this@PIBLoggerService) }
        val uri = Uri.parse("content://${Constants.PROVIDER_AUTHORITY}")

        // During startup the provider authority may not be registered yet.
        if (!isProviderRegistered(app, uri)) {
            maybeLogProviderNotReady()
            return false
        }

        return runCatching {
            val response = app.contentResolver.call(uri, Constants.PROVIDER_METHOD_LINK, null, extras)
            val linked = response?.getBoolean(Constants.PROVIDER_RESULT_OK, false) == true
            if (!linked) {
                logW(TAG, "Provider link call returned no acknowledgement")
            }
            linked
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

    private fun enqueuePendingEvent(event: PendingIntegrityEvent) {
        synchronized(pendingEventLock) {
            if (pendingEvents.size >= MAX_BUFFERED_EVENTS) {
                pendingEvents.removeFirstOrNull()
                logW(TAG, "Pending event buffer full, dropping oldest event")
            }
            pendingEvents.addLast(event)
        }
        flushPendingEvents()
    }

    private fun flushPendingEvents(): Boolean {
        val app = getCurrentApplication() ?: return false
        if (!publishFlushRunning.compareAndSet(false, true)) return false

        try {
            val uri = Uri.parse("content://${Constants.PROVIDER_AUTHORITY}")
            while (true) {
                val event = synchronized(pendingEventLock) {
                    pendingEvents.firstOrNull()
                } ?: return true

                val extras = Bundle().apply {
                    putLong(Constants.PROVIDER_EXTRA_EVENT_TIMESTAMP_MS, event.timestampMs)
                    putString(Constants.PROVIDER_EXTRA_EVENT_PACKAGE, event.packageName)
                    event.playIntegrityVersionMajor?.let { putInt(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MAJOR, it) }
                    event.playIntegrityVersionMinor?.let { putInt(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MINOR, it) }
                    event.playIntegrityVersionPatch?.let { putInt(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_PATCH, it) }
                    event.userId?.let { putString(Constants.PROVIDER_EXTRA_EVENT_USER_ID, it) }
                    putString(Constants.PROVIDER_EXTRA_EVENT_TYPE, event.eventType)
                    putString(Constants.PROVIDER_EXTRA_EVENT_SOURCE, event.source)
                    event.success?.let { putBoolean(Constants.PROVIDER_EXTRA_EVENT_SUCCESS, it) }
                    event.errorCode?.let { putInt(Constants.PROVIDER_EXTRA_EVENT_ERROR_CODE, it) }
                    event.retriable?.let { putBoolean(Constants.PROVIDER_EXTRA_EVENT_RETRIABLE, it) }
                }

                val stored = runCatching {
                    app.contentResolver.call(uri, Constants.PROVIDER_METHOD_PUBLISH_EVENT, null, extras)
                        ?.getBoolean(Constants.PROVIDER_RESULT_OK, false) == true
                }.onFailure {
                    logW(TAG, "Failed to publish integrity event to provider", it)
                }.getOrDefault(false)

                if (!stored) {
                    return false
                }

                synchronized(pendingEventLock) {
                    pendingEvents.removeFirstOrNull()
                }
            }
        } finally {
            publishFlushRunning.set(false)
        }
    }

    override fun writeConfig(json: String) {
        val parsedConfig = runCatching {
            JsonConfig.parse(json)
        }.onFailure {
            logE(TAG, "Failed to parse config", it)
        }.getOrNull() ?: return

        config = parsedConfig
        // The app's config is newer than any snapshot on disk.
        configSnapshotRestored.set(true)
        persistConfigSnapshot(parsedConfig.toString())
    }

    override fun getServiceVersion(): Int = BuildConfig.SERVICE_VERSION

    override fun getServiceHealthcheckTimestamp(): Long = lastHealthcheckTimestamp.get()

    override fun getFilterCount(): Int {
        val inMemoryCount = capturedEvents.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pendingCount = synchronized(pendingEventLock) { pendingEvents.size }
        return (inMemoryCount + pendingCount).coerceAtLeast(inMemoryCount)
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
        synchronized(pendingEventLock) {
            pendingEvents.clear()
        }
    }

    override fun readConfig(): String = config.toString()

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

    override fun getBackendName(): String = Backend.name

    override fun runPlayStoreIntegrityCheck(callback: IIntegrityCheckCallback) {
        thread(name = "PIB-PlayStoreCheck") {
            // This thread is in the Play Store: anything it throws kills the Play Store.
            try {
                val result = PlayStoreIntegrityCheck.run()
                when (result.status) {
                    PlayStoreIntegrityCheck.Status.OK -> callback.onResult(
                        Constants.PLAY_STORE_RESULT_OK,
                        PlayStoreVerdict.toVerdictJson(result.labels),
                    )
                    else -> callback.onResult(
                        Constants.PLAY_STORE_RESULT_FAILED,
                        "${result.status}: ${result.detail.orEmpty()}",
                    )
                }
            } catch (e: Throwable) {
                // Mostly a DeadObjectException: PIB was killed during the check.
                logW(TAG, "Play Store check result not delivered", e)
            }
        }
    }

    private fun Bundle.getIntOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        return getInt(key)
    }

    private fun currentUserId(): String? {
        val value = config.userId.trim()
        return value.takeIf { it.isNotEmpty() }
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

    /**
     * Restores the config the app last pushed. Hooks can be installed before the Application
     * exists (the snapshot lives in its files dir), so this is retried until it can run once.
     */
    private fun loadPersistedConfigSnapshot() {
        if (configSnapshotRestored.get()) return
        val file = ensureConfigSnapshotFile() ?: return
        if (!configSnapshotRestored.compareAndSet(false, true)) return
        if (!file.exists() || file.length() == 0L) return

        runCatching {
            JsonConfig.parse(file.readText())
        }.onSuccess { restored ->
            config = restored
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

    private fun isProviderRegistered(app: Application, uri: Uri): Boolean {
        return runCatching {
            app.contentResolver.acquireUnstableContentProviderClient(uri)?.close() != null
        }.getOrDefault(false)
    }

    private fun maybeLogProviderNotReady() {
        val now = System.currentTimeMillis()
        val last = lastProviderMissingLogTimestamp.get()
        if (now - last < 30_000L) return
        if (lastProviderMissingLogTimestamp.compareAndSet(last, now)) {
            logI(TAG, "Provider not ready yet; binder publish will retry")
        }
    }
}
