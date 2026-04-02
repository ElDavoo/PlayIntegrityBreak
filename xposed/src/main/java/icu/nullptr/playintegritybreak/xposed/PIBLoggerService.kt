package icu.nullptr.playintegritybreak.xposed

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private val initialized = AtomicBoolean(false)
    private val capturedEvents = AtomicLong(0)
    private val configLock = Any()
    private val logLock = Any()

    @Volatile
    private var config = JsonConfig().apply {
        detailLog = true
        errorOnlyLog = false
    }

    @Volatile
    private var runtimeLogFile: File? = null

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
        if (!initialized.compareAndSet(false, true)) return
        ensureLogFile()
        tryPublishBinderToClientApp()
        logI(TAG, "PIB logger service initialized")
    }

    fun isActive(): Boolean = initialized.get()

    fun isErrorOnlyLogging(): Boolean = synchronized(configLock) { config.errorOnlyLog }

    fun isDetailLogging(): Boolean = synchronized(configLock) { config.detailLog }

    fun appendParsedLog(parsedMsg: String) {
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
        if (callerPkg.isBlank() || callerPkg == "unknown") {
            return IntegrityPolicy(
                enabled = true,
                logRequest = true,
                logResponse = true,
                errorOnly = isErrorOnlyLogging(),
                rewriteResponse = false,
                rewriteErrorCode = -8,
                rewriteRemediable = true,
            )
        }

        synchronized(configLock) {
            val appConfig = config.scope[callerPkg]
            val hasScopedApps = config.scope.isNotEmpty()
            if (appConfig == null && hasScopedApps) {
                return IntegrityPolicy(
                    enabled = false,
                    logRequest = false,
                    logResponse = false,
                    errorOnly = true,
                    rewriteResponse = false,
                    rewriteErrorCode = -8,
                    rewriteRemediable = true,
                )
            }

            return IntegrityPolicy(
                enabled = appConfig?.integrityLoggerEnabled ?: true,
                logRequest = appConfig?.logIntegrityRequests ?: true,
                logResponse = appConfig?.logIntegrityResponses ?: true,
                errorOnly = config.errorOnlyLog || (appConfig?.logIntegrityErrorsOnly ?: false),
                rewriteResponse = appConfig?.rewriteIntegrityResponse ?: false,
                rewriteErrorCode = appConfig?.rewriteIntegrityErrorCode ?: -8,
                rewriteRemediable = appConfig?.rewriteIntegrityErrorRemediable ?: true,
            )
        }
    }

    fun tryPublishBinderToClientApp(): Boolean {
        val app = getCurrentApplication() ?: return false
        val extras = Bundle().apply { putBinder("binder", this@PIBLoggerService) }
        val uri = Uri.parse("content://${Constants.PROVIDER_AUTHORITY}")

        return runCatching {
            app.contentResolver.call(uri, "link", null, extras) != null
        }.onSuccess { success ->
            if (success) {
                logI(TAG, "Published logger binder to app")
            }
        }.onFailure {
            logW(TAG, "Failed to publish logger binder", it)
        }.getOrDefault(false)
    }

    override fun writeConfig(json: String) {
        synchronized(configLock) {
            runCatching {
                config = JsonConfig.parse(json).apply {
                    configVersion = BuildConfig.CONFIG_VERSION
                }
            }.onFailure {
                logE(TAG, "Failed to parse config", it)
            }
        }
    }

    override fun getServiceVersion() = BuildConfig.SERVICE_VERSION

    override fun getFilterCount() = capturedEvents.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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

    private fun getCurrentApplication(): Application? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication: Method = activityThread.getDeclaredMethod("currentApplication")
            currentApplication.invoke(null) as? Application
        }.getOrNull()
    }
}
