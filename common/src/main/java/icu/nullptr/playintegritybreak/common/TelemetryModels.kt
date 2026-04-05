package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TelemetryQueueState {
    const val PENDING = "pending"
    const val RETRY = "retry"
    const val IN_FLIGHT = "in_flight"
    const val ACKED = "acked"
    const val FAILED = "failed"
}

@Serializable
data class TelemetryEventPayload(
    val id: Long,
    val timestampMs: Long,
    val userId: String? = null,
    val packageName: String,
    val playIntegrityVersionMajor: Int? = null,
    val playIntegrityVersionMinor: Int? = null,
    val playIntegrityVersionPatch: Int? = null,
    val eventType: String,
    val success: Boolean? = null,
    val errorCode: Int? = null,
    val retriable: Boolean? = null,
    val source: String,
    val attemptCount: Int = 0,
)

@Serializable
data class TelemetryBatchPayload(
    val batchId: String = "",
    val leaseExpiresAtMs: Long = 0L,
    val events: List<TelemetryEventPayload> = emptyList(),
)

@Serializable
data class TelemetryQueueSnapshot(
    val pending: Int = 0,
    val retry: Int = 0,
    val inFlight: Int = 0,
    val acknowledged: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class TelemetryPackageStat(
    val packageName: String,
    val requestCount: Int,
    val responseCount: Int,
    val errorCount: Int,
)

@Serializable
data class TelemetryRecentRequest(
    val timestampMs: Long,
    val packageName: String,
)

@Serializable
data class TelemetryStatsPayload(
    val generatedAtMs: Long = 0L,
    val fromTimestampMs: Long = 0L,
    val toTimestampMs: Long = 0L,
    val totalEvents: Int = 0,
    val totalRequests: Int = 0,
    val totalResponses: Int = 0,
    val totalSuccessResponses: Int = 0,
    val totalErrorResponses: Int = 0,
    val queue: TelemetryQueueSnapshot = TelemetryQueueSnapshot(),
    val topPackages: List<TelemetryPackageStat> = emptyList(),
    val recentRequests: List<TelemetryRecentRequest> = emptyList(),
)

object TelemetryJsonCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeBatch(payload: TelemetryBatchPayload): String = json.encodeToString(payload)

    fun decodeBatch(payload: String?): TelemetryBatchPayload {
        if (payload.isNullOrBlank()) return TelemetryBatchPayload()
        return runCatching { json.decodeFromString<TelemetryBatchPayload>(payload) }
            .getOrDefault(TelemetryBatchPayload())
    }

    fun encodeStats(payload: TelemetryStatsPayload): String = json.encodeToString(payload)

    fun decodeStats(payload: String?): TelemetryStatsPayload {
        if (payload.isNullOrBlank()) return TelemetryStatsPayload()
        return runCatching { json.decodeFromString<TelemetryStatsPayload>(payload) }
            .getOrDefault(TelemetryStatsPayload())
    }

    fun encodeQueueSnapshot(payload: TelemetryQueueSnapshot): String = json.encodeToString(payload)

    fun decodeQueueSnapshot(payload: String?): TelemetryQueueSnapshot {
        if (payload.isNullOrBlank()) return TelemetryQueueSnapshot()
        return runCatching { json.decodeFromString<TelemetryQueueSnapshot>(payload) }
            .getOrDefault(TelemetryQueueSnapshot())
    }
}
