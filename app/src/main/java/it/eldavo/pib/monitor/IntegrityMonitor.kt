package it.eldavo.pib.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.IIntegrityCheckCallback
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.RequestAlert
import icu.nullptr.playintegritybreak.service.ServiceClient
import it.eldavo.pib.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Runs the Play Store's own Play Integrity check in the background, through PIB's hook in the Play Store process
 * (see PlayStoreIntegrityCheck in :core). The check needs no UI. PIB must not rewrite the Play Store's responses (the
 * default): when it does, the Play Store's token request gets a synthetic error and no verdict comes back.
 */
object IntegrityMonitor {
    private const val TAG = "IntegrityMonitor"
    private const val PERIODIC_WORK = "integrity.monitor.periodic"
    private const val NOTIFICATION_CHANNEL = "integrity_monitor"
    private const val NOTIFICATION_ID = 0x5049
    private const val SERVICE_LINK_TIMEOUT_MS = 20_000L

    /**
     * Only for a hook that hangs: the check always answers, since every wait in it is bounded, but its worst case (the
     * first check on a new Play Store build, then up to one decode per account) is past a minute. A Play Store that dies
     * during the check is seen at once, from its binder.
     */
    private const val CHECK_TIMEOUT_MS = 5 * 60_000L

    val intervalHoursOptions = intArrayOf(1, 3, 6, 12, 24)

    private val checkLock = Mutex()

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val checkingFlow = MutableStateFlow(false)

    /** Whether a check started with [checkInBackground] is running. */
    val checking: StateFlow<Boolean> = checkingFlow

    /**
     * Starts [checkAndRecord] outside of any screen, so that leaving or rotating the screen does not cancel it. Does
     * nothing while one is running.
     */
    fun checkInBackground() {
        if (!checkingFlow.compareAndSet(expect = false, update = true)) return
        backgroundScope.launch {
            try {
                checkAndRecord()
            } finally {
                checkingFlow.value = false
            }
        }
    }

    /** Runs one check, stores it in the history and notifies if the verdict dropped. */
    suspend fun checkAndRecord(context: Context = pibApp): IntegrityCheck = checkLock.withLock {
        val previous = IntegrityCheckStore.lastVerdict()
        val check = checkWithPlayStore(context)
        IntegrityCheckStore.add(check)
        notifyIfDropped(context, previous, check)
        check
    }

    private suspend fun checkWithPlayStore(context: Context): IntegrityCheck {
        val policy = ConfigManager.config.policyFor(Constants.VENDING_PACKAGE_NAME)
        if (policy.interventionEnabled && policy.rewriteResponse) {
            return IntegrityCheck(
                System.currentTimeMillis(),
                IntegrityCheck.Status.PLAY_STORE_ERROR,
                "PIB gives the Play Store synthetic errors, so it gets no verdict. Turn off \"Enable synthetic errors\" for the Play Store in PIB.",
            )
        }
        // The link can die before or during the call, when the Play Store is killed. Then one more attempt is made,
        // once the Play Store has linked back.
        var lostReason = ""
        repeat(2) {
            when (val attempt = attemptPlayStoreCheck(context)) {
                is Attempt.Finished -> return attempt.check
                is Attempt.LinkLost -> lostReason = attempt.reason
                Attempt.Unreachable -> return IntegrityCheck(
                    System.currentTimeMillis(),
                    IntegrityCheck.Status.UNREACHABLE,
                    "",
                )
            }
        }
        return IntegrityCheck(
            System.currentTimeMillis(),
            IntegrityCheck.Status.PLAY_STORE_ERROR,
            "The Play Store's hook link was lost during the check: $lostReason",
        )
    }

    /** One attempt at the check: waits for the hook, then calls it. */
    private suspend fun attemptPlayStoreCheck(context: Context): Attempt =
        withPlayStoreLink(context, ready = { ServiceClient.isLinked }) {
            // Checked once linked: a hook that is not running yet cannot tell its version.
            if (ServiceClient.canRunPlayStoreCheck) {
                callPlayStore()
            } else {
                Attempt.Finished(
                    IntegrityCheck(
                        System.currentTimeMillis(),
                        IntegrityCheck.Status.PLAY_STORE_ERROR,
                        "The Play Store hook is older than this PIB; update the module.",
                    ),
                )
            }
        } ?: Attempt.Unreachable

