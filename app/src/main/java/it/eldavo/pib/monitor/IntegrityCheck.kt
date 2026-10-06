package it.eldavo.pib.monitor

import org.json.JSONArray
import org.json.JSONObject

/** One integrity check run through the Play Integrity API Checker app. */
data class IntegrityCheck(
    val timestampMs: Long,
    val status: Status,
    /** Raw backend JSON for [Status.OK], the integrity error code for [Status.INTEGRITY_ERROR], else a message. */
    val payload: String,
) {
    enum class Status {
        /** The checker's backend answered: [payload] is its JSON. */
        OK,
        /** The Play Store answered with an error: [payload] is the Play Integrity error code. */
        INTEGRITY_ERROR,
        /** The checker's backend could not be reached or rejected the token. */
        SERVER_ERROR,
        /** The hook in the checker failed before getting an answer. */
        INTERNAL_ERROR,
        /** The checker answered, but PIB's hook is not running in it. */
        NOT_HOOKED,
        CHECKER_NOT_INSTALLED,
        /** Neither PIB's Play Store hook nor root was available to start the checker. */
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

    /** Error reported inside the backend JSON (e.g. an expired or invalid token). */
    val backendError: String?
        get() = json?.optString("error")?.takeIf { it.isNotEmpty() }

    val deviceVerdicts: Set<String>
        get() {
            val verdicts = json?.optJSONObject("deviceIntegrity")?.optJSONArray("deviceRecognitionVerdict")
            return verdicts.toStringSet()
        }

    val level: Level?
        get() {
            if (json == null || backendError != null) return null
            val verdicts = deviceVerdicts
            return Level.entries.firstOrNull { it.label in verdicts } ?: Level.NONE
        }

    val integrityErrorCode: Int?
        get() = if (status == Status.INTEGRITY_ERROR) payload.toIntOrNull() else null

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

        /** Names of the Play Integrity (classic request) error codes. */
        fun integrityErrorName(code: Int): String = when (code) {
            -1 -> "API_NOT_AVAILABLE"
            -2 -> "PLAY_STORE_NOT_FOUND"
            -3 -> "NETWORK_ERROR"
            -4 -> "PLAY_STORE_ACCOUNT_NOT_FOUND"
            -5 -> "APP_NOT_INSTALLED"
            -6 -> "PLAY_SERVICES_NOT_FOUND"
            -7 -> "APP_UID_MISMATCH"
            -8 -> "TOO_MANY_REQUESTS"
            -9 -> "CANNOT_BIND_TO_SERVICE"
            -10 -> "NONCE_TOO_SHORT"
            -11 -> "NONCE_TOO_LONG"
            -12 -> "GOOGLE_SERVER_UNAVAILABLE"
            -13 -> "NONCE_IS_NOT_BASE64"
            -14 -> "PLAY_STORE_VERSION_OUTDATED"
            -15 -> "PLAY_SERVICES_VERSION_OUTDATED"
            -16 -> "CLOUD_PROJECT_NUMBER_IS_INVALID"
            -17 -> "CLIENT_TRANSIENT_ERROR"
            -100 -> "INTERNAL_ERROR"
            else -> "UNKNOWN_ERROR"
        }
    }
}
