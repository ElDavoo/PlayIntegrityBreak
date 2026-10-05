package icu.nullptr.playintegritybreak.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import icu.nullptr.playintegritybreak.common.JsonConfig.AlertStyle
import icu.nullptr.playintegritybreak.pibApp
import it.eldavo.pib.R

/**
 * Tells the user that an app asked for Play Integrity. The hook only reports the request: showing
 * the alert from PIB (instead of the Play Store) gives it PIB's icon and name.
 */
object RequestAlert {
    private const val TAG = "RequestAlert"
    // High importance, so the notification pops up on screen like the toast. Channel importance
    // can't be raised once created, so this replaces the default-importance channel of earlier builds.
    private const val CHANNEL_ID = "integrity_requests_popup"
    private const val OLD_CHANNEL_ID = "integrity_requests"

    /** Requests buffered by the hook while PIB was unreachable are too old to alert about. */
    private const val MAX_AGE_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun onRequest(packageName: String, timestampMs: Long) {
        val config = ConfigManager.config
        if (!config.policyFor(packageName).requestAlert) return
        if (System.currentTimeMillis() - timestampMs > MAX_AGE_MS) return

        val text = pibApp.getString(R.string.integrity_request_alert, loadLabel(packageName))
        mainHandler.post {
            runCatching {
                when (config.requestAlertStyle) {
                    AlertStyle.TOAST -> Toast.makeText(pibApp, text, Toast.LENGTH_SHORT).show()
                    AlertStyle.NOTIFICATION -> notify(packageName, text, timestampMs)
                }
            }.onFailure {
                Log.w(TAG, "Failed to show integrity request alert", it)
            }
        }
    }

    /** Asks for the notification permission if any app can trigger an alert. */
    fun requestPermissionIfNeeded(activity: Activity) {
        val config = ConfigManager.config
        if (config.defaults.requestAlert || config.scope.values.any { it.requestAlert == true }) {
            requestPermission(activity)
        }
    }

    /** Alerts are shown from the background, which Android only allows with notifications enabled. */
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    private fun loadLabel(packageName: String): CharSequence = runCatching {
        val pm = pibApp.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm)
    }.getOrDefault(packageName)

    private fun notify(packageName: String, text: String, timestampMs: Long) {
        val manager = pibApp.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                pibApp.getString(R.string.integrity_request_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val launch = pibApp.packageManager.getLaunchIntentForPackage(pibApp.packageName)
        val notification = Notification.Builder(pibApp, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(pibApp.getString(R.string.integrity_request_notification_title))
            .setContentText(text)
            .setWhen(timestampMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .apply {
                if (launch != null) {
                    setContentIntent(PendingIntent.getActivity(pibApp, 0, launch, PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .build()
        // One notification per app, updated on every request.
        manager.notify(packageName.hashCode(), notification)
    }
}
