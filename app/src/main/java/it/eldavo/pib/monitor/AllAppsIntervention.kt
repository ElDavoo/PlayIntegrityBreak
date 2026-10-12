package it.eldavo.pib.monitor

import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.InterventionSnapshot
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager

/**
 * Turns intervention and synthetic errors on for every app but the Play Store, which the monitor needs to get its verdict.
 * The change and its undo are in common ([InterventionSnapshot]), where they are unit tested. This object keeps the
 * snapshot and applies it. Its functions are synchronized, so that the snapshot and the config change together.
 */
object AllAppsIntervention {
    private const val TAG = "AllAppsIntervention"
    private const val VENDING = Constants.VENDING_PACKAGE_NAME

    /** Turns the errors on for all apps. Does nothing when they are already on from here. */
    @Synchronized
    fun enable() {
        if (PrefManager.integrityAutomationInterventionSnapshot.isNotEmpty()) return
        ConfigManager.update { current ->
            // Kept before the config is saved, so that a forced config always has its snapshot.
            PrefManager.integrityAutomationInterventionSnapshot = InterventionSnapshot.of(current, VENDING).encode()
            InterventionSnapshot.enableForAllApps(current, VENDING)
        }
    }

    /** Puts back what [enable] changed. Does nothing when [enable] did not change anything. */
    @Synchronized
    fun restore() {
        val text = PrefManager.integrityAutomationInterventionSnapshot
        if (text.isEmpty()) return
        val snapshot = InterventionSnapshot.decode(text)
        if (snapshot == null) {
            Log.e(TAG, "Could not read the intervention snapshot, so the settings were left as they are")
        } else {
            ConfigManager.update { current -> InterventionSnapshot.restore(current, snapshot, VENDING) }
        }
        // Dropped once the config is saved: restoring a second time changes nothing more.
        PrefManager.integrityAutomationInterventionSnapshot = ""
    }

    /**
     * Runs [replace], which replaces the whole config (an import), then forces the errors again on top of it while they
     * are on, with a snapshot of the new config: the old snapshot describes a config that is gone. Both happen under this
     * object's lock, so a [restore] cannot apply the old snapshot to the new config in between.
     */
    @Synchronized
    fun replaceConfig(replace: () -> Unit) {
        val forced = PrefManager.integrityAutomationInterventionSnapshot.isNotEmpty()
        replace()
        if (!forced) return
        PrefManager.integrityAutomationInterventionSnapshot = ""
        enable()
    }

    /** [config] without what [enable] forced, for a backup: it would be restored as the user's own settings. */
    fun withoutForced(config: JsonConfig): JsonConfig {
        val snapshot = InterventionSnapshot.decode(PrefManager.integrityAutomationInterventionSnapshot) ?: return config
        return InterventionSnapshot.restore(config, snapshot, VENDING)
    }
}
