package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStoreVerdictTest {

    @Test
    fun parsesTheVerdictList() {
        assertEquals(
            listOf("MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"),
            PlayStoreVerdict.parseLabels("[MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY, MEETS_STRONG_INTEGRITY]"),
        )
    }

    @Test
    fun emptyLabelListGivesNoVerdicts() {
        assertEquals(emptyList<String>(), PlayStoreVerdict.parseLabels("[]"))
        assertEquals(emptyList<String>(), PlayStoreVerdict.parseLabels(""))
    }

    @Test
    fun verdictJsonHasTheDeviceVerdictShape() {
        val json = Json.parseToJsonElement(PlayStoreVerdict.toVerdictJson(listOf("MEETS_BASIC_INTEGRITY")))
        val verdicts = json.jsonObject["deviceIntegrity"]!!.jsonObject["deviceRecognitionVerdict"]!!.jsonArray
        assertEquals(listOf("MEETS_BASIC_INTEGRITY"), verdicts.map { it.jsonPrimitive.content })
    }
}
