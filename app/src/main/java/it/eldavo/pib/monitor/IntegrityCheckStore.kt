package it.eldavo.pib.monitor

import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import icu.nullptr.playintegritybreak.pibApp
import org.json.JSONArray
import java.io.File

/** History of the integrity monitor checks, newest first. */
object IntegrityCheckStore {
    private const val FILE_NAME = "integrity_monitor_history.json"
    private const val MAX_ENTRIES = 200

    private val file by lazy { AtomicFile(File(pibApp.filesDir, FILE_NAME)) }

    @Synchronized
    fun all(): List<IntegrityCheck> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(IntegrityCheck::fromJson)
        }
    }

    /** The newest check that produced a device verdict. */
    fun lastVerdict(): IntegrityCheck? = all().firstOrNull { it.level != null }

    @Synchronized
    fun add(check: IntegrityCheck) {
        val entries = (listOf(check) + all()).take(MAX_ENTRIES)
        file.writeText(JSONArray(entries.map { it.toJson() }).toString())
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
