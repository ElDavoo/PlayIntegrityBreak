package icu.nullptr.playintegritybreak.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import icu.nullptr.playintegritybreak.common.Constants
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs inside the Play Integrity checker app (gr.nikolasspyr.integritycheck) and performs an
 * integrity check on PIB's request, without any UI.
 *
 * PIB starts the checker process with a broadcast to its (androidx) ProfileInstallReceiver, the
 * only component that can be started without showing anything. The receiver requires the DUMP
 * permission, so the broadcast is sent by PIB's Play Store hook (see PIBLoggerService), or as root.
 * The hook answers that broadcast instead of the receiver: it requests a token from the Play Store
 * as the checker, sends it to the checker's backend and returns the backend's raw JSON as the
 * broadcast result. PIB parses the JSON.
 *
 * The checker is obfuscated, so its own request code cannot be called reliably. The request is
 * re-implemented here with the same protocol the bundled Play Core library uses.
 */
object CheckerMonitorHook {
    private const val TAG = "PIB-CheckerMonitor"

    private const val RECEIVER_CLASS = "androidx.profileinstaller.ProfileInstallReceiver"

    private const val BIND_ACTION = "com.google.android.play.core.integrityservice.BIND_INTEGRITY_SERVICE"
    private const val SERVICE_DESCRIPTOR = "com.google.android.play.core.integrity.protocol.IIntegrityService"
    private const val CALLBACK_DESCRIPTOR = "com.google.android.play.core.integrity.protocol.IIntegrityServiceCallback"
    private const val TRANSACTION_REQUEST_INTEGRITY_TOKEN = 2
    private const val TRANSACTION_ON_REQUEST_INTEGRITY_TOKEN = 2

    // Same values as the Play Core library bundled in the checker (integrity 1.4.0).
    private const val PLAY_CORE_INTEGRITY_VERSION_MAJOR = 1
    private const val PLAY_CORE_INTEGRITY_VERSION_MINOR = 4
    private const val PLAY_CORE_INTEGRITY_VERSION_PATCH = 0
    private const val EVENT_TYPE_REQUEST = 3
    private const val NONCE_BYTES = 32

    // Error code reported by Play Core when the service answers without a token.
    private const val ERROR_NO_TOKEN = -100

    // The trigger is a background broadcast (60s ANR timeout): stay well below it.
    private const val TOKEN_TIMEOUT_MS = 25_000L
    private const val HTTP_TIMEOUT_MS = 15_000

    fun install(classLoader: ClassLoader) {
        val receiverClass = classLoader.loadClass(RECEIVER_CLASS)
        val onReceive = receiverClass.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
        Backend.current.hook(onReceive, object : MethodHook() {
            override fun before(param: HookParam) {
                val intent = param.args[1] as? Intent ?: return
                if (intent.action != Constants.CHECKER_ACTION_RUN_CHECK) return
                // The receiver's context is a ReceiverRestrictedContext, which cannot bind services.
                val context = (param.args[0] as Context).applicationContext
                val receiver = param.thisObject as BroadcastReceiver
                param.result = null

                val pending = receiver.goAsync()
                thread(name = "PIB-CheckerMonitor") {
                    val result = runCheck(context)
                    pending.setResult(result.status, Base64.encodeToString(result.payload.toByteArray(), Base64.NO_WRAP), null)
                    pending.finish()
                }
            }
        })
        logI(TAG, "Checker monitor hook installed")
    }

    private class CheckResult(val status: Int, val payload: String)

    private fun runCheck(context: Context): CheckResult {
        val tokenBundle = try {
            requestIntegrityToken(context)
        } catch (e: Exception) {
            logW(TAG, "Integrity token request failed", e)
            return CheckResult(Constants.CHECKER_RESULT_INTERNAL_ERROR, e.toString())
        }

        val error = tokenBundle.getInt("error")
        if (error != 0) {
            return CheckResult(Constants.CHECKER_RESULT_INTEGRITY_ERROR, error.toString())
        }
        val token = tokenBundle.getString("token")
            ?: return CheckResult(Constants.CHECKER_RESULT_INTEGRITY_ERROR, ERROR_NO_TOKEN.toString())

        return try {
            val connection = URL(Constants.CHECKER_API_URL + token).openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    CheckResult(Constants.CHECKER_RESULT_OK, body)
                } else {
                    CheckResult(Constants.CHECKER_RESULT_SERVER_ERROR, "HTTP $code $body")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logW(TAG, "Checker backend request failed", e)
            CheckResult(Constants.CHECKER_RESULT_SERVER_ERROR, e.toString())
        }
    }

    /** Asks the Play Store for a token; returns the callback bundle ("token" or "error"). */
    private fun requestIntegrityToken(context: Context): Bundle {
        val response = CompletableFuture<Bundle>()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TRANSACTION_ON_REQUEST_INTEGRITY_TOKEN) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
                response.complete(bundle)
                return true
            }
        }.apply { attachInterface(null, CALLBACK_DESCRIPTOR) }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                    data.writeInt(1)
                    buildRequest(context.packageName).writeToParcel(data, 0)
                    data.writeStrongBinder(callback)
                    service.transact(TRANSACTION_REQUEST_INTEGRITY_TOKEN, data, null, IBinder.FLAG_ONEWAY)
                } catch (e: Exception) {
                    response.completeExceptionally(e)
                } finally {
                    data.recycle()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                response.completeExceptionally(IllegalStateException("Integrity service disconnected"))
            }
        }

        val intent = Intent(BIND_ACTION).setPackage(Constants.VENDING_PACKAGE_NAME)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            runCatching { context.unbindService(connection) }
            throw IllegalStateException("Cannot bind to the integrity service")
        }
        try {
            return response.get(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun buildRequest(packageName: String) = Bundle().apply {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        putString("package.name", packageName)
        putByteArray("nonce", nonce)
        putInt("playcore.integrity.version.major", PLAY_CORE_INTEGRITY_VERSION_MAJOR)
        putInt("playcore.integrity.version.minor", PLAY_CORE_INTEGRITY_VERSION_MINOR)
        putInt("playcore.integrity.version.patch", PLAY_CORE_INTEGRITY_VERSION_PATCH)
        val event = Bundle().apply {
            putInt("event_type", EVENT_TYPE_REQUEST)
            putLong("event_timestamp", System.currentTimeMillis())
        }
        putParcelableArrayList("event_timestamps", arrayListOf(event))
    }
}