    private suspend fun callPlayStore(): Attempt {
        val binder = ServiceClient.linkedBinder ?: return Attempt.LinkLost("the hook is not linked")
        val outcome = CompletableDeferred<Attempt>()
        val callback = object : IIntegrityCheckCallback.Stub() {
            override fun onResult(resultCode: Int, resultData: String?) {
                outcome.complete(Attempt.Finished(parsePlayStoreResult(resultCode, resultData)))
            }
        }
        // The Play Store can die during the check, and then the callback never comes.
        val death = IBinder.DeathRecipient { outcome.complete(Attempt.LinkLost("the Play Store died during the check")) }
        try {
            binder.linkToDeath(death, 0)
            RequestAlert.onOwnCheck()
            ServiceClient.runPlayStoreIntegrityCheck(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Play Store link lost before the check started", e)
            runCatching { binder.unlinkToDeath(death, 0) }
            return Attempt.LinkLost(e.toString())
        }
        try {
            return withTimeoutOrNull(CHECK_TIMEOUT_MS) { outcome.await() }
                ?: Attempt.Finished(
                    IntegrityCheck(System.currentTimeMillis(), IntegrityCheck.Status.PLAY_STORE_ERROR, "Timed out"),
                )
        } finally {
            runCatching { binder.unlinkToDeath(death, 0) }
        }
    }

    /** The outcome of one attempt at the check. */
    private sealed interface Attempt {
        class Finished(val check: IntegrityCheck) : Attempt
        class LinkLost(val reason: String) : Attempt
        object Unreachable : Attempt
    }

    /** The check answers with the verdict JSON, or with the reason it failed. */
    private fun parsePlayStoreResult(code: Int, data: String?): IntegrityCheck {
        val now = System.currentTimeMillis()
        return when (code) {
            Constants.PLAY_STORE_RESULT_OK -> IntegrityCheck(now, IntegrityCheck.Status.OK, data.orEmpty())
            else -> IntegrityCheck(now, IntegrityCheck.Status.PLAY_STORE_ERROR, data.orEmpty())
        }
    }

    /**
     * Binds the Play Store's integrity service for the length of [block], and waits up to [SERVICE_LINK_TIMEOUT_MS] for
     * [ready]. The bind starts the Play Store if it is dead, and its hook then links back to PIB. The bind also keeps the
     * Play Store from being frozen during the call: a binder call into a frozen process gets that process killed.
     * Returns null when the hook never became ready.
     */
    private suspend fun <T> withPlayStoreLink(context: Context, ready: () -> Boolean, block: suspend () -> T): T? {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(Constants.PLAY_INTEGRITY_BIND_ACTION).setPackage(Constants.VENDING_PACKAGE_NAME)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        try {
            val linked = withTimeoutOrNull(SERVICE_LINK_TIMEOUT_MS) {
                while (!ready()) delay(500)
                true
            } ?: false
            return if (linked) block() else null
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    // Periodic monitoring

    /**
     * Schedules or cancels the periodic check. [replace] reschedules an existing check (after the
     * interval changed); otherwise it is kept, since replacing it cancels a run that is starting.
     */
    fun syncSchedule(context: Context = pibApp, replace: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        if (!PrefManager.integrityMonitorEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }

        val request = PeriodicWorkRequestBuilder<Worker>(
            PrefManager.integrityMonitorIntervalHours.toLong(),
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()

        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, policy, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            checkAndRecord(applicationContext)
            return Result.success()
        }
    }

    // Notifications

    private fun notifyIfDropped(context: Context, previous: IntegrityCheck?, current: IntegrityCheck) {
        val before = previous?.level ?: return
        val after = current.level ?: return
        // Levels are declared from the strongest down.
        if (after.ordinal <= before.ordinal) return

        RequestAlert.postNotification(
            channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                context.getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            id = NOTIFICATION_ID,
            title = context.getString(R.string.monitor_notification_title),
            text = context.getString(R.string.monitor_notification_text, before.label, after.label),
            timestampMs = current.timestampMs,
        )
    }
}
