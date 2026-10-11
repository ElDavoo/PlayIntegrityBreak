package it.eldavo.pib.monitor

import com.topjohnwu.superuser.Shell
import icu.nullptr.playintegritybreak.pibApp
import java.io.File

/**
 * Replaces the keybox TrickyStore reads, through root. The root shell writes the module's files. The copies the app keeps
 * live in its own storage, so the app can read them, and the root shell only reads them from there.
 */
object KeyboxInstaller {
    private const val MODULE_DIR = "/data/adb/modules/tricky_store"

    /** The module's data directory. TEESimulator-RS, the module on the test device, reads its keybox from there. */
    private const val DATA_DIR = "/data/adb/tricky_store"

    private val nextFile = File(pibApp.filesDir, "keybox-next.xml")
    private val originalFile = File(pibApp.filesDir, "keybox-original.xml")
    private val noOriginalMarker = File(pibApp.filesDir, "keybox-original.none")

    /** Asks for root, which may show the root manager's prompt. False when it is refused. */
    fun hasRoot(): Boolean = Shell.getShell().isRoot

    /** Whether root was already granted. Never asks. */
    fun rootGranted(): Boolean = Shell.isAppGrantedRoot() == true

    fun moduleInstalled(): Boolean = Shell.cmd("test -d '$MODULE_DIR'").exec().isSuccess

    /**
     * The keybox the module reads: in its data directory when there is one, otherwise in the module directory. Checked
     * each time, since the module can be installed or removed while PIB runs.
     */
    private fun keyboxPath(): String =
        if (Shell.cmd("test -d '$DATA_DIR'").exec().isSuccess) "$DATA_DIR/keybox.xml" else "$MODULE_DIR/keybox.xml"

    /** The ID of the keybox the module reads now, or null when there is none. Needs root. */
    fun installedId(): String? {
        val result = Shell.cmd("md5sum '${keyboxPath()}'").exec()
        if (!result.isSuccess) return null
        return result.out.firstOrNull()?.substringBefore(' ')?.takeIf { it.length == 32 }
    }

    /**
     * Keeps a copy of the keybox the device has, for when none of the published ones passes. The copy is kept once: a
     * second call does not overwrite it. Returns false when it could not be kept.
     */
    fun keepOriginal(): Boolean {
        if (originalFile.exists() || noOriginalMarker.exists()) return true
        val path = keyboxPath()
        if (!Shell.cmd("test -f '$path'").exec().isSuccess) return noOriginalMarker.createNewFile()
        val uid = pibApp.applicationInfo.uid
        val copied = Shell.cmd(
            "cp '$path' '${originalFile.path}' && chown $uid:$uid '${originalFile.path}'",
        ).exec().isSuccess
        if (!copied) originalFile.delete()
        return copied
    }

    /**
     * Writes [bytes] as the keybox. The file is replaced in one step, so the module never reads half of it, and the module
     * picks up the new keybox without a restart.
     */
    fun install(bytes: ByteArray): Boolean {
        val path = keyboxPath()
        nextFile.writeBytes(bytes)
        return try {
            Shell.cmd(
                "cp '${nextFile.path}' '$path.pib' && chmod 644 '$path.pib' && mv -f '$path.pib' '$path'",
            ).exec().isSuccess
        } finally {
            nextFile.delete()
        }
    }

    /** Puts back the keybox kept by [keepOriginal], or removes the keybox when the device had none. */
    fun restoreOriginal(): Boolean {
        val path = keyboxPath()
        val restored = when {
            originalFile.exists() -> Shell.cmd(
                "cp '${originalFile.path}' '$path.pib' && chmod 644 '$path.pib' && mv -f '$path.pib' '$path'",
            ).exec().isSuccess
            noOriginalMarker.exists() -> Shell.cmd("rm -f '$path'").exec().isSuccess
            else -> return true
        }
        if (restored) forgetOriginal()
        return restored
    }

    /** Drops the kept copy, once the keybox that is installed is the one that works. */
    fun forgetOriginal() {
        originalFile.delete()
        noOriginalMarker.delete()
    }
}
