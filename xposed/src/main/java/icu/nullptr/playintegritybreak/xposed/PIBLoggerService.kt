package icu.nullptr.playintegritybreak.xposed

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.IPIBService
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.common.TelemetryBatchPayload
import icu.nullptr.playintegritybreak.common.TelemetryJsonCodec
import icu.nullptr.playintegritybreak.common.TelemetryQueueSnapshot
import icu.nullptr.playintegritybreak.common.TelemetryStatsPayload
import it.eldavo.pib.common.BuildConfig
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

    private val initialized = AtomicBoolean(false)
    private val heartbeatLoopStarted = AtomicBoolean(false)
    private val binderPublished = AtomicBoolean(false)
    private val publishFlushRunning = AtomicBoolean(false)
    private val capturedEvents = AtomicLong(0)
    private val lastHealthcheckTimestamp = AtomicLong(0)
    private val configLock = Any()
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
        val deliverSyntheticResponse: Boolean,
        val delaySyntheticResponseDelivery: Boolean,
    )

    private data class PendingIntegrityEvent(
        val timestampMs: Long,
        val packageName: String,
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
                val defaultRewriteEnabled = config.defaultHookRewriteEnabled
                IntegrityPolicy(
                    enabled = defaultRewriteEnabled,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = config.errorOnlyLog,
                    rewriteResponse = defaultRewriteEnabled,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                    deliverSyntheticResponse = true,
                    delaySyntheticResponseDelivery = false,
                )
            }
        }

        synchronized(configLock) {
            val appConfig = config.scope[callerPkg]
            val defaultRewriteEnabled = config.defaultHookRewriteEnabled
            if (appConfig == null) {
                return IntegrityPolicy(
                    enabled = defaultRewriteEnabled,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = config.errorOnlyLog,
                    rewriteResponse = defaultRewriteEnabled,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                    deliverSyntheticResponse = true,
                    delaySyntheticResponseDelivery = false,
                )
            }

            if (!appConfig.interventionEnabled) {
                return IntegrityPolicy(
                    enabled = false,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = true,
                    rewriteResponse = false,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                    deliverSyntheticResponse = appConfig.deliverSyntheticResponse,
                    delaySyntheticResponseDelivery = appConfig.delaySyntheticResponseDelivery,
                )
            }

            if (!appConfig.rewriteIntegrityResponseOverridden) {
                return IntegrityPolicy(
                    enabled = defaultRewriteEnabled,
                    logRequest = true,
                    logResponse = true,
                    errorOnly = config.errorOnlyLog,
                    rewriteResponse = defaultRewriteEnabled,
                    rewriteErrorCode = config.defaultHookRewriteErrorCode,
                    rewriteRemediable = config.defaultHookRewriteRemediable,
                    deliverSyntheticResponse = appConfig.deliverSyntheticResponse,
                    delaySyntheticResponseDelivery = appConfig.delaySyntheticResponseDelivery,
                )
            }

            return IntegrityPolicy(
                enabled = true,
                // Request/response logging is always enabled; only logger enable/error-only may filter output.
                logRequest = true,
                logResponse = true,
                errorOnly = config.errorOnlyLog,
                rewriteResponse = appConfig.rewriteIntegrityResponse,
                rewriteErrorCode = appConfig.rewriteIntegrityErrorCode,
                rewriteRemediable = appConfig.rewriteIntegrityErrorRemediable,
                deliverSyntheticResponse = appConfig.deliverSyntheticResponse,
                delaySyntheticResponseDelivery = appConfig.delaySyntheticResponseDelivery,
            )
        }
    }

    fun recordIntegrityRequest(callerPkg: String) {
        touchHealthcheck()
        enqueuePendingEvent(
            PendingIntegrityEvent(
                timestampMs = System.currentTimeMillis(),
                packageName = callerPkg,
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

        return runCatching {
            app.contentResolver.call(uri, Constants.PROVIDER_METHOD_LINK, null, extras) != null
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
                    Log.w(TAG, "Failed to publish integrity event to provider", it)
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

    override fun getTelemetryStatsJson(fromTimestampMs: Long): String {
        val app = getCurrentApplication() ?: return TelemetryJsonCodec.encodeStats(TelemetryStatsPayload())
        return TelemetryJsonCodec.encodeStats(
            IntegrityEventStore.getTelemetryStats(
                app = app,
                fromTimestampMs = fromTimestampMs.coerceAtLeast(0L),
            )
        )
    }

    override fun dequeueTelemetryBatchJson(maxEvents: Int, leaseDurationMs: Long, staleInFlightMs: Long): String {
        val app = getCurrentApplication() ?: return TelemetryJsonCodec.encodeBatch(TelemetryBatchPayload())
        if (staleInFlightMs > 0L) {
            val staleBefore = System.currentTimeMillis() - staleInFlightMs
            IntegrityEventStore.recoverStaleInFlight(app, staleBefore)
        }

        val batch = IntegrityEventStore.dequeueTelemetryBatch(
            app = app,
            maxEvents = maxEvents,
            leaseDurationMs = leaseDurationMs,
        )
        return TelemetryJsonCodec.encodeBatch(batch)
    }

    override fun ackTelemetryBatch(batchId: String?, serverAckId: String?) {
        val app = getCurrentApplication() ?: return
        if (batchId.isNullOrBlank()) return
        IntegrityEventStore.ackTelemetryBatch(app, batchId, serverAckId)
    }

    override fun nackTelemetryBatch(
        batchId: String?,
        retriable: Boolean,
        nextAttemptTimestampMs: Long,
        lastError: String?,
    ) {
        val app = getCurrentApplication() ?: return
        if (batchId.isNullOrBlank()) return
        IntegrityEventStore.nackTelemetryBatch(
            app = app,
            batchId = batchId,
            retriable = retriable,
            nextAttemptTimestampMs = nextAttemptTimestampMs,
            lastError = lastError,
        )
    }

    override fun getTelemetryQueueSnapshotJson(): String {
        val app = getCurrentApplication() ?: return TelemetryJsonCodec.encodeQueueSnapshot(TelemetryQueueSnapshot())
        return TelemetryJsonCodec.encodeQueueSnapshot(IntegrityEventStore.getTelemetryQueueSnapshot(app))
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
