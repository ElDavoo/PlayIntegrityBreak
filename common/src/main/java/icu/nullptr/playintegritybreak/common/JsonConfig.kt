package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import it.eldavo.pib.common.BuildConfig

@Serializable
data class JsonConfig(
    val configVersion: Int = BuildConfig.CONFIG_VERSION,
    val detailLog: Boolean = false,
    val errorOnlyLog: Boolean = false,
    val maxLogSize: Int = 512,
    val telemetryEnabled: Boolean = false,
    val intentApiEnabled: Boolean = false,
    val userId: String = "",
    val packageQueryWorkaround: Boolean = false,
    val favoritePackages: Set<String> = emptySet(),
    /** How the app tells the user that an app asked for Play Integrity, see [Policy.requestAlert]. */
    val requestAlertStyle: AlertStyle = AlertStyle.TOAST,
    val defaults: Policy = Policy(),
    val scope: Map<String, AppConfig> = DEFAULT_SCOPE,
) {
    /** A fully specified policy. Used for the defaults and as the result of [policyFor]. */
    @Serializable
    data class Policy(
        val interventionEnabled: Boolean = true,
        val rewriteResponse: Boolean = true,
        val rewriteErrorCode: Int = -8,
        val rewriteRemediable: Boolean = true,
        val deliverSyntheticResponse: Boolean = true,
        val delaySyntheticResponse: Boolean = false,
        val requestAlert: Boolean = true,
    )

    @Serializable
    enum class AlertStyle { TOAST, NOTIFICATION }

    /** Per-app overrides. A null field follows [defaults]. */
    @Serializable
    data class AppConfig(
        val interventionEnabled: Boolean? = null,
        val rewriteResponse: Boolean? = null,
        val rewriteErrorCode: Int? = null,
        val rewriteRemediable: Boolean? = null,
        val deliverSyntheticResponse: Boolean? = null,
        val delaySyntheticResponse: Boolean? = null,
        val requestAlert: Boolean? = null,
    ) {
        fun isEmpty() = this == AppConfig()

        fun applyTo(base: Policy) = Policy(
            interventionEnabled = interventionEnabled ?: base.interventionEnabled,
            rewriteResponse = rewriteResponse ?: base.rewriteResponse,
            rewriteErrorCode = rewriteErrorCode ?: base.rewriteErrorCode,
            rewriteRemediable = rewriteRemediable ?: base.rewriteRemediable,
            deliverSyntheticResponse = deliverSyntheticResponse ?: base.deliverSyntheticResponse,
            delaySyntheticResponse = delaySyntheticResponse ?: base.delaySyntheticResponse,
            requestAlert = requestAlert ?: base.requestAlert,
        )
    }

    /**
     * The effective policy for [packageName]. Rewrite settings are returned as stored even when
     * intervention is off; callers gate on [Policy.interventionEnabled].
     */
    fun policyFor(packageName: String): Policy = scope[packageName]?.applyTo(defaults) ?: defaults

    override fun toString() = encoder.encodeToString(this)

    companion object {
        /** First config version with [defaults] and nullable per-app overrides. */
        const val OVERRIDES_CONFIG_VERSION = 94

        /** First config version where the Play Store's responses are not rewritten by default ([DEFAULT_SCOPE]). */
        const val PLAY_STORE_DEFAULT_CONFIG_VERSION = 95

        /**
         * The Play Store's responses are not rewritten by default: the integrity monitor checks through the Play Store,
         * and gets no verdict while they are. It is an override, so it shows in the Play Store's settings and can be changed.
         */
        val DEFAULT_SCOPE = mapOf(Constants.VENDING_PACKAGE_NAME to AppConfig(rewriteResponse = false))

        private val encoder = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** Parses any config version. The returned [configVersion] is the one found in [json]. */
        fun parse(json: String): JsonConfig {
            val root = encoder.parseToJsonElement(json).jsonObject
            val version = root.int("configVersion") ?: 0
            val current = if (version < OVERRIDES_CONFIG_VERSION) migrateLegacy(root) else root
            val parsed = encoder.decodeFromJsonElement(serializer(), current)
            return if (version < PLAY_STORE_DEFAULT_CONFIG_VERSION) withPlayStoreDefault(parsed) else parsed
        }

        /** Gives older configs the Play Store default of [DEFAULT_SCOPE], unless rewriting was set for the Play Store. */
        private fun withPlayStoreDefault(config: JsonConfig): JsonConfig {
            val vending = config.scope[Constants.VENDING_PACKAGE_NAME] ?: AppConfig()
            if (vending.rewriteResponse != null) return config
            return config.copy(scope = config.scope + (Constants.VENDING_PACKAGE_NAME to vending.copy(rewriteResponse = false)))
        }

        /**
         * Converts the old flat default* fields and the rewriteIntegrityResponseOverridden flag
         * into [defaults] and per-app overrides, keeping what the hook used to do for every app.
         */
        private fun migrateLegacy(root: JsonObject): JsonObject {
            val rewriteDefault = root.bool("defaultHookRewriteEnabled") ?: true
            val defaults = Policy(
                interventionEnabled = root.bool("defaultInterventionEnabled") ?: rewriteDefault,
                rewriteResponse = rewriteDefault,
                rewriteErrorCode = root.int("defaultHookRewriteErrorCode") ?: -8,
                rewriteRemediable = root.bool("defaultHookRewriteRemediable") ?: true,
                deliverSyntheticResponse = root.bool("defaultDeliverSyntheticResponse") ?: true,
                delaySyntheticResponse = root.bool("defaultDelaySyntheticResponseDelivery") ?: false,
                requestAlert = root.bool("integrityRequestToast") ?: true,
            )
            // Configs from before the integrity logger reset every app to logger defaults on load.
            val loggerMigrated = root.bool("integrityModeMigrated") ?: false
            val scope = (root["scope"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                migrateLegacyApp(value as? JsonObject ?: JsonObject(emptyMap()), defaults, loggerMigrated)
            }

            return JsonObject(
                root + mapOf(
                    "defaults" to encoder.encodeToJsonElement(Policy.serializer(), defaults),
                    "scope" to JsonObject(scope.mapValues { encoder.encodeToJsonElement(AppConfig.serializer(), it.value) }),
                )
            )
        }

        private fun migrateLegacyApp(app: JsonObject, defaults: Policy, loggerMigrated: Boolean): AppConfig {
            fun <T> differing(value: T, default: T) = value.takeIf { it != default }

            val toast = if (app.bool("integrityRequestToast") == false) false else null
            val deliver = if (loggerMigrated) app.bool("deliverSyntheticResponse") ?: true else true
            val delay = if (loggerMigrated) app.bool("delaySyntheticResponseDelivery") ?: false else false
            val synthetic = AppConfig(
                deliverSyntheticResponse = differing(deliver, defaults.deliverSyntheticResponse),
                delaySyntheticResponse = differing(delay, defaults.delaySyntheticResponse),
                requestAlert = toast,
            )
            if (!loggerMigrated) return synthetic

            if (app.bool("interventionEnabled") == false) {
                return synthetic.copy(interventionEnabled = false)
            }
            if (app.bool("rewriteIntegrityResponseOverridden") != true) {
                return synthetic
            }
            return synthetic.copy(
                interventionEnabled = differing(true, defaults.interventionEnabled),
                rewriteResponse = differing(app.bool("rewriteIntegrityResponse") ?: false, defaults.rewriteResponse),
                rewriteErrorCode = differing(app.int("rewriteIntegrityErrorCode") ?: -8, defaults.rewriteErrorCode),
                rewriteRemediable = differing(app.bool("rewriteIntegrityErrorRemediable") ?: true, defaults.rewriteRemediable),
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
        private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
        private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
    }
}
