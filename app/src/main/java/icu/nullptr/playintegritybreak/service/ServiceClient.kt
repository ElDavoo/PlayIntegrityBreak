package icu.nullptr.playintegritybreak.service

import android.os.IBinder
import android.util.Log
import icu.nullptr.playintegritybreak.common.IPIBService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object ServiceClient : IPIBService, IBinder.DeathRecipient {

    private const val TAG = "ServiceClient"
    private const val STATUS_CACHE_GRACE_MS = 120_000L

    private class ServiceProxy(private val obj: IPIBService) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val result = method.invoke(obj, *args.orEmpty())
            if (result == null) Log.i(TAG, "Call service method ${method.name}")
            else Log.i(TAG, "Call service method ${method.name} with result " + result.toString().take(20))
            return result
        }
    }

    @Volatile
    private var service: IPIBService? = null

    @Volatile
    private var linkedBinder: IBinder? = null

    @Volatile
    private var lastKnownServiceVersion: Int = 0

    @Volatile
    private var lastKnownHealthcheckTimestamp: Long = 0L

    @Volatile
    private var lastKnownLinkTimestamp: Long = 0L

    private fun updateStatusCache(version: Int? = null, healthcheckTimestamp: Long? = null) {
        val now = System.currentTimeMillis()
        version?.let {
            lastKnownServiceVersion = it
            lastKnownLinkTimestamp = now
        }
        healthcheckTimestamp?.let {
            if (it > 0L) {
                lastKnownHealthcheckTimestamp = it
                lastKnownLinkTimestamp = now
            }
        }
    }

    private fun isStatusCacheFresh(): Boolean {
        val lastLink = lastKnownLinkTimestamp
        if (lastLink <= 0L) return false
        return System.currentTimeMillis() - lastLink <= STATUS_CACHE_GRACE_MS
    }

    fun linkService(binder: IBinder): Boolean {
        if (linkedBinder == binder && service != null) {
            return false
        }

        linkedBinder?.let {
            runCatching { it.unlinkToDeath(this, 0) }
        }

        service = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IPIBService::class.java),
            ServiceProxy(IPIBService.Stub.asInterface(binder))
        ) as IPIBService
        linkedBinder = binder
        binder.linkToDeath(this, 0)

        val initialVersion = runCatching { service?.serviceVersion }.getOrNull()
        val initialHealthcheck = runCatching { service?.serviceHealthcheckTimestamp }.getOrNull()
        updateStatusCache(initialVersion, initialHealthcheck)
        Log.i(
            TAG,
            "Service linked: version=${initialVersion ?: 0}, healthcheck=${initialHealthcheck ?: 0L}"
        )
        return true
    }

    override fun binderDied() {
        service = null
        linkedBinder = null
        Log.e(TAG, "Binder died")
    }

    override fun asBinder() = service?.asBinder()

    override fun getServiceVersion(): Int {
        service?.let { remote ->
            val live = runCatching { remote.serviceVersion }.getOrNull()
            if (live != null) {
                updateStatusCache(version = live)
                return live
            }
        }
        return if (isStatusCacheFresh()) lastKnownServiceVersion else 0
    }

    override fun getServiceHealthcheckTimestamp(): Long {
        service?.let { remote ->
            val live = runCatching { remote.serviceHealthcheckTimestamp }.getOrNull()
            if (live != null) {
                updateStatusCache(healthcheckTimestamp = live)
                return live
            }
        }
        return if (isStatusCacheFresh()) lastKnownHealthcheckTimestamp else 0L
    }

    override fun getFilterCount() = service?.filterCount ?: 0

    override fun getLogs() = service?.logs

    override fun clearLogs() {
        service?.clearLogs()
    }

    override fun readConfig() = service?.readConfig()

    override fun writeConfig(json: String) {
        val remote = service
        if (remote == null) {
            Log.w(TAG, "writeConfig skipped: service is not linked yet")
            return
        }

        runCatching {
            remote.writeConfig(json)
        }.onFailure {
            Log.w(TAG, "writeConfig failed", it)
        }
    }

    override fun log(level: Int, tag: String, message: String) {
        service?.log(level, tag, message)
    }

    override fun getPackageNames(userId: Int) = service?.getPackageNames(userId)

    override fun getPackageInfo(
        packageName: String?,
        userId: Int
    ) = service?.getPackageInfo(packageName, userId)

    override fun getLogFileLocation() = service?.logFileLocation ?: "the log file"
}
