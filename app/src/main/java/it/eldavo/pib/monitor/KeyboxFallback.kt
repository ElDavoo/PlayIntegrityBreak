package it.eldavo.pib.monitor

import android.content.Context
import android.util.Log
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPlan
import icu.nullptr.playintegritybreak.common.KeyboxPool
import icu.nullptr.playintegritybreak.common.KeyboxXml
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.PrefManager
import it.eldavo.pib.R
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /** How many published keyboxes one run installs and checks. Each is a check against Google, so the number is kept low. */
    private const val MAX_ATTEMPTS = 20

    /** After a run that did not reach STRONG, the next one waits this long. A keybox published later is tried then. */
    private const val RETRY_AFTER_MS = 6 * 3_600_000L

    private const val TIMEOUT_MS = 15_000
    private const val MAX_LIST_BYTES = 64 * 1024

    /** A failure that escapes a run is logged: this scope has no screen or job to report it to, and it would end the app. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Keybox fallback failed", e) },
    )

    @Volatile
    private var running = false

    /** Whether a run is going. */
    val isRunning: Boolean get() = running

    /** How a run ended. */
    private enum class Outcome {
        /** A keybox gave STRONG, or the verdict was STRONG by the time the run ended. */
        PASSED,

        /** Every keybox was tried without STRONG, or none could be tried (for example, no root). */
        EXHAUSTED,

        /** A check gave no verdict, so it is not known whether the keyboxes work. The run is tried again later. */
        INCONCLUSIVE,

        /** The user turned the keybox action off during the run. */
        STOPPED,
    }

    /**
     * Starts a run, unless one is going or the last failed one was too recent. A run that reaches STRONG is not a failure,
     * so a drop after it starts a run at once.
     */
    @Synchronized
    fun startIfDue(context: Context) {
        val due = IntegrityAutomationPlan.keyboxRunDue(
            phase = PrefManager.integrityAutomationPhase,
            keybox = PrefManager.integrityAutomationKeybox,
            running = running,
            nowMs = System.currentTimeMillis(),
            lastFailedMs = PrefManager.integrityKeyboxLastFailedMs,
            retryAfterMs = RETRY_AFTER_MS,
        )
        if (!due) return
        running = true
        // The run outlives the screen that may have started it.
        val app = context.applicationContext
        scope.launch {
            // The run counts as going until its outcome is written: a run started before that would not wait its turn.
            try {
                val outcome = try {
                    run(app)
                } catch (e: Exception) {
                    Log.e(TAG, "Keybox fallback failed", e)
                    Outcome.INCONCLUSIVE
                }
                when (outcome) {
                    Outcome.PASSED -> {
                        PrefManager.integrityKeyboxExhausted = false
                        // The keybox installed now gives STRONG: the copy of the previous one is not needed.
                        KeyboxInstaller.forgetOriginal()
                    }
                    Outcome.EXHAUSTED -> {
                        PrefManager.integrityKeyboxLastFailedMs = System.currentTimeMillis()
                        PrefManager.integrityKeyboxExhausted = true
                        // No keybox gives STRONG, or none could be tried: the errors for all apps may come on now.
                        IntegrityAutomation.reconcile(app)
                    }
                    // Not known to fail, so the errors stay as they are, but the run waits before it asks Google again.
                    Outcome.INCONCLUSIVE -> PrefManager.integrityKeyboxLastFailedMs = System.currentTimeMillis()
                    Outcome.STOPPED -> Unit
                }
            } finally {
                running = false
            }
        }
    }

    private suspend fun run(context: Context): Outcome {
        if (!KeyboxInstaller.hasRoot()) return fail(context, R.string.automation_keybox_no_root)
        if (!KeyboxInstaller.moduleInstalled()) return fail(context, R.string.automation_keybox_no_module)
        val names = fetchNames() ?: return fail(context, R.string.automation_keybox_list_failed)
        if (!KeyboxInstaller.keepOriginal()) return fail(context, R.string.automation_keybox_not_kept)

        val outcome = try {
            tryKeyboxes(context, names)
        } catch (e: Exception) {
            // A keybox that was not seen to give STRONG may be installed: the one the device had is put back below.
            Log.e(TAG, "Keybox fallback failed while trying keyboxes", e)
            Outcome.INCONCLUSIVE
        }
        if (outcome == Outcome.PASSED) return outcome

        // The verdict went back to STRONG on another check: the keybox that works is installed, so keep it.
        if (PrefManager.integrityAutomationPhase == IntegrityAutomationPhase.PASSING) return Outcome.PASSED
        val restored = KeyboxInstaller.restoreOriginal()
        val text = when {
            !restored -> R.string.automation_keybox_restore_failed
            outcome == Outcome.INCONCLUSIVE -> R.string.automation_keybox_no_verdict
            outcome == Outcome.EXHAUSTED -> R.string.automation_keybox_none
            // Stopped by the user, and the previous keybox is back: nothing to tell.
            else -> null
        }
        if (text != null) {
            IntegrityAutomation.alert(
                context,
                IntegrityAutomation.ALERT_KEYBOX,
                context.getString(R.string.automation_keybox_title),
                context.getString(text),
            )
        }
        return outcome
    }

    /** Installs the published keyboxes one by one, and checks after each one, until one gives STRONG. */
    private suspend fun tryKeyboxes(context: Context, names: List<String>): Outcome {
        // keybox.xml is a copy of one of the others, and the keybox the device has, which just failed, may be listed too:
        // each keybox is installed and checked once.
        val seen = listOfNotNull(KeyboxInstaller.installedId()).toMutableSet()
        var attempts = 0
        for (name in names) {
            if (attempts >= MAX_ATTEMPTS) break
            if (!shouldContinue()) return Outcome.STOPPED
            if (name.removeSuffix(".xml") in seen) continue
            val bytes = download(name) ?: continue
            val id = KeyboxXml.id(bytes)
            if (!seen.add(id)) continue
            if (!KeyboxXml.isKeybox(bytes) || !KeyboxInstaller.install(bytes)) continue
            attempts++
            val level = checkInstalled() ?: return Outcome.INCONCLUSIVE
            if (level == IntegrityCheck.Level.STRONG) {
                IntegrityAutomation.alert(
                    context,
                    IntegrityAutomation.ALERT_KEYBOX,
                    context.getString(R.string.automation_keybox_title),
                    context.getString(R.string.automation_keybox_passed, id),
                )
                return Outcome.PASSED
            }
        }
        return if (shouldContinue()) Outcome.EXHAUSTED else Outcome.STOPPED
    }

    /**
     * The verdict with the keybox just installed. The check runs on the Play Store and records the verdict, and TrickyStore
     * reads the new file without a restart. A check with no verdict can be a passing failure of the Play Store, so it is
     * made once more before the run gives up on it.
     */
    private suspend fun checkInstalled(): IntegrityCheck.Level? {
        repeat(2) {
            IntegrityMonitor.checkWhenFree(pibApp).level?.let { return it }
        }
        return null
    }

    /** Stops the run when the verdict is no longer degraded, or when the user turned the keybox action off. */
    private fun shouldContinue(): Boolean =
        PrefManager.integrityAutomationPhase == IntegrityAutomationPhase.DEGRADED && PrefManager.integrityAutomationKeybox

    private fun fail(context: Context, text: Int): Outcome {
        IntegrityAutomation.alert(
            context,
            IntegrityAutomation.ALERT_KEYBOX,
            context.getString(R.string.automation_keybox_title),
            context.getString(text),
        )
        return Outcome.EXHAUSTED
    }

    private fun fetchNames(): List<String>? =
        get(KeyboxPool.BASE_URL, MAX_LIST_BYTES, "the keybox list")?.let { KeyboxPool.parseListing(it.decodeToString()) }

    private fun download(name: String): ByteArray? = get(KeyboxPool.BASE_URL + name, KeyboxXml.MAX_BYTES, "a keybox")

    /** The body of [url], or null when the request fails or the body is longer than [limit]. */
    private fun get(url: String, limit: Int, what: String): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null else readBounded(connection.inputStream, limit)
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Could not read $what", it) }.getOrNull()

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
