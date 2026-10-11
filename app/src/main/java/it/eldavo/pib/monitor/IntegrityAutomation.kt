package it.eldavo.pib.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.RequestAlert
import it.eldavo.pib.R

/**
 * Acts on the verdicts of the integrity monitor. Nothing happens until the device has passed STRONG once. After that, a
 * drop below STRONG turns on the actions the user chose, and a return to STRONG turns them off again.
 */
object IntegrityAutomation {
    private const val CHANNEL = "integrity_monitor"

    /** One ID per kind of alert, so that an alert replaces only the earlier one of its kind. */
    const val ALERT_DROP = 0x5049
    const val ALERT_RECOVER = 0x504A
    const val ALERT_KEYBOX = 0x504B

    /** Called after each check, once the monitor has released its lock. */
    fun onCheck(context: Context, previous: IntegrityCheck?, check: IntegrityCheck) {
        val level = check.level ?: return
        val before = PrefManager.integrityAutomationPhase
        val after = IntegrityAutomationPhase.next(before, strong = level == IntegrityCheck.Level.STRONG)
        if (after != before) PrefManager.integrityAutomationPhase = after
        // The first STRONG only arms the automation: there is nothing to act on before it.
        if (before == IntegrityAutomationPhase.UNARMED) return

        val previousLevel = previous?.level
        // Levels are declared from the strongest down.
        if (previousLevel != null && level.ordinal > previousLevel.ordinal && PrefManager.integrityAutomationNotifyDrop) {
            alert(
                context,
                ALERT_DROP,
                context.getString(R.string.monitor_notification_title),
                context.getString(R.string.monitor_notification_text, previousLevel.label, level.label),
                check.timestampMs,
            )
        }
        if (before == IntegrityAutomationPhase.DEGRADED && after == IntegrityAutomationPhase.PASSING &&
            PrefManager.integrityAutomationNotifyRecover
        ) {
            alert(
                context,
                ALERT_RECOVER,
                context.getString(R.string.automation_recover_title),
                context.getString(R.string.automation_recover_text, level.label),
                check.timestampMs,
            )
        }
        reconcile(context)
    }

    /**
     * Makes the actions match the settings: while the verdict is below STRONG, the chosen ones are on; otherwise they are
     * off. Also called when a setting changes, so that a change made while degraded takes effect at once.
     */
    fun reconcile(context: Context) {
        val phase = PrefManager.integrityAutomationPhase
        val degraded = phase == IntegrityAutomationPhase.DEGRADED
        // The errors come on while degraded if the user chose them outright, or if the keybox action has run out of keyboxes
        // and the user chose them for that case.
        val noKeybox = PrefManager.integrityAutomationKeybox && PrefManager.integrityKeyboxExhausted &&
            PrefManager.integrityAutomationSyntheticIfNoKeybox
        if (degraded && (PrefManager.integrityAutomationSyntheticErrors || noKeybox)) {
            AllAppsIntervention.enable()
        } else {
            AllAppsIntervention.restore()
        }
        if (phase == IntegrityAutomationPhase.PASSING) {
            // Back at STRONG, the keybox the device has is the one that works: the copy kept for a fallback is not needed.
            KeyboxInstaller.forgetOriginal()
            PrefManager.integrityKeyboxExhausted = false
        }
        if (degraded && PrefManager.integrityAutomationKeybox) KeyboxFallback.startIfDue(context)
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
