package icu.nullptr.playintegritybreak.common

/**
 * The decisions the integrity automation makes after a check. They are free of Android, so the unit tests cover them. The
 * app applies them: it posts the alerts, turns the errors for all apps on or off, and starts the keybox run.
 */
object IntegrityAutomationPlan {
    /** An alert the automation posts. */
    enum class Alert { DROP, RECOVER }

    /**
     * The alerts for a check, given the phases before and after it. Nothing is posted before the automation is armed.
     * [dropped] is whether the verdict fell below the one before it.
     */
    fun alerts(
        before: IntegrityAutomationPhase,
        after: IntegrityAutomationPhase,
        dropped: Boolean,
        notifyDrop: Boolean,
        notifyRecover: Boolean,
    ): List<Alert> {
        if (before == IntegrityAutomationPhase.UNARMED) return emptyList()
        return buildList {
            if (dropped && notifyDrop) add(Alert.DROP)
            if (before == IntegrityAutomationPhase.DEGRADED && after == IntegrityAutomationPhase.PASSING && notifyRecover) {
                add(Alert.RECOVER)
            }
        }
    }

    /**
     * Whether the errors for all apps are on. While degraded they are on when the user chose them outright, or when the
     * keybox run has given up (see [keyboxExhausted]) and the user chose them for that case.
     */
    fun syntheticErrorsOn(
        phase: IntegrityAutomationPhase,
        syntheticErrors: Boolean,
        keybox: Boolean,
        keyboxExhausted: Boolean,
        syntheticIfNoKeybox: Boolean,
    ): Boolean {
        if (phase != IntegrityAutomationPhase.DEGRADED) return false
        return syntheticErrors || (keybox && keyboxExhausted && syntheticIfNoKeybox)
    }

    /**
     * Whether a keybox run should start now: degraded, the keybox action on, no run going, and the last run that failed
     * is at least [retryAfterMs] old. A failure in the future means the clock was set back, and does not hold a run back.
     */
    fun keyboxRunDue(
        phase: IntegrityAutomationPhase,
        keybox: Boolean,
        running: Boolean,
        nowMs: Long,
        lastFailedMs: Long,
        retryAfterMs: Long,
    ): Boolean =
        phase == IntegrityAutomationPhase.DEGRADED && keybox && !running && nowMs - lastFailedMs !in 0 until retryAfterMs
}
