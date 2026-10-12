package icu.nullptr.playintegritybreak.common

import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.DEGRADED
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.PASSING
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.UNARMED
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPlan.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityAutomationPlanTest {

    @Test
    fun noAlertIsPostedBeforeArming() {
        assertEquals(emptyList<Alert>(), IntegrityAutomationPlan.alerts(UNARMED, UNARMED, dropped = true, notifyDrop = true, notifyRecover = true))
        assertEquals(emptyList<Alert>(), IntegrityAutomationPlan.alerts(UNARMED, PASSING, dropped = false, notifyDrop = true, notifyRecover = true))
    }

    @Test
    fun aDropAlertsOnEveryDropOnceArmed() {
        assertEquals(listOf(Alert.DROP), IntegrityAutomationPlan.alerts(PASSING, DEGRADED, dropped = true, notifyDrop = true, notifyRecover = true))
        assertEquals(listOf(Alert.DROP), IntegrityAutomationPlan.alerts(DEGRADED, DEGRADED, dropped = true, notifyDrop = true, notifyRecover = true))
    }

    @Test
    fun aDropCanBeSilenced() {
        assertEquals(emptyList<Alert>(), IntegrityAutomationPlan.alerts(PASSING, DEGRADED, dropped = true, notifyDrop = false, notifyRecover = true))
    }

    @Test
    fun aRecoveryAlertsOnlyWhenLeavingDegraded() {
        assertEquals(listOf(Alert.RECOVER), IntegrityAutomationPlan.alerts(DEGRADED, PASSING, dropped = false, notifyDrop = true, notifyRecover = true))
        assertEquals(emptyList<Alert>(), IntegrityAutomationPlan.alerts(PASSING, PASSING, dropped = false, notifyDrop = true, notifyRecover = true))
        assertEquals(emptyList<Alert>(), IntegrityAutomationPlan.alerts(DEGRADED, PASSING, dropped = false, notifyDrop = true, notifyRecover = false))
    }

    @Test
    fun syntheticErrorsNeverOnOutsideDegraded() {
        assertFalse(IntegrityAutomationPlan.syntheticErrorsOn(PASSING, syntheticErrors = true, keybox = true, keyboxExhausted = true, syntheticIfNoKeybox = true))
        assertFalse(IntegrityAutomationPlan.syntheticErrorsOn(UNARMED, syntheticErrors = true, keybox = false, keyboxExhausted = false, syntheticIfNoKeybox = false))
    }

    @Test
    fun syntheticErrorsOnWhenChosenOutright() {
        assertTrue(IntegrityAutomationPlan.syntheticErrorsOn(DEGRADED, syntheticErrors = true, keybox = false, keyboxExhausted = false, syntheticIfNoKeybox = false))
    }

    @Test
    fun syntheticErrorsOnlyAfterTheKeyboxRunGivesUp() {
        // Chosen for the no-keybox case, and the run has given up: on.
        assertTrue(IntegrityAutomationPlan.syntheticErrorsOn(DEGRADED, syntheticErrors = false, keybox = true, keyboxExhausted = true, syntheticIfNoKeybox = true))
        // The run has not given up yet: off.
        assertFalse(IntegrityAutomationPlan.syntheticErrorsOn(DEGRADED, syntheticErrors = false, keybox = true, keyboxExhausted = false, syntheticIfNoKeybox = true))
        // The keybox action is off: there is no run to give up, so off.
        assertFalse(IntegrityAutomationPlan.syntheticErrorsOn(DEGRADED, syntheticErrors = false, keybox = false, keyboxExhausted = true, syntheticIfNoKeybox = true))
        // Not chosen for that case: off.
        assertFalse(IntegrityAutomationPlan.syntheticErrorsOn(DEGRADED, syntheticErrors = false, keybox = true, keyboxExhausted = true, syntheticIfNoKeybox = false))
    }

    @Test
    fun aKeyboxRunStartsWhenDueAndOnlyThen() {
        val retry = 6 * 3_600_000L
        val now = 10 * retry
        fun due(
            phase: IntegrityAutomationPhase = DEGRADED,
            keybox: Boolean = true,
            running: Boolean = false,
            lastFailed: Long = now - retry,
        ) = IntegrityAutomationPlan.keyboxRunDue(phase, keybox, running, now, lastFailed, retry)

        assertTrue(due())
        assertFalse(due(phase = PASSING))
        assertFalse(due(keybox = false))
        assertFalse(due(running = true))
        assertFalse(due(lastFailed = now - retry + 1))
        assertFalse(due(lastFailed = now))
    }

    @Test
    fun aFailureDatedInTheFutureDoesNotHoldARunBack() {
        val retry = 6 * 3_600_000L
        val now = 10 * retry
        assertTrue(IntegrityAutomationPlan.keyboxRunDue(DEGRADED, keybox = true, running = false, nowMs = now, lastFailedMs = now + 1, retryAfterMs = retry))
    }

    @Test
    fun aFirstRunIsDueAtOnce() {
        assertTrue(IntegrityAutomationPlan.keyboxRunDue(DEGRADED, keybox = true, running = false, nowMs = 1_800_000_000_000L, lastFailedMs = 0L, retryAfterMs = 6 * 3_600_000L))
    }
}
