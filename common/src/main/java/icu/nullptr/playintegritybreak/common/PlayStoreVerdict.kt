package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Verdict of the Play Store's own integrity check, as PIB reads it. */
object PlayStoreVerdict {

    /** Labels from the Play Store's verdict list, "[A, B]", in order. Empty for "[]" or an empty text. */
    fun parseLabels(text: String): List<String> {
        val body = text.trim().removePrefix("[").removeSuffix("]")
        return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The verdict JSON the monitor reads the device levels from: {"deviceIntegrity": {"deviceRecognitionVerdict": [...]}}.
     * Only device labels come from the Play Store check.
     */
    fun toVerdictJson(labels: List<String>): String = buildJsonObject {
        putJsonObject("deviceIntegrity") {
            putJsonArray("deviceRecognitionVerdict") {
                labels.forEach { add(JsonPrimitive(it)) }
            }
        }
    }.toString()
}
