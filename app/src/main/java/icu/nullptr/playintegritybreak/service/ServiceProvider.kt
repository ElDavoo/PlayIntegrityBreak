package icu.nullptr.playintegritybreak.service

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import it.eldavo.pib_oss.common.BuildConfig
import java.io.File
import kotlin.concurrent.thread

class ServiceProvider : ContentProvider() {

    private companion object {
        private const val TAG = "ServiceProvider"
        private const val CONFIG_FILE_NAME = "config.json"
    }

    private val allowedCallers = setOf(
        Constants.ANDROID_PACKAGE_NAME,
        Constants.VENDING_PACKAGE_NAME,
        BuildConfig.APP_PACKAGE_NAME,
    )

    override fun onCreate() = false

    override fun query(p0: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?) = null

    override fun getType(p0: Uri) = null

    override fun insert(p0: Uri, p1: ContentValues?) = null

    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?) = 0

    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?) = 0

    private fun isCallerAllowed(): Boolean {
        val directCaller = callingPackage
        if (directCaller != null && directCaller in allowedCallers) {
            return true
        }

        val uidPackages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid()) ?: return false
        return uidPackages.any { it in allowedCallers }
    }

    private fun syncConfigSnapshotAsync() {
        val appContext = context ?: return
        thread(name = "PIB-ConfigSync", isDaemon = true) {
            runCatching {
                val configFile = File(appContext.filesDir, CONFIG_FILE_NAME)
                if (!configFile.exists()) return@runCatching
                ServiceClient.writeConfig(configFile.readText())
            }.onFailure {
                Log.w(TAG, "Failed to sync config snapshot", it)
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isCallerAllowed()) return null

        if (method == "healthcheck") {
            return Bundle().apply {
                putInt("serviceVersion", ServiceClient.serviceVersion)
                putLong("healthcheckTimestamp", ServiceClient.serviceHealthcheckTimestamp)
            }
        }

        val binder = extras?.getBinder("binder") ?: return null
        if (ServiceClient.linkService(binder)) {
            syncConfigSnapshotAsync()
        }
        return Bundle()
    }
}
