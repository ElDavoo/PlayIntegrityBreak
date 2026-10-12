package icu.nullptr.playintegritybreak.common

/**
 * Where the integrity automation stands. It only leaves [UNARMED] on a STRONG verdict, so a device that never passed
 * STRONG has nothing to fall back from. A check that gave no verdict changes nothing.
 */
enum class IntegrityAutomationPhase {
    /** No STRONG verdict seen yet: the automation does nothing. */
    UNARMED,

    /** The device passed STRONG, and the automation waits for the verdict to drop. */
    PASSING,

    /** The verdict dropped below STRONG: the drop actions are on, until it is back to STRONG. */
    DEGRADED;

    companion object {
        /** The phase after a check that gave [strong] (null when it gave no verdict). */
        fun next(phase: IntegrityAutomationPhase, strong: Boolean?): IntegrityAutomationPhase = when {
            strong == null -> phase
            phase == UNARMED -> if (strong) PASSING else UNARMED
            strong -> PASSING
            else -> DEGRADED
        }
    }
}
