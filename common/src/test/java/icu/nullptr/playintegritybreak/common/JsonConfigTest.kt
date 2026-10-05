package icu.nullptr.playintegritybreak.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonConfigTest {

    /** What the hook acts on. Rewrite details only matter while rewriting, and nothing matters while disabled. */
    private data class Behaviour(
        val enabled: Boolean,
        val rewrite: Boolean,
        val errorCode: Int?,
        val remediable: Boolean?,
        val deliver: Boolean?,
        val delay: Boolean?,
        val toast: Boolean,
    )

    private fun behaviour(enabled: Boolean, rewrite: Boolean, code: Int, remediable: Boolean, deliver: Boolean, delay: Boolean, toast: Boolean): Behaviour {
        val rewriting = enabled && rewrite
        return Behaviour(
            enabled = enabled,
            rewrite = rewriting,
            errorCode = code.takeIf { rewriting },
            remediable = remediable.takeIf { rewriting },
            deliver = deliver.takeIf { rewriting },
            delay = (delay && deliver).takeIf { rewriting },
            toast = toast,
        )
    }

    private data class LegacyDefaults(val intervention: Boolean, val rewrite: Boolean, val deliver: Boolean, val toast: Boolean)

    private data class LegacyApp(
        val intervention: Boolean,
        val overridden: Boolean,
        val rewrite: Boolean,
        val deliver: Boolean,
        val delay: Boolean,
        val toast: Boolean,
    )

    private val defaultCode = -3
    private val appCode = -12

    private fun legacyJson(d: LegacyDefaults, app: LegacyApp?, loggerMigrated: Boolean = true): String {
        val scope = app?.let {
            """"com.example": {
                "interventionEnabled": ${it.intervention},
                "integrityLoggerEnabled": true,
                "rewriteIntegrityResponseOverridden": ${it.overridden},
                "rewriteIntegrityResponse": ${it.rewrite},
                "rewriteIntegrityErrorCode": $appCode,
                "rewriteIntegrityErrorRemediable": false,
                "deliverSyntheticResponse": ${it.deliver},
                "delaySyntheticResponseDelivery": ${it.delay},
                "integrityRequestToast": ${it.toast}
            }"""
        }.orEmpty()
        return """{
            "configVersion": 93,
            "integrityModeMigrated": $loggerMigrated,
            "integrityRequestToast": ${d.toast},
            "defaultInterventionEnabled": ${d.intervention},
            "defaultHookRewriteEnabled": ${d.rewrite},
            "defaultHookRewriteErrorCode": $defaultCode,
            "defaultHookRewriteRemediable": true,
            "defaultDeliverSyntheticResponse": ${d.deliver},
            "defaultDelaySyntheticResponseDelivery": false,
            "forceMountData": true,
            "telemetryBatchSize": 10,
            "userId": "abc",
            "scope": { $scope }
        }"""
    }

    /** Copy of PIBLoggerService.resolvePolicy and showRequestToast before config version 94. */
    private fun legacyBehaviour(d: LegacyDefaults, app: LegacyApp?): Behaviour {
        val defaultRewrite = d.intervention && d.rewrite
        val toast = d.toast && app?.toast != false
        return when {
            app == null -> behaviour(d.intervention, defaultRewrite, defaultCode, true, d.deliver, false, toast)
            !app.intervention -> behaviour(false, false, defaultCode, true, app.deliver, app.delay, toast)
            !app.overridden -> behaviour(d.intervention, defaultRewrite, defaultCode, true, app.deliver, app.delay, toast)
            else -> behaviour(true, app.rewrite, appCode, false, app.deliver, app.delay, toast)
        }
    }

    private fun JsonConfig.Policy.behaviour() =
        behaviour(interventionEnabled, rewriteResponse, rewriteErrorCode, rewriteRemediable, deliverSyntheticResponse, delaySyntheticResponse, requestToast)

    private val bools = listOf(true, false)

    @Test
    fun `migration keeps what the hook did for every legacy combination`() {
        var checked = 0
        for (di in bools) for (dr in bools) for (dd in bools) for (dt in bools) {
            val defaults = LegacyDefaults(di, dr, dd, dt)
            val apps = listOf<LegacyApp?>(null) + bools.flatMap { i ->
                bools.flatMap { o ->
                    bools.flatMap { r ->
                        bools.flatMap { dl -> bools.flatMap { dy -> bools.map { t -> LegacyApp(i, o, r, dl, dy, t) } } }
                    }
                }
            }
            for (app in apps) {
                val migrated = JsonConfig.parse(legacyJson(defaults, app))
                assertEquals("defaults=$defaults app=$app", legacyBehaviour(defaults, app), migrated.policyFor("com.example").behaviour())
                checked++
            }
        }
        assertEquals(16 * 65, checked)
    }

    @Test
    fun `migration keeps top level settings and drops removed ones`() {
        val migrated = JsonConfig.parse(legacyJson(LegacyDefaults(true, true, true, true), null))
        assertEquals(93, migrated.configVersion)
        assertEquals("abc", migrated.userId)
        assertTrue("forceMountData" !in migrated.toString())
        assertTrue("telemetryBatchSize" !in migrated.toString())
    }

    @Test
    fun `pre logger configs reset apps to follow the defaults`() {
        val defaults = LegacyDefaults(intervention = false, rewrite = true, deliver = true, toast = true)
        val app = LegacyApp(intervention = true, overridden = true, rewrite = true, deliver = false, delay = true, toast = true)
        val migrated = JsonConfig.parse(legacyJson(defaults, app, loggerMigrated = false))
        assertEquals(JsonConfig.AppConfig(), migrated.scope["com.example"])
        assertEquals(migrated.defaults, migrated.policyFor("com.example"))
    }

    @Test
    fun `missing defaultInterventionEnabled follows defaultHookRewriteEnabled`() {
        val migrated = JsonConfig.parse("""{"configVersion": 80, "defaultHookRewriteEnabled": false}""")
        assertEquals(false, migrated.defaults.interventionEnabled)
    }

    @Test
    fun `current configs round trip`() {
        val config = JsonConfig(
            defaults = JsonConfig.Policy(rewriteErrorCode = -1),
            scope = mapOf("a.b" to JsonConfig.AppConfig(interventionEnabled = false)),
        )
        assertEquals(config, JsonConfig.parse(config.toString()))
    }

    @Test
    fun `unset overrides follow the defaults`() {
        val config = JsonConfig(
            defaults = JsonConfig.Policy(rewriteErrorCode = -1, requestToast = false),
            scope = mapOf("a.b" to JsonConfig.AppConfig(rewriteErrorCode = -7)),
        )
        val policy = config.policyFor("a.b")
        assertEquals(-7, policy.rewriteErrorCode)
        assertEquals(false, policy.requestToast)
        assertEquals(config.defaults, config.policyFor("unknown"))
    }

    @Test
    fun `policy keys read and write every field`() {
        for (key in PolicyKey.entries) {
            val value: Any = if (key.isInt) 42 else false
            assertEquals(key, PolicyKey.fromKey(key.key))
            assertEquals(value, key.get(key.with(JsonConfig.Policy(), value)))
            val app = key.with(JsonConfig.AppConfig(), value)
            assertEquals(value, key.get(app.applyTo(JsonConfig.Policy())))
            assertTrue(key.with(app, null).isEmpty())
        }
        assertNull(PolicyKey.fromKey("nope"))
    }
}
