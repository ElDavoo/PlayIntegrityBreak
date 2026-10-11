package it.eldavo.pib.monitor

import com.topjohnwu.superuser.Shell
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.PrefManager
import java.io.File

/**
 * Replaces the keybox TrickyStore reads, through root. The root shell writes the module's files. The copies the app keeps
 * live in its own storage, so the app can read them, and the root shell only reads them from there. That storage is the
 * one Auto Backup skips, since a keybox has a private key.
 */
object KeyboxInstaller {
    private const val MODULE_DIR = "/data/adb/modules/tricky_store"

    /** The module's data directory. TEESimulator-RS, the module on the test device, reads its keybox from there. */
    private const val DATA_DIR = "/data/adb/tricky_store"

    private val nextFile = File(pibApp.noBackupFilesDir, "keybox-next.xml")
    private val originalFile = File(pibApp.noBackupFilesDir, "keybox-original.xml")
    private val noOriginalMarker = File(pibApp.noBackupFilesDir, "keybox-original.none")

    /** Asks for root, which may show the root manager's prompt. False when it is refused. Remembers the answer. */
    fun hasRoot(): Boolean {
        // After a refused or timed-out prompt, libsu keeps the non-root shell it fell back to, and would answer false until
        // the app restarts. It is dropped, so that root is asked for again.
        Shell.getCachedShell()?.takeUnless { it.isRoot }?.let { runCatching { it.close() } }
        val root = Shell.getShell().isRoot
        PrefManager.integrityRootSeen = root
        return root
    }


    fun moduleInstalled(): Boolean = Shell.cmd("test -d '$MODULE_DIR'").exec().isSuccess

    /**
     * The keybox the module reads: in its data directory when there is one, otherwise in the module directory. Checked
     * each time, since the module can be installed or removed while PIB runs.
     */
    private fun keyboxPath(): String =
        if (Shell.cmd("test -d '$DATA_DIR'").exec().isSuccess) "$DATA_DIR/keybox.xml" else "$MODULE_DIR/keybox.xml"

    /**
     * [installedId], read only where it cannot bring up a root prompt the user did not ask for: in a process whose root
     * shell is open already, or with the keybox action on (which uses root anyway) and root granted the last time it was
     * asked for. A new process has no root shell yet, so libsu cannot say, and that answer is kept in the preferences.
     * Null otherwise.
     */
    fun installedIdWithoutAsking(): String? {
        val shellOpen = Shell.getCachedShell()?.isRoot == true
        val allowed = shellOpen || (PrefManager.integrityAutomationKeybox && PrefManager.integrityRootSeen)
        return if (allowed) installedId() else null
    }

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
            replace(nextFile, path)
        } finally {
            nextFile.delete()
        }
    }

    /**
     * Puts back the keybox kept by [keepOriginal], or removes the keybox when the device had none. False when it failed,
     * or when nothing was kept, so there is nothing to put back.
     */
    fun restoreOriginal(): Boolean {
        val path = keyboxPath()
        val restored = when {
            originalFile.exists() -> replace(originalFile, path)
            noOriginalMarker.exists() -> Shell.cmd("rm -f '$path'").exec().isSuccess
            else -> return false
        }
        if (restored) forgetOriginal()
        return restored
    }

    /** Copies [source] over [path] in one step, so the module never reads half of it. */
    private fun replace(source: File, path: String): Boolean = Shell.cmd(
        "cp '${source.path}' '$path.pib' && chmod 644 '$path.pib' && mv -f '$path.pib' '$path'",
    ).exec().isSuccess

    /** Drops the kept copy, once the keybox that is installed is the one that works. */
    fun forgetOriginal() {
        originalFile.delete()
        noOriginalMarker.delete()
    }
}
