package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The settings as they were before "synthetic errors for all apps" changed them: intervention and synthetic errors, for the
 * default and for each app (null when the app follows the default). Restoring puts these values back, but only where the
 * setting still has the value that was forced: a change the user made in the meantime is kept.
 */
@Serializable
data class InterventionSnapshot(
    val defaults: Boolean,
    val apps: Map<String, Boolean?>,
    /** The default for synthetic errors. Null in a snapshot taken before they were forced too, and then left alone. */
    val rewriteDefaults: Boolean? = null,
    val rewriteApps: Map<String, Boolean?> = emptyMap(),
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): InterventionSnapshot? =
            runCatching { json.decodeFromString<InterventionSnapshot>(text) }.getOrNull()

        /** The snapshot of [config], taken before [enableForAllApps]. [vendingPackage] is always in it. */
        fun of(config: JsonConfig, vendingPackage: String): InterventionSnapshot = InterventionSnapshot(
            defaults = config.defaults.interventionEnabled,
            apps = (config.scope.keys + vendingPackage).associateWith { config.scope[it]?.interventionEnabled },
            rewriteDefaults = config.defaults.rewriteResponse,
            rewriteApps = (config.scope.keys - vendingPackage).associateWith { config.scope[it]?.rewriteResponse },
        )

        /**
         * [config] with intervention and synthetic errors on for every app but [vendingPackage], which the monitor needs and
         * is pinned off. An app that follows the default now gets them from the default, and an app set to off is turned on.
         */
        fun enableForAllApps(config: JsonConfig, vendingPackage: String): JsonConfig {
            val scope = config.scope.mapValues { (pkg, app) ->
                if (pkg == vendingPackage) {
                    app.copy(interventionEnabled = false)
                } else {
                    app.copy(
                        interventionEnabled = forced(app.interventionEnabled),
                        rewriteResponse = forced(app.rewriteResponse),
                    )
                }
            }
            return config.copy(
                defaults = config.defaults.copy(interventionEnabled = true, rewriteResponse = true),
                scope = scope + (vendingPackage to (scope[vendingPackage] ?: JsonConfig.AppConfig())
                    .copy(interventionEnabled = false)),
            )
        }

        /** What [enableForAllApps] makes of an app's own [value]: off is turned on, and the rest is kept. */
        private fun forced(value: Boolean?): Boolean? = if (value == false) true else value

        /**
         * [config] with the values of [snapshot] put back where they still have the value [enableForAllApps] gave them. An
         * app removed since is not added back, and an app left with no override at all is removed again.
         */
        fun restore(config: JsonConfig, snapshot: InterventionSnapshot, vendingPackage: String): JsonConfig {
            val scope = config.scope.toMutableMap()
            for ((pkg, value) in snapshot.apps) {
                val app = scope[pkg] ?: continue
                val wasForced = if (pkg == vendingPackage) false else forced(value)
                if (app.interventionEnabled == wasForced) scope[pkg] = app.copy(interventionEnabled = value)
            }
            for ((pkg, value) in snapshot.rewriteApps) {
                val app = scope[pkg] ?: continue
                if (app.rewriteResponse == forced(value)) scope[pkg] = app.copy(rewriteResponse = value)
            }
            for (pkg in snapshot.apps.keys + snapshot.rewriteApps.keys) {
                if (scope[pkg]?.isEmpty() == true) scope.remove(pkg)
            }

            var defaults = config.defaults
            if (defaults.interventionEnabled) defaults = defaults.copy(interventionEnabled = snapshot.defaults)
            if (snapshot.rewriteDefaults != null && defaults.rewriteResponse) {
                defaults = defaults.copy(rewriteResponse = snapshot.rewriteDefaults)
            }
            return config.copy(defaults = defaults, scope = scope)
        }
    }
}
