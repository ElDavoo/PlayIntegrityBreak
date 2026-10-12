package it.eldavo.pib.monitor

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
    private const val SERVICE_LINK_TIMEOUT_MS = 20_000L

    /**
     * Only for a hook that hangs: the check always answers, since every wait in it is bounded, but its worst case (the
     * first check on a new Play Store build, then up to one decode per account) is past a minute. A Play Store that dies
     * during the check is seen at once, from its binder.
     */
    private const val CHECK_TIMEOUT_MS = 5 * 60_000L

    /** How often the periodic check runs. */
    private const val INTERVAL_HOURS = 3L

    private val checkLock = Mutex()

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val checkingFlow = MutableStateFlow(false)

    /** Whether a check is running, started from [checkInBackground] or by the periodic [Worker]. */
    val checking: StateFlow<Boolean> = checkingFlow

    /**
     * Starts [checkAndRecord] outside of any screen, so that leaving or rotating the screen does not cancel it. A failure
     * is logged: this scope has no screen or job to report it to, and an uncaught one would end the app.
     */
    fun checkInBackground() {
        if (checkLock.isLocked) return
        backgroundScope.launch {
            try {
                checkAndRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Integrity check failed", e)
            }
        }
    }

    /**
     * Runs one check, stores it in the history, and lets the telemetry and the automation see it. Returns null without
     * checking when a check is already running: its result is the one that gets recorded, and a second would ask Google
     * again right after.
     */
    suspend fun checkAndRecord(context: Context = pibApp): IntegrityCheck? {
        if (!checkLock.tryLock()) return null
        try {
            return recordCheck(context)
        } finally {
            checkLock.unlock()
        }
    }

    /**
     * Like [checkAndRecord], but waits for a check that is running. The keybox fallback needs the result of a check it
     * starts itself, so it cannot take the one already running. Each of its checks goes to the telemetry, even one without
     * a verdict.
     */
    suspend fun checkWhenFree(context: Context = pibApp): IntegrityCheck =
        checkLock.withLock { recordCheck(context, keyboxRun = true) }

    /**
     * Runs the check, stores it, and lets the telemetry and the automation see it. The caller holds [checkLock], so the
     * automation sees the checks in the order they ran. A keybox run the automation starts takes the lock for its own
     * checks, so it waits for this one to end.
     */
    private suspend fun recordCheck(context: Context, keyboxRun: Boolean = false): IntegrityCheck {
        checkingFlow.value = true
        try {
            val check = checkWithPlayStore(context)
            IntegrityCheckStore.add(check)
            // The telemetry reads the installed keybox first: the automation can start a keybox run, which installs others.
            IntegrityTelemetry.sendVerdict(check, withoutVerdict = keyboxRun)
            IntegrityAutomation.onCheck(context, check, keyboxRun)
            return check
        } finally {
            // Cleared once the automation has written its phase, so that the screen shows the new one.
            checkingFlow.value = false
        }
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
        // The version is read once linked, from the same call that tells the link is up: a hook that is not running yet
        // cannot tell its version.
        withPlayStoreLink(context, linked = { ServiceClient.liveVersion }) { version ->
            if (ServiceClient.canRunPlayStoreCheck(version)) {
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
     * [linked] to give a value, which [block] gets. The bind starts the Play Store if it is dead, and its hook then links
     * back to PIB. The bind also keeps the Play Store from being frozen during the call: a binder call into a frozen
     * process gets that process killed. Returns null when the hook never linked.
     */
    private suspend fun <L : Any, T> withPlayStoreLink(context: Context, linked: () -> L?, block: suspend (L) -> T): T? {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(Constants.PLAY_INTEGRITY_BIND_ACTION).setPackage(Constants.VENDING_PACKAGE_NAME)
        runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
        try {
            val link = withTimeoutOrNull(SERVICE_LINK_TIMEOUT_MS) {
                var value = linked()
                while (value == null) {
                    delay(500)
                    value = linked()
                }
                value
            }
            return link?.let { block(it) }
        } finally {
            // The system keeps the connection until it is unbound, even when the bind failed.
            runCatching { context.unbindService(connection) }
        }
    }

    // Periodic monitoring

    /**
     * Schedules or cancels the periodic check. A check scheduled with [INTERVAL_HOURS] is kept, since replacing it cancels
     * a run that is starting. One scheduled with another interval, from when the interval was a setting, is updated once.
     */
    fun syncSchedule(context: Context = pibApp) {
        val workManager = WorkManager.getInstance(context)
        if (!PrefManager.integrityMonitorEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }

        val request = PeriodicWorkRequestBuilder<Worker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()

        val policy = if (PrefManager.integrityMonitorScheduledHours == INTERVAL_HOURS.toInt()) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, policy, request)
        PrefManager.integrityMonitorScheduledHours = INTERVAL_HOURS.toInt()
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            checkAndRecord(applicationContext)
            return Result.success()
        }
    }

}
