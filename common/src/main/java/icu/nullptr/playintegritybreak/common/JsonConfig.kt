package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import it.eldavo.pib.common.BuildConfig

@Serializable
data class JsonConfig(
    var configVersion: Int = BuildConfig.CONFIG_VERSION,
    var integrityModeMigrated: Boolean = false,
    var detailLog: Boolean = false,
    var errorOnlyLog: Boolean = false,
    var defaultInterventionEnabled: Boolean = true,
    var defaultHookRewriteEnabled: Boolean = true,
    var defaultHookRewriteErrorCode: Int = -8,
    var defaultHookRewriteRemediable: Boolean = true,
    var defaultDeliverSyntheticResponse: Boolean = true,
    var defaultDelaySyntheticResponseDelivery: Boolean = false,
    var maxLogSize: Int = 512,
    var telemetryEnabled: Boolean = false,
    var telemetryBatchSize: Int = 100,
    var telemetryMaxAttempts: Int = 8,
    var telemetryBaseRetrySeconds: Int = 30,
    var telemetryLeaseDurationSeconds: Int = 120,
    var telemetryStaleInFlightMinutes: Int = 15,
    var userId: String = "",
    var forceMountData: Boolean = true,
    var disableActivityLaunchProtection: Boolean = false,
    var altAppDataIsolation: Boolean = false,
    var altVoldAppDataIsolation: Boolean = false,
    var skipSystemAppDataIsolation: Boolean = true,
    var packageQueryWorkaround: Boolean = false,
    var favoritePackages: MutableSet<String> = mutableSetOf(),
    val scope: MutableMap<String, AppConfig> = mutableMapOf()
) {
    @Serializable
    data class AppConfig(
        var interventionEnabled: Boolean = true,
        var integrityLoggerEnabled: Boolean = true,
        var logIntegrityRequests: Boolean = true,
        var logIntegrityResponses: Boolean = true,
        var rewriteIntegrityResponseOverridden: Boolean = false,
        var rewriteIntegrityResponse: Boolean = false,
        var rewriteIntegrityErrorCode: Int = -8,
        var rewriteIntegrityErrorRemediable: Boolean = true,
        var deliverSyntheticResponse: Boolean = true,
        var delaySyntheticResponseDelivery: Boolean = false,
    ) {
        override fun toString() = encoder.encodeToString(this)

        companion object {
            fun parse(json: String) = encoder.decodeFromString<AppConfig>(json)
        }
    }

    companion object {
        fun parse(json: String): JsonConfig {
            val parsed = encoder.decodeFromString<JsonConfig>(json)
            val root = runCatching { encoder.parseToJsonElement(json).jsonObject }.getOrNull()

            if (root != null && !root.containsKey("defaultInterventionEnabled")) {
                parsed.defaultInterventionEnabled = parsed.defaultHookRewriteEnabled
            }

            return parsed
        }

        private val encoder = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }

    override fun toString() = encoder.encodeToString(this)
}
