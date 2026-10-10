package it.eldavo.pib.monitor

import org.json.JSONArray
import org.json.JSONObject

/** One run of the Play Store's own Play Integrity check (see PlayStoreIntegrityCheck in :core). */
data class IntegrityCheck(
    val timestampMs: Long,
    val status: Status,
    /** The verdict JSON for [Status.OK], otherwise the reason. */
    val payload: String,
) {
    enum class Status {
        /** The Play Store answered: [payload] is the verdict JSON. */
        OK,
        /** The Play Store's check failed or timed out: [payload] says why. */
        PLAY_STORE_ERROR,
        /** PIB's hook in the Play Store could not be reached, even after the Play Store was started. */
        UNREACHABLE,
    }

    /** Device verdict level, highest first, or null when the check did not produce a verdict. */
    enum class Level(val label: String) {
        STRONG("MEETS_STRONG_INTEGRITY"),
        DEVICE("MEETS_DEVICE_INTEGRITY"),
        BASIC("MEETS_BASIC_INTEGRITY"),
        NONE("NO_INTEGRITY"),
    }

    private val json: JSONObject? by lazy {
        if (status != Status.OK) null else runCatching { JSONObject(payload) }.getOrNull()
    }

    val deviceVerdicts: Set<String>
        get() {
            val verdicts = json?.optJSONObject("deviceIntegrity")?.optJSONArray("deviceRecognitionVerdict")
            return verdicts.toStringSet()
        }

    val level: Level?
        get() {
            if (json == null) return null
            val verdicts = deviceVerdicts
            return Level.entries.firstOrNull { it.label in verdicts } ?: Level.NONE
        }

    /** Human-readable JSON for [Status.OK], the raw payload otherwise. */
    val prettyPayload: String
        get() = json?.toString(2) ?: payload

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_TIMESTAMP, timestampMs)
        .put(KEY_STATUS, status.name)
        .put(KEY_PAYLOAD, payload)

    companion object {
        private const val KEY_TIMESTAMP = "timestampMs"
        private const val KEY_STATUS = "status"
        private const val KEY_PAYLOAD = "payload"

        fun fromJson(json: JSONObject): IntegrityCheck? = runCatching {
            IntegrityCheck(
                timestampMs = json.getLong(KEY_TIMESTAMP),
                status = Status.valueOf(json.getString(KEY_STATUS)),
                payload = json.optString(KEY_PAYLOAD),
            )
        }.getOrNull()

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }.toSet()
        }
    }
}
