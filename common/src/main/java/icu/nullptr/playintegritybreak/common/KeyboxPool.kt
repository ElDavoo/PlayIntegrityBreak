package icu.nullptr.playintegritybreak.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The keyboxes published at [BASE_URL]. The directory is an nginx JSON index, and the keybox fallback reads the list
 * from it: only files with a keybox name are kept, and directories such as revoked/ are skipped.
 */
object KeyboxPool {
    const val BASE_URL = "https://www.davidepalma.it/pib/"

    /** The keybox the scavenger picked as the best one. It is tried first. */
    const val BEST_NAME = "keybox.xml"

    private val contentNamePattern = Regex("[0-9a-f]{32}\\.xml")

    /**
     * The file names to try: [BEST_NAME] first, then the others newest first. Only names of this form are returned, so a
     * name is never a path. Null when [json] is not an index at all, such as an error page.
     */
    fun parseListing(json: String): List<String>? {
        val entries = runCatching { Json.parseToJsonElement(json) as JsonArray }.getOrNull() ?: return null
        val files = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            if (entry.string("type") != "file") return@mapNotNull null
            if (name != BEST_NAME && !contentNamePattern.matches(name)) return@mapNotNull null
            name to modifiedMillis(entry.string("mtime"))
        }
        val best = files.filter { it.first == BEST_NAME }.map { it.first }
        val others = files.filter { it.first != BEST_NAME }.sortedByDescending { it.second }.map { it.first }
        return (best + others).distinct()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The index gives the time as an RFC 1123 date, such as "Sat, 03 Oct 2026 20:14:12 GMT". */
    private fun modifiedMillis(text: String?): Long = runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}
