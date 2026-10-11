package it.eldavo.pib.monitor

import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import org.json.JSONObject

/**
 * Turns intervention on for every app but the Play Store, which the monitor needs to get its verdict. Restoring puts back
 * what each app had before, so a per-app setting the user made is not lost.
 */
object AllAppsIntervention {
    private const val TAG = "AllAppsIntervention"

    /** Turns intervention on for all apps. Does nothing when it is already on from here. */
    fun enable() {
        if (PrefManager.integrityAutomationInterventionSnapshot.isNotEmpty()) return
        ConfigManager.update { current ->
            PrefManager.integrityAutomationInterventionSnapshot = snapshotOf(current).toString()
            val vending = Constants.VENDING_PACKAGE_NAME
            val scope = current.scope.mapValues { (pkg, app) ->
                when {
                    pkg == vending -> app.copy(interventionEnabled = false)
                    app.interventionEnabled == false -> app.copy(interventionEnabled = true)
                    else -> app
                }
            }
            current.copy(
                defaults = current.defaults.copy(interventionEnabled = true),
                scope = scope + (vending to (scope[vending] ?: JsonConfig.AppConfig()).copy(interventionEnabled = false)),
            )
        }
    }

    /** Puts back what [enable] changed. Does nothing when [enable] did not change anything. */
    fun restore() {
        val text = PrefManager.integrityAutomationInterventionSnapshot
        if (text.isEmpty()) return
        PrefManager.integrityAutomationInterventionSnapshot = ""
        runCatching {
            val snapshot = JSONObject(text)
            val apps = snapshot.getJSONObject("apps")
            ConfigManager.update { current ->
                val scope = current.scope.toMutableMap()
                apps.keys().forEach { pkg ->
                    val value = if (apps.isNull(pkg)) null else apps.getBoolean(pkg)
                    val app = (scope[pkg] ?: JsonConfig.AppConfig()).copy(interventionEnabled = value)
                    if (app.isEmpty()) scope.remove(pkg) else scope[pkg] = app
                }
                current.copy(
                    defaults = current.defaults.copy(interventionEnabled = snapshot.getBoolean("defaults")),
                    scope = scope,
                )
            }
        }.onFailure {
            Log.e(TAG, "Could not restore the intervention settings", it)
        }
    }

    /** The interventions as they are now: the default, and each app's own value (null when it follows the default). */
    private fun snapshotOf(config: JsonConfig): JSONObject {
        val apps = JSONObject()
        for (pkg in config.scope.keys + Constants.VENDING_PACKAGE_NAME) {
            apps.put(pkg, config.scope[pkg]?.interventionEnabled ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("defaults", config.defaults.interventionEnabled)
            .put("apps", apps)
    }
}
