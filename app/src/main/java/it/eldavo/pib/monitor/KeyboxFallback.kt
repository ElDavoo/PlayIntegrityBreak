package it.eldavo.pib.monitor

import android.content.Context
import android.util.Log
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase
import icu.nullptr.playintegritybreak.common.KeyboxPool
import icu.nullptr.playintegritybreak.common.KeyboxXml
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.PrefManager
import it.eldavo.pib.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The keybox fallback. While the verdict is below STRONG, it installs the published keyboxes one by one, and checks after
 * each one with the Play Store, until one gives STRONG. When none does, the keybox the device had is put back.
 */
object KeyboxFallback {
    private const val TAG = "KeyboxFallback"

    /** How many published keyboxes one run tries. Each try is a check against Google, so the number is kept low. */
    private const val MAX_ATTEMPTS = 20

    /** After a run that did not reach STRONG, the next one waits this long. A keybox published later is tried then. */
    private const val RETRY_AFTER_MS = 6 * 3_600_000L

    private const val TIMEOUT_MS = 15_000
    private const val MAX_LIST_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    /**
     * Starts a run, unless one is going or the last failed one was too recent. A run that reaches STRONG is not a failure,
     * so a drop after it starts a run at once.
     */
    @Synchronized
    fun startIfDue(context: Context) {
        if (running) return
        if (System.currentTimeMillis() - PrefManager.integrityKeyboxLastFailedMs < RETRY_AFTER_MS) return
        running = true
        scope.launch {
            val reachedStrong = try {
                run(context)
            } catch (e: Exception) {
                Log.e(TAG, "Keybox fallback failed", e)
                false
            } finally {
                running = false
            }
            if (reachedStrong) {
                PrefManager.integrityKeyboxExhausted = false
            } else {
                // No keybox gives STRONG, or none could be tried (no root): the errors for all apps may come on now.
                PrefManager.integrityKeyboxLastFailedMs = System.currentTimeMillis()
                PrefManager.integrityKeyboxExhausted = true
                IntegrityAutomation.reconcile(context)
            }
        }
    }

    /** Returns true when a keybox gave STRONG, or the verdict was STRONG by the time the run ended. */
    private suspend fun run(context: Context): Boolean {
        if (!KeyboxInstaller.hasRoot()) return fail(context, R.string.automation_keybox_no_root)
        if (!KeyboxInstaller.moduleInstalled()) return fail(context, R.string.automation_keybox_no_module)
        val names = fetchNames() ?: return fail(context, R.string.automation_keybox_list_failed)
        if (!KeyboxInstaller.keepOriginal()) return fail(context, R.string.automation_keybox_not_kept)

        for (name in names.take(MAX_ATTEMPTS)) {
            if (!shouldContinue()) break
            val bytes = download(name) ?: continue
            if (!KeyboxXml.isKeybox(bytes) || !KeyboxInstaller.install(bytes)) continue
            val id = KeyboxXml.id(bytes)
            // The check runs on the Play Store and records the verdict. TrickyStore reads the new file without a restart.
            val check = IntegrityMonitor.checkWhenFree(pibApp)
            if (check.level == IntegrityCheck.Level.STRONG) {
                IntegrityAutomation.alert(
                    context,
                    IntegrityAutomation.ALERT_KEYBOX,
                    context.getString(R.string.automation_keybox_title),
                    context.getString(R.string.automation_keybox_passed, id),
                )
                return true
            }
            if (check.level == null) break
        }

        // The verdict went back to STRONG on another check: the keybox that works is installed, so keep it.
        if (PrefManager.integrityAutomationPhase == IntegrityAutomationPhase.PASSING) return true
        val restored = KeyboxInstaller.restoreOriginal()
        IntegrityAutomation.alert(
            context,
            IntegrityAutomation.ALERT_KEYBOX,
            context.getString(R.string.automation_keybox_title),
            context.getString(if (restored) R.string.automation_keybox_none else R.string.automation_keybox_restore_failed),
        )
        return false
    }

    /** Stops the run when the verdict is no longer degraded, or when the user turned the keybox action off. */
    private fun shouldContinue(): Boolean =
        PrefManager.integrityAutomationPhase == IntegrityAutomationPhase.DEGRADED && PrefManager.integrityAutomationKeybox

    private fun fail(context: Context, text: Int): Boolean {
        IntegrityAutomation.alert(
            context,
            IntegrityAutomation.ALERT_KEYBOX,
            context.getString(R.string.automation_keybox_title),
            context.getString(text),
        )
        return false
    }

    private fun fetchNames(): List<String>? = runCatching {
        val connection = URL(KeyboxPool.BASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                readBounded(connection.inputStream, MAX_LIST_BYTES)?.let { KeyboxPool.parseListing(it.decodeToString()) }
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Could not read the keybox list", it) }.getOrNull()

    private fun download(name: String): ByteArray? = runCatching {
        val connection = URL(KeyboxPool.BASE_URL + name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                readBounded(connection.inputStream, KeyboxXml.MAX_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Could not download a keybox", it) }.getOrNull()

    /** Reads the whole stream, or null when it is longer than [limit]. */
    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
