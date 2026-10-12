package it.eldavo.pib.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPlan
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.RequestAlert
import it.eldavo.pib.R

/**
 * Acts on the verdicts of the integrity monitor. Nothing happens until the device has passed STRONG once. After that, a
 * drop below STRONG turns on the actions the user chose, and a return to STRONG turns them off again. The decisions are in
 * common ([IntegrityAutomationPlan]) and are unit tested there; this object applies them.
 */
object IntegrityAutomation {
    private const val CHANNEL = "integrity_monitor"

    /** One ID per kind of alert, so that an alert replaces only the earlier one of its kind. */
    const val ALERT_DROP = 0x5049
    const val ALERT_RECOVER = 0x504A
    const val ALERT_KEYBOX = 0x504B

    /**
     * Called after each check, while the monitor still holds its lock. [keyboxRun] is a check of a keybox the fallback is
     * trying: unless it gives STRONG, the fallback puts the previous keybox back, so its level is not the device's.
     */
    fun onCheck(context: Context, check: IntegrityCheck, keyboxRun: Boolean = false) {
        val level = check.level ?: return
        val before = PrefManager.integrityAutomationPhase
        val after = IntegrityAutomationPhase.next(before, strong = level == IntegrityCheck.Level.STRONG)
        if (after != before) PrefManager.integrityAutomationPhase = after

        // A check made between the run's own checks (periodic, or "Check now") also sees the keybox on trial.
        val tried = (keyboxRun || KeyboxFallback.isRunning) && level != IntegrityCheck.Level.STRONG
        val previousLevel = PrefManager.integrityAutomationLastLevel
            ?.let { name -> IntegrityCheck.Level.entries.firstOrNull { it.name == name } }
        if (!tried) PrefManager.integrityAutomationLastLevel = level.name
        // Levels are declared from the strongest down.
        val dropped = !tried && previousLevel != null && level.ordinal > previousLevel.ordinal
        val alerts = IntegrityAutomationPlan.alerts(
            before = before,
            after = after,
            dropped = dropped,
            notifyDrop = PrefManager.integrityAutomationNotifyDrop,
            notifyRecover = PrefManager.integrityAutomationNotifyRecover,
        )
        for (item in alerts) {
            when (item) {
                IntegrityAutomationPlan.Alert.DROP -> previousLevel?.let { from ->
                    alert(
                        context,
                        ALERT_DROP,
                        context.getString(R.string.monitor_notification_title),
                        context.getString(R.string.monitor_notification_text, from.label, level.label),
                        check.timestampMs,
                    )
                }
                IntegrityAutomationPlan.Alert.RECOVER -> alert(
                    context,
                    ALERT_RECOVER,
                    context.getString(R.string.automation_recover_title),
                    context.getString(R.string.automation_recover_text, level.label),
                    check.timestampMs,
                )
            }
        }
        if (before != IntegrityAutomationPhase.UNARMED) reconcile(context)
    }

    /**
     * Makes the actions match the settings: while the verdict is below STRONG, the chosen ones are on; otherwise they are
     * off. Also called when a setting changes, so that a change made while degraded takes effect at once.
     */
    fun reconcile(context: Context) {
        val phase = PrefManager.integrityAutomationPhase
        val errorsOn = IntegrityAutomationPlan.syntheticErrorsOn(
            phase = phase,
            syntheticErrors = PrefManager.integrityAutomationSyntheticErrors,
            keybox = PrefManager.integrityAutomationKeybox,
            keyboxExhausted = PrefManager.integrityKeyboxExhausted,
            syntheticIfNoKeybox = PrefManager.integrityAutomationSyntheticIfNoKeybox,
        )
        if (errorsOn) AllAppsIntervention.enable() else AllAppsIntervention.restore()
        if (phase == IntegrityAutomationPhase.PASSING) {
            // Back at STRONG, the keybox the device has is the one that works: the copy kept for a fallback is not needed.
            // During a run it is kept: a later check can drop again, and the run then still has to put it back. The run
            // drops the copy itself when it ends at STRONG.
            if (!KeyboxFallback.isRunning) KeyboxInstaller.forgetOriginal()
            PrefManager.integrityKeyboxExhausted = false
            // A failed run is about the drop before: the next drop starts a run at once.
            PrefManager.integrityKeyboxLastFailedMs = 0L
        }
        if (phase == IntegrityAutomationPhase.DEGRADED && PrefManager.integrityAutomationKeybox) {
            KeyboxFallback.startIfDue(context)
        }
    }

    /** Posts an alert on the monitor's channel. */
    fun alert(context: Context, id: Int, title: String, text: String, timestampMs: Long = System.currentTimeMillis()) {
        RequestAlert.postNotification(
            channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            id = id,
            title = title,
            text = text,
            timestampMs = timestampMs,
        )
    }
}
