package icu.nullptr.playintegritybreak.common

import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.DEGRADED
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.PASSING
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase.UNARMED
import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrityAutomationPhaseTest {

    @Test
    fun staysUnarmedUntilTheFirstStrongVerdict() {
        assertEquals(UNARMED, IntegrityAutomationPhase.next(UNARMED, strong = false))
        assertEquals(PASSING, IntegrityAutomationPhase.next(UNARMED, strong = true))
    }

    @Test
    fun dropsBelowStrongAndRecovers() {
        assertEquals(DEGRADED, IntegrityAutomationPhase.next(PASSING, strong = false))
        assertEquals(DEGRADED, IntegrityAutomationPhase.next(DEGRADED, strong = false))
        assertEquals(PASSING, IntegrityAutomationPhase.next(DEGRADED, strong = true))
    }

    @Test
    fun aCheckWithoutAVerdictChangesNothing() {
        for (phase in IntegrityAutomationPhase.entries) {
            assertEquals(phase, IntegrityAutomationPhase.next(phase, strong = null))
        }
    }
}
