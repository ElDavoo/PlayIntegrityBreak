package icu.nullptr.playintegritybreak.telemetry

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.ServiceClient
import kotlin.random.Random

class TelemetryUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!ConfigManager.telemetryEnabled) {
            return Result.success()
        }

        AppIntegrityEventStore.recoverStaleInFlight(System.currentTimeMillis() - STALE_IN_FLIGHT_MS)

        val batch = AppIntegrityEventStore.dequeueTelemetryBatch(
            maxEvents = BATCH_SIZE,
            leaseDurationMs = LEASE_DURATION_MS,
        )

        if (batch.events.isEmpty()) {
            return Result.success()
        }

        val outcome = TelemetryApiClient.uploadBatch(batch)

        if (outcome.accepted) {
            AppIntegrityEventStore.ackTelemetryBatch(batch.batchId, outcome.ackId ?: "")
            ServiceClient.log(
                Log.INFO,
                TAG,
                "Uploaded telemetry batch ${batch.batchId} (${batch.events.size} events)",
            )
            return Result.success()
        }

        val attempt = batch.events.maxOfOrNull { it.attemptCount } ?: 1
        val canRetry = outcome.retriable && attempt < MAX_ATTEMPTS

        if (canRetry) {
            val retryDelayMs = computeRetryDelayMs(
                attempt = attempt,
                serverRetryAfterMs = outcome.retryAfterMs,
            )
            val nextAttemptAt = System.currentTimeMillis() + retryDelayMs

            AppIntegrityEventStore.nackTelemetryBatch(
                batchId = batch.batchId,
                retriable = true,
                nextAttemptTimestampMs = nextAttemptAt,
                lastError = outcome.errorMessage ?: "Retryable telemetry upload failure",
            )
            ServiceClient.log(
                Log.WARN,
                TAG,
                "Telemetry upload retry scheduled for ${batch.batchId} in ${retryDelayMs}ms",
            )
            return Result.retry()
        }

        AppIntegrityEventStore.nackTelemetryBatch(
            batchId = batch.batchId,
            retriable = false,
            nextAttemptTimestampMs = 0L,
            lastError = outcome.errorMessage ?: "Permanent telemetry upload failure",
        )
        ServiceClient.log(
            Log.ERROR,
            TAG,
            "Telemetry upload permanently failed for ${batch.batchId}: ${outcome.errorMessage}",
        )

        return Result.success()
    }

    private fun computeRetryDelayMs(attempt: Int, serverRetryAfterMs: Long?): Long {
        val serverDelay = serverRetryAfterMs?.takeIf { it > 0L }
        if (serverDelay != null) {
            return serverDelay
        }

        val baseDelayMs = BASE_RETRY_DELAY_MS
        val exponent = (attempt - 1).coerceIn(0, 8)
        val exponentialDelay = (baseDelayMs * (1L shl exponent)).coerceAtMost(MAX_RETRY_DELAY_MS)
        val jitter = Random.nextLong((baseDelayMs / 3).coerceAtLeast(1L))
        return (exponentialDelay + jitter).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    companion object {
        private const val TAG = "TelemetryUploadWorker"
        private const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L
        private const val BATCH_SIZE = 10
        private const val MAX_ATTEMPTS = 8
        private const val BASE_RETRY_DELAY_MS = 30_000L
        private const val LEASE_DURATION_MS = 120_000L
        private const val STALE_IN_FLIGHT_MS = 15 * 60_000L
    }
}
