package icu.nullptr.playintegritybreak.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionSnapshotTest {

    private val vending = Constants.VENDING_PACKAGE_NAME

    private val withOverrides = JsonConfig(
        defaults = JsonConfig.Policy(interventionEnabled = false),
        scope = mapOf(
            vending to JsonConfig.AppConfig(rewriteResponse = false),
            "a.off" to JsonConfig.AppConfig(interventionEnabled = false, rewriteErrorCode = -3),
            "b.on" to JsonConfig.AppConfig(interventionEnabled = true),
            "c.default" to JsonConfig.AppConfig(rewriteErrorCode = -5),
            "d.logged" to JsonConfig.AppConfig(rewriteResponse = false),
        ),
    )

    @Test
    fun enableTurnsInterventionOnForEveryAppButThePlayStore() {
        val enabled = InterventionSnapshot.enableForAllApps(withOverrides, vending)

        assertTrue(enabled.defaults.interventionEnabled)
        assertFalse(enabled.policyFor(vending).interventionEnabled)
        assertFalse(enabled.policyFor(vending).rewriteResponse)
        assertTrue(enabled.policyFor("a.off").interventionEnabled)
        assertTrue(enabled.policyFor("b.on").interventionEnabled)
        assertTrue(enabled.policyFor("c.default").interventionEnabled)
        assertTrue(enabled.policyFor("never.seen").interventionEnabled)
    }

    @Test
    fun enableTurnsSyntheticErrorsOnForEveryAppButThePlayStore() {
        val enabled = InterventionSnapshot.enableForAllApps(withOverrides.copy(defaults = JsonConfig.Policy(rewriteResponse = false)), vending)

        assertTrue(enabled.defaults.rewriteResponse)
        assertTrue(enabled.policyFor("d.logged").rewriteResponse)
        assertTrue(enabled.policyFor("never.seen").rewriteResponse)
        assertFalse(enabled.policyFor(vending).rewriteResponse)
    }

    @Test
    fun enableKeepsTheOtherSettingsOfEachApp() {
        val enabled = InterventionSnapshot.enableForAllApps(withOverrides, vending)

        assertEquals(JsonConfig.AppConfig(interventionEnabled = true, rewriteErrorCode = -3), enabled.scope["a.off"])
        assertEquals(JsonConfig.AppConfig(rewriteErrorCode = -5), enabled.scope["c.default"])
        assertEquals(JsonConfig.AppConfig(rewriteResponse = false, interventionEnabled = false), enabled.scope[vending])
    }

    @Test
    fun enableAddsThePlayStoreOverrideWhenItIsMissing() {
        val enabled = InterventionSnapshot.enableForAllApps(JsonConfig(scope = emptyMap()), vending)

        assertEquals(JsonConfig.AppConfig(interventionEnabled = false), enabled.scope[vending])
    }

    @Test
    fun restoreGivesBackTheConfigAsItWas() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)
        val restored = InterventionSnapshot.restore(InterventionSnapshot.enableForAllApps(withOverrides, vending), snapshot, vending)

        assertEquals(withOverrides, restored)
    }

    @Test
    fun restoreRemovesThePlayStoreOverrideThatWasAdded() {
        val config = JsonConfig(scope = emptyMap())
        val snapshot = InterventionSnapshot.of(config, vending)
        val restored = InterventionSnapshot.restore(InterventionSnapshot.enableForAllApps(config, vending), snapshot, vending)

        assertEquals(config, restored)
        assertFalse(restored.scope.containsKey(vending))
    }

    @Test
    fun restoreKeepsSettingsChangedAfterwardsForAppsOutsideTheSnapshot() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)
        val changed = withOverrides.copy(scope = withOverrides.scope + ("later.app" to JsonConfig.AppConfig(rewriteErrorCode = -7)))
        val restored = InterventionSnapshot.restore(changed, snapshot, vending)

        assertEquals(JsonConfig.AppConfig(rewriteErrorCode = -7), restored.scope["later.app"])
    }

    @Test
    fun restoreKeepsWhatTheUserChangedWhileForced() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)
        val enabled = InterventionSnapshot.enableForAllApps(withOverrides, vending)
        val edited = enabled.copy(
            defaults = enabled.defaults.copy(rewriteResponse = false),
            scope = enabled.scope + ("a.off" to enabled.scope.getValue("a.off").copy(interventionEnabled = null)),
        )
        val restored = InterventionSnapshot.restore(edited, snapshot, vending)

        assertFalse(restored.defaults.rewriteResponse)
        assertNull(restored.scope.getValue("a.off").interventionEnabled)
        // What the user did not touch goes back.
        assertFalse(restored.defaults.interventionEnabled)
        assertEquals(false, restored.scope.getValue("d.logged").rewriteResponse)
    }

    @Test
    fun restoreDoesNotBringBackAnAppRemovedWhileForced() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)
        val enabled = InterventionSnapshot.enableForAllApps(withOverrides, vending)
        val restored = InterventionSnapshot.restore(enabled.copy(scope = enabled.scope - "a.off"), snapshot, vending)

        assertFalse(restored.scope.containsKey("a.off"))
    }

    @Test
    fun restoreTwiceChangesNothingMore() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)
        val once = InterventionSnapshot.restore(InterventionSnapshot.enableForAllApps(withOverrides, vending), snapshot, vending)

        assertEquals(once, InterventionSnapshot.restore(once, snapshot, vending))
    }

    @Test
    fun anOlderSnapshotLeavesSyntheticErrorsAlone() {
        val old = InterventionSnapshot.decode("""{"defaults":false,"apps":{"a.off":false}}""")!!
        val config = JsonConfig(
            defaults = JsonConfig.Policy(interventionEnabled = true, rewriteResponse = true),
            scope = mapOf("a.off" to JsonConfig.AppConfig(interventionEnabled = true, rewriteResponse = true)),
        )
        val restored = InterventionSnapshot.restore(config, old, vending)

        assertFalse(restored.defaults.interventionEnabled)
        assertTrue(restored.defaults.rewriteResponse)
        assertEquals(JsonConfig.AppConfig(interventionEnabled = false, rewriteResponse = true), restored.scope["a.off"])
    }

    @Test
    fun snapshotRecordsEachAppsOwnValueAndTheDefault() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)

        assertFalse(snapshot.defaults)
        assertEquals(false, snapshot.apps["a.off"])
        assertEquals(true, snapshot.apps["b.on"])
        assertNull(snapshot.apps["c.default"])
        assertTrue(snapshot.apps.containsKey(vending))
        assertNull(snapshot.apps[vending])
    }

    @Test
    fun snapshotSurvivesEncodingAndRejectsGarbage() {
        val snapshot = InterventionSnapshot.of(withOverrides, vending)

        assertEquals(snapshot, InterventionSnapshot.decode(snapshot.encode()))
        assertNull(InterventionSnapshot.decode("not json"))
    }
}
