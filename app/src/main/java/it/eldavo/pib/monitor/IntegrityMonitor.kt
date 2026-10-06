package it.eldavo.pib.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.Shell
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.IIntegrityCheckCallback
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.ServiceClient
import it.eldavo.pib.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Asks the Play Integrity API Checker app to run an integrity check in the background.
 *
 * A broadcast to the checker's ProfileInstallReceiver starts it without UI; PIB's hook in the
 * checker answers it with the backend's raw JSON as the broadcast result (see CheckerMonitorHook
 * in :core). The receiver requires the DUMP permission, so the broadcast is sent by PIB's
 * Play Store hook, or as root when the hook cannot be reached.
 */
object IntegrityMonitor {
    private const val TAG = "IntegrityMonitor"
    private const val PERIODIC_WORK = "integrity.monitor.periodic"
    private const val NOTIFICATION_CHANNEL = "integrity_monitor"
    private const val NOTIFICATION_ID = 0x5049
    private const val PLAY_INTEGRITY_BIND_ACTION = "com.google.android.play.core.integrityservice.BIND_INTEGRITY_SERVICE"
    private const val SERVICE_LINK_TIMEOUT_MS = 20_000L

    // The checker answers within the 60s timeout of a background broadcast.
    private const val CHECK_TIMEOUT_MS = 70_000L

    val intervalHoursOptions = intArrayOf(1, 3, 6, 12, 24)

    private val resultPattern = Regex("""Broadcast completed: result=(-?\d+)(?:, data="([^"]*)")?""")
    private val checkLock = Mutex()

    fun isCheckerInstalled(context: Context = pibApp): Boolean = runCatching {
        context.packageManager.getPackageInfo(Constants.CHECKER_PACKAGE_NAME, 0)
    }.isSuccess

    /** Runs one check, stores it in the history and notifies if the verdict dropped. */
    suspend fun checkAndRecord(context: Context = pibApp): IntegrityCheck = checkLock.withLock {
        val previous = IntegrityCheckStore.lastVerdict()
        val check = runCheck(context)
        IntegrityCheckStore.add(check)
        notifyIfDropped(context, previous, check)
        check
    }

    private suspend fun runCheck(context: Context): IntegrityCheck {
        if (!isCheckerInstalled(context)) {
            return IntegrityCheck(System.currentTimeMillis(), IntegrityCheck.Status.CHECKER_NOT_INSTALLED, "")
        }
        return checkViaPlayStore(context)
            ?: checkViaRoot()
            ?: IntegrityCheck(System.currentTimeMillis(), IntegrityCheck.Status.UNREACHABLE, "")
    }

    /**
     * Has PIB's Play Store hook send the broadcast: the Play Store holds the DUMP permission the
     * checker's receiver requires. Returns null when the hook is not reachable.
     */
    private suspend fun checkViaPlayStore(context: Context): IntegrityCheck? {
        if (!awaitPlayStoreService(context)) return null

        val result = CompletableDeferred<Pair<Int, String?>>()
        val callback = object : IIntegrityCheckCallback.Stub() {
            override fun onResult(resultCode: Int, resultData: String?) {
                result.complete(resultCode to resultData)
            }
        }
        runCatching { ServiceClient.runIntegrityCheck(callback) }.onFailure {
            Log.w(TAG, "Play Store service failed to run the check", it)
            return null
        }
        val (code, data) = withTimeoutOrNull(CHECK_TIMEOUT_MS) { result.await() }
            ?: return IntegrityCheck(System.currentTimeMillis(), IntegrityCheck.Status.INTERNAL_ERROR, "Timed out")
        return parseResult(code, data)
    }

    /**
     * Waits for the Play Store hook's binder. A dead Play Store is started by binding to its
     * integrity service; the hook then links to PIB within a heartbeat.
     */
    private suspend fun awaitPlayStoreService(context: Context): Boolean {
        if (ServiceClient.canRunIntegrityCheck) return true
        // Linked, but too old to run checks.
        if (ServiceClient.serviceVersion > 0) return false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(PLAY_INTEGRITY_BIND_ACTION).setPackage(Constants.VENDING_PACKAGE_NAME)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        try {
            return withTimeoutOrNull(SERVICE_LINK_TIMEOUT_MS) {
                while (!ServiceClient.canRunIntegrityCheck) delay(500)
                true
            } ?: false
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    /** Sends the broadcast as root. Returns null when root is not available. */
    private fun checkViaRoot(): IntegrityCheck? {
        var check = sendRootBroadcast() ?: return null
        if (check.status == IntegrityCheck.Status.NOT_HOOKED) {
            // A checker process started before the hook was enabled answers unhooked: restart it once.
            Shell.cmd("am force-stop ${Constants.CHECKER_PACKAGE_NAME}").exec()
            check = sendRootBroadcast() ?: return null
        }
        return check
    }

    private fun sendRootBroadcast(): IntegrityCheck? {
        val component = "${Constants.CHECKER_PACKAGE_NAME}/${Constants.CHECKER_TRIGGER_RECEIVER}"
        val shellResult = Shell.cmd("am broadcast -n $component -a ${Constants.CHECKER_ACTION_RUN_CHECK}").exec()
        if (Shell.isAppGrantedRoot() != true) return null

        val output = shellResult.out.joinToString("\n")
        val match = resultPattern.find(output) ?: return IntegrityCheck(
            System.currentTimeMillis(),
            IntegrityCheck.Status.INTERNAL_ERROR,
            (output + shellResult.err.joinToString("\n")).trim(),
        )
        return parseResult(match.groupValues[1].toInt(), match.groupValues[2])
    }

    /** Decodes the checker broadcast's result code and (Base64) data. */
    private fun parseResult(code: Int, data: String?): IntegrityCheck {
        val payload = if (data.isNullOrEmpty()) "" else {
            runCatching { String(Base64.decode(data, Base64.DEFAULT)) }.getOrDefault(data)
        }
        val status = when (code) {
            Constants.CHECKER_RESULT_OK -> IntegrityCheck.Status.OK
            Constants.CHECKER_RESULT_INTEGRITY_ERROR -> IntegrityCheck.Status.INTEGRITY_ERROR
            Constants.CHECKER_RESULT_SERVER_ERROR -> IntegrityCheck.Status.SERVER_ERROR
            Constants.CHECKER_RESULT_INTERNAL_ERROR -> IntegrityCheck.Status.INTERNAL_ERROR
            else -> IntegrityCheck.Status.NOT_HOOKED
        }
        return IntegrityCheck(System.currentTimeMillis(), status, payload)
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

        val manager = context.getSystemService(NotificationManager::class.java)
        // False when POST_NOTIFICATIONS is not granted.
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                context.getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.monitor_notification_title))
            .setContentText(context.getString(R.string.monitor_notification_text, before.label, after.label))
            .setWhen(current.timestampMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .apply {
                if (launch != null) {
                    setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
