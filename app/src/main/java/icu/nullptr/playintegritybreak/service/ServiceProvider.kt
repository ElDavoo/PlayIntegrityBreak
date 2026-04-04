package icu.nullptr.playintegritybreak.service

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.telemetry.AppIntegrityEventStore
import it.eldavo.pib.common.BuildConfig
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

    override fun onCreate(): Boolean {
        val ready = context != null
        if (!ready) {
            Log.e(TAG, "Provider initialization failed: context is null")
        }
        return ready
    }

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

    private fun publishEvent(extras: Bundle?): Bundle {
        val timestampMs = extras?.getLong(Constants.PROVIDER_EXTRA_EVENT_TIMESTAMP_MS)
            ?: System.currentTimeMillis()
        val packageName = extras?.getString(Constants.PROVIDER_EXTRA_EVENT_PACKAGE)?.trim().orEmpty()
        val playIntegrityVersionMajor = extras?.getIntOrNull(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MAJOR)
        val playIntegrityVersionMinor = extras?.getIntOrNull(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MINOR)
        val playIntegrityVersionPatch = extras?.getIntOrNull(Constants.PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_PATCH)
        val eventType = extras?.getString(Constants.PROVIDER_EXTRA_EVENT_TYPE)?.trim().orEmpty()
        val source = extras?.getString(Constants.PROVIDER_EXTRA_EVENT_SOURCE)?.trim().orEmpty()

        if (packageName.isBlank() || eventType.isBlank() || source.isBlank()) {
            return Bundle().apply {
                putBoolean(Constants.PROVIDER_RESULT_OK, false)
            }
        }

        val success = extras?.let {
            if (it.containsKey(Constants.PROVIDER_EXTRA_EVENT_SUCCESS)) {
                it.getBoolean(Constants.PROVIDER_EXTRA_EVENT_SUCCESS)
            } else {
                null
            }
        }

        val errorCode = extras?.let {
            if (it.containsKey(Constants.PROVIDER_EXTRA_EVENT_ERROR_CODE)) {
                it.getInt(Constants.PROVIDER_EXTRA_EVENT_ERROR_CODE)
            } else {
                null
            }
        }

        val retriable = extras?.let {
            if (it.containsKey(Constants.PROVIDER_EXTRA_EVENT_RETRIABLE)) {
                it.getBoolean(Constants.PROVIDER_EXTRA_EVENT_RETRIABLE)
            } else {
                null
            }
        }

        val stored = AppIntegrityEventStore.appendPublishedEvent(
            timestampMs = timestampMs,
            packageName = packageName,
            playIntegrityVersionMajor = playIntegrityVersionMajor,
            playIntegrityVersionMinor = playIntegrityVersionMinor,
            playIntegrityVersionPatch = playIntegrityVersionPatch,
            eventType = eventType,
            success = success,
            errorCode = errorCode,
            retriable = retriable,
            source = source,
        )

        return Bundle().apply {
            putBoolean(Constants.PROVIDER_RESULT_OK, stored)
        }
    }

    private fun Bundle.getIntOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        return getInt(key)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isCallerAllowed()) {
            Log.w(
                TAG,
                "Rejected provider call: method=$method caller=$callingPackage uid=${Binder.getCallingUid()}"
            )
            return null
        }

        when (method) {
            Constants.PROVIDER_METHOD_HEALTHCHECK -> {
                return Bundle().apply {
                    putInt("serviceVersion", ServiceClient.serviceVersion)
                    putLong("healthcheckTimestamp", ServiceClient.serviceHealthcheckTimestamp)
                }
            }

            Constants.PROVIDER_METHOD_PUBLISH_EVENT -> {
                return publishEvent(extras)
            }

            Constants.PROVIDER_METHOD_LINK -> {
                val binder = extras?.getBinder(Constants.PROVIDER_EXTRA_BINDER) ?: return null
                if (ServiceClient.linkService(binder)) {
                    syncConfigSnapshotAsync()
                }
                return Bundle().apply {
                    putBoolean(Constants.PROVIDER_RESULT_OK, true)
                }
            }

            else -> return null
        }
    }
}
