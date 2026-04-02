package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.frknkrc44.pib_oss.common.BuildConfig

@Serializable
data class JsonConfig(
    var configVersion: Int = BuildConfig.CONFIG_VERSION,
    var integrityModeMigrated: Boolean = false,
    var detailLog: Boolean = false,
    var errorOnlyLog: Boolean = false,
    var defaultHookRewriteEnabled: Boolean = false,
    var defaultHookRewriteErrorCode: Int = -8,
    var defaultHookRewriteRemediable: Boolean = true,
    var defaultHookRewriteCallerPackages: MutableSet<String> = mutableSetOf(),
    var maxLogSize: Int = 512,
    var forceMountData: Boolean = true,
    var disableActivityLaunchProtection: Boolean = false,
    var altAppDataIsolation: Boolean = false,
    var altVoldAppDataIsolation: Boolean = false,
    var skipSystemAppDataIsolation: Boolean = true,
    var packageQueryWorkaround: Boolean = false,
    val scope: MutableMap<String, AppConfig> = mutableMapOf()
) {
    @Serializable
    data class AppConfig(
        var integrityLoggerEnabled: Boolean = true,
        var logIntegrityRequests: Boolean = true,
        var logIntegrityResponses: Boolean = true,
        var logIntegrityErrorsOnly: Boolean = false,
        var rewriteIntegrityResponse: Boolean = false,
        var rewriteIntegrityErrorCode: Int = -8,
        var rewriteIntegrityErrorRemediable: Boolean = true,
    ) {
        override fun toString() = encoder.encodeToString(this)

        companion object {
            fun parse(json: String) = encoder.decodeFromString<AppConfig>(json)
        }
    }

    companion object {
        fun parse(json: String) = encoder.decodeFromString<JsonConfig>(json)

        private val encoder = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }

    override fun toString() = encoder.encodeToString(this)
}
