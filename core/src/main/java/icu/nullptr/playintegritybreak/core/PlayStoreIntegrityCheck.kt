package icu.nullptr.playintegritybreak.core

import android.accounts.AccountManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.PlayStoreVerdict
import icu.nullptr.playintegritybreak.core.playstore.ApkDex
import icu.nullptr.playintegritybreak.core.playstore.DecodeEndpoint
import icu.nullptr.playintegritybreak.core.playstore.DecodeEndpointLocator
import icu.nullptr.playintegritybreak.core.playstore.DecodeRequest
import icu.nullptr.playintegritybreak.core.playstore.EndpointNotFound
import icu.nullptr.playintegritybreak.core.playstore.FactoryProvider
import icu.nullptr.playintegritybreak.core.playstore.FactoryProviderLocator
import icu.nullptr.playintegritybreak.core.playstore.LOG_TAG
import icu.nullptr.playintegritybreak.core.playstore.ObjectGraph
import icu.nullptr.playintegritybreak.core.playstore.RequestMessage
import icu.nullptr.playintegritybreak.core.playstore.RequestSenders
import icu.nullptr.playintegritybreak.core.playstore.Unsupported
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reads the Play Integrity verdict from the Play Store, inside the Play Store process, without UI.
 *
 * It sends the two requests of the Play Store's dev-options check ("Vérifier l'intégrité"), in the same order:
 * 1. A classic token from the Play Store's integrity service (binder transaction 2) for the nonce
 *    "testing-" + 16 random bytes. The Play Store sends fdfe/integrity itself to make the token.
 * 2. fdfe/decodeintegritytoken, through the Play Store's request sender for the signed-in account.
 *    The body has the token and the device. Its device id must be the nonce's suffix, or the server answers
 *    400 (DF-DFERH-01). That is the nonce binding: a decode request can only be made for the token it was
 *    asked for. The body and the sender are the Play Store's own, so the request headers are the Play Store's.
 *    See docs/playstore-checker-di-chain.md, section 12.
 *
 * The Play Store's classes are obfuscated and renamed in every build, so none is named here. The classic token
 * uses the Play Core integrity service, whose binder protocol is public. The decode request is found in the Play
 * Store's dex by its path string ([DecodeEndpointLocator]), its body is built from the server's field numbers
 * ([DecodeRequest]) and parsed by the Play Store's own protobuf parser, and the request sender comes from the Play
 * Store's objects, or from a factory that its own dependency injection is asked to make ([FactoryProviderLocator]).
 * Whatever does not match returns [Status.UNAVAILABLE] and sends nothing.
 */
object PlayStoreIntegrityCheck {
    private const val TAG = LOG_TAG

    private const val NONCE_PREFIX = "testing-"
    private const val TOKEN_TIMEOUT_MS = 25_000L
    private const val DECODE_TIMEOUT_MS = 30_000L

    /** How far below the decode response's payload the response message is looked for. */
    private const val VERDICT_DEPTH = 3

    /** At most this many accounts are tried for the decode request. */
    private const val MAX_ACCOUNTS = 3

    private const val INTEGRITY_SERVICE_DESCRIPTOR = "com.google.android.play.core.integrity.protocol.IIntegrityService"
    private const val INTEGRITY_CALLBACK_DESCRIPTOR = "com.google.android.play.core.integrity.protocol.IIntegrityServiceCallback"

    /** The verdict list in the decode response, such as "[MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY]" or "[]". */
    private val verdictList = Regex("""\[(?:[A-Z_]+(?:, [A-Z_]+)*)?]""")

    enum class Status {
        /** The decode answered: [Result.labels] holds the verdict labels, which may be none. */
        OK,
        /** The Play Store build does not have the classes or fields this check needs. */
        UNAVAILABLE,
        /** A request did not answer in time. */
        TIMEOUT,
        /** A request failed: [Result.detail] says why. */
        FAILED,
    }

    class Result(val status: Status, val labels: List<String> = emptyList(), val detail: String? = null) {
        override fun toString(): String = when (status) {
            Status.OK -> "OK labels=$labels"
            else -> "$status ${detail.orEmpty()}"
        }
    }

    private class CheckFailure(val status: Status, message: String) : Exception(message)

    /** One check at a time: a second call waits for the first to finish. */
    private val singleFlight = ReentrantLock()

    /**
     * How long the nonce and token of a finished token request are still known as this check's: the Play Store's calls
     * that carry them can end after the token has come back.
     */
    private const val OWN_REQUEST_GRACE_MS = 60_000L

    /**
     * The nonces and tokens of this check's token requests, to tell them from the Play Store's own, each with the time
     * (elapsedRealtime) it is forgotten at. A request in flight is kept until it ends.
     */
    private val ownMarkers = ConcurrentHashMap<String, Long>()

    /**
     * Whether the integrity service call with [args] is this check's token request, or its response. IntegrityServiceHook
     * reports neither: they are PIB's own, not an app asking for Play Integrity. The request's Bundle is known by its
     * nonce, and the response's by its token; the random nonce alone tells it, even in a copy without the package name.
     */
    fun isOwnRequest(args: Array<Any?>): Boolean {
        if (ownMarkers.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        ownMarkers.entries.removeIf { it.value < now }
        return args.any { arg ->
            val bundle = arg as? Bundle ?: return@any false
            bundle.getByteArray("nonce")?.let { ownMarkers.containsKey(String(it)) } == true ||
                bundle.getString("token")?.let { ownMarkers.containsKey(it) } == true
        }
    }

    /** Runs one check and blocks until the verdict or a failure. Call it from a background thread. */
    fun run(): Result = singleFlight.withLock {
        runCatching {
            val app = PIBLoggerService.getCurrentApplication() ?: throw CheckFailure(Status.UNAVAILABLE, "no application in this process")
            val (token, nonce) = requestClassicToken(app)
            val labels = decodeLabels(app, token, nonce.removePrefix(NONCE_PREFIX))
            Log.i(TAG, "verdict labels=$labels")
            Result(Status.OK, labels = labels)
        }.getOrElse { failure ->
            val error = (failure as? InvocationTargetException)?.targetException ?: failure
            // The class names are for one Play Store build: the version tells whether that is the cause.
            val version = "Play Store versionCode ${vendingVersionCode()}"
            val result = when (error) {
                is CheckFailure -> Result(error.status, detail = "${error.message} ($version)")
                is Unsupported -> Result(Status.UNAVAILABLE, detail = "${error.message} ($version)")
                is TimeoutException -> Result(Status.TIMEOUT, detail = "no answer in time ($version)")
                else -> Result(Status.FAILED, detail = "$error ($version)")
            }
            Log.w(TAG, "check did not give a verdict: $result", error)
            result
        }
    }

    /** The classic token and the nonce it was requested with. */
    private fun requestClassicToken(context: Context): Pair<String, String> {
        val suffix = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = NONCE_PREFIX + Base64.encodeToString(suffix, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val response = CompletableFuture<Bundle>()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 2) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(INTEGRITY_CALLBACK_DESCRIPTOR)
                val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
                response.complete(bundle)
                return true
            }
        }.apply { attachInterface(null, INTEGRITY_CALLBACK_DESCRIPTOR) }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(INTEGRITY_SERVICE_DESCRIPTOR)
                    data.writeInt(1)
                    Bundle().apply {
                        putString("package.name", Constants.VENDING_PACKAGE_NAME)
                        putByteArray("nonce", nonce.toByteArray())
                        putInt("playcore.integrity.version.major", 1)
                        putInt("playcore.integrity.version.minor", 6)
                        putInt("playcore.integrity.version.patch", 0)
                        val event = Bundle().apply {
                            putInt("event_type", 3)
                            putLong("event_timestamp", System.currentTimeMillis())
                        }
                        putParcelableArrayList("event_timestamps", arrayListOf(event))
                    }.writeToParcel(data, 0)
                    data.writeStrongBinder(callback)
                    // The service is in this process, so this runs its code here, on the main thread: what it throws
                    // would end the Play Store.
                    service.transact(2, data, null, IBinder.FLAG_ONEWAY)
                } catch (e: Throwable) {
                    response.completeExceptionally(e)
                } finally {
                    data.recycle()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(Constants.PLAY_INTEGRITY_BIND_ACTION).setPackage(Constants.VENDING_PACKAGE_NAME)
        ownMarkers[nonce] = Long.MAX_VALUE
        var token: String? = null
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw CheckFailure(Status.UNAVAILABLE, "the Play Store integrity service did not bind")
            }
            val bundle = try {
                response.get(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                throw CheckFailure(Status.FAILED, "the token request threw ${e.cause}")
            }
            val error = bundle.getInt("error")
            token = bundle.getString("token")
            if (error != 0 || token == null) {
                throw CheckFailure(Status.FAILED, "no classic token (error $error)${interventionHint()}")
            }
            return token to nonce
        } finally {
            // The system keeps the connection until it is unbound, even when the bind failed.
            runCatching { context.unbindService(connection) }
            val until = SystemClock.elapsedRealtime() + OWN_REQUEST_GRACE_MS
            ownMarkers[nonce] = until
            token?.let { ownMarkers[it] = until }
        }
    }

    /**
     * PIB rewrites the Play Store's responses when intervention and response rewriting are both on (IntegrityServiceHook).
     * Then the classic token request gets a synthetic error and no token comes back. Named in the failure, so the user
     * knows which setting to change.
     */
    private fun interventionHint(): String {
        val policy = PIBLoggerService.resolvePolicy(Constants.VENDING_PACKAGE_NAME)
        return if (policy.interventionEnabled && policy.rewriteResponse) {
            "; PIB gives the Play Store synthetic errors, turn them off for com.android.vending for the check to work"
        } else {
            ""
        }
    }

    /** The verdict labels from fdfe/decodeintegritytoken, sent through the Play Store's request sender. */
    private fun decodeLabels(app: Application, token: String, deviceId: String): List<String> {
        val classLoader = app.classLoader
        val endpoint = endpointFor(app)
        val types = (listOf(endpoint.bodyType) + endpoint.listenerTypes).map { classLoader.loadClass(it) }
        val (bodyType, firstListener, secondListener) = types
        val (successListener, errorListener) = when {
            isErrorListener(secondListener) && !isErrorListener(firstListener) -> firstListener to secondListener
            isErrorListener(firstListener) && !isErrorListener(secondListener) -> secondListener to firstListener
            else -> throw Unsupported("the request's listeners are not a success and an error listener ($endpoint)")
        }
        val sendMethod = classLoader.loadClass(endpoint.senderClass)
            .getDeclaredMethod(endpoint.senderMethod, *types.toTypedArray()).apply { isAccessible = true }
        val device = DecodeRequest.Device(Build.FINGERPRINT, Build.BRAND, Build.DEVICE, Build.MODEL, deviceId)
        val body = RequestMessage.build(bodyType, token, device)
        // The accounts are tried in turn, after an error or no answer: the first may be one the Play Store does not sign in with.
        val accounts = accountNames(app).ifEmpty { listOf(null) }.take(MAX_ACCOUNTS)
        val senders = RequestSenders.find(app, endpoint, accounts) { providersFor(app, endpoint) }

        var failure: Throwable? = null
        for (account in accounts) {
            val sender = senders(account) ?: continue
            val response = CompletableFuture<Any?>()
            val onSuccess = listener(successListener) { response.complete(it) }
            val onError = listener(errorListener) { response.completeExceptionally(CheckFailure(Status.FAILED, "decode error: $it")) }
            // In the order the send method takes them.
            val listeners = if (successListener === firstListener) arrayOf(onSuccess, onError) else arrayOf(onError, onSuccess)
            sendMethod.invoke(sender, body, *listeners)
            val payload = try {
                response.get(DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                failure = e.cause ?: e
                continue
            } catch (e: TimeoutException) {
                failure = e
                continue
            }
            val text = payload?.let(::verdictListIn) ?: throw CheckFailure(Status.FAILED, "no verdict list in the decode response")
            return PlayStoreVerdict.parseLabels(text)
        }
        throw failure ?: Unsupported("no request sender for any account ($endpoint)")
    }

    /**
     * The verdict list in the decode response [payload]. The response message answers with one of several messages, in a
     * oneof: a field of type Object holds the answer and an int field its field number, 1 for the labels message, whose
     * string is the list. The other answers are not a verdict. See docs/playstore-checker-di-chain.md, section 10.
     */
    private fun verdictListIn(payload: Any): String? {
        var list: String? = null
        ObjectGraph.reachableFrom(payload, VERDICT_DEPTH) { message ->
            list = oneofLabels(message)
            list != null
        }
        return list
    }

    /** The verdict list if [message] holds the labels message in a oneof whose case is 1. */
    private fun oneofLabels(message: Any): String? {
        val fields = instanceFields(message.javaClass)
        if (fields.none { it.type == Int::class.javaPrimitiveType && it.getInt(message) == 1 }) return null
        return fields.asSequence()
            .filter { it.type == Any::class.java }
            .mapNotNull { it.get(message) }
            .flatMap { answer -> instanceFields(answer.javaClass).asSequence().filter { it.type == String::class.java }.map { it.get(answer) } }
            .filterIsInstance<String>()
            .firstOrNull { verdictList.matches(it) }
    }

    /** The non-static fields declared by [type] that can be read. */
    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && runCatching { it.isAccessible = true }.isSuccess }

    /**
     * What is read from the Play Store's dex files is kept while the Play Store build stays the same. So is an
     * [Unsupported] failure: the same dex files give the same answer, and reading them again costs seconds each check.
     */
    private class PerBuild<T> {
        private var held: Pair<Pair<Long, Long>, kotlin.Result<T>>? = null

        fun get(build: Pair<Long, Long>, make: () -> T): T {
            held?.let { (at, value) -> if (at == build) return value.getOrThrow() }
            val made = try {
                kotlin.Result.success(make())
            } catch (e: Unsupported) {
                kotlin.Result.failure(e)
            }
            held = build to made
            return made.getOrThrow()
        }
    }

    private val endpoints = PerBuild<DecodeEndpoint>()
    private val factoryProviders = PerBuild<List<FactoryProvider>>()

    /** Where the decode request is in this Play Store build: read from its dex files, about a second the first time. */
    private fun endpointFor(app: Application): DecodeEndpoint = endpoints.get(buildOf(app)) {
        val started = SystemClock.elapsedRealtime()
        val endpoint = try {
            DecodeEndpointLocator.locate { ApkDex.dexes(apkPaths(app)) }
        } catch (e: EndpointNotFound) {
            throw Unsupported("the decode request was not found in the Play Store: ${e.message}")
        }
        Log.i(TAG, "decode endpoint $endpoint (found in ${SystemClock.elapsedRealtime() - started} ms)")
        endpoint
    }

    /** The providers that make the request factory: only read when the Play Store has no factory yet. */
    private fun providersFor(app: Application, endpoint: DecodeEndpoint): List<FactoryProvider> = factoryProviders.get(buildOf(app)) {
        val started = SystemClock.elapsedRealtime()
        val providers = FactoryProviderLocator.locate(endpoint.factories) { ApkDex.dexes(apkPaths(app)) }
        Log.i(TAG, "request factory providers $providers (found in ${SystemClock.elapsedRealtime() - started} ms)")
        providers
    }

    /** The Play Store build: its version code and the time it was installed. */
    private fun buildOf(app: Application): Pair<Long, Long> {
        val info = app.packageManager.getPackageInfo(Constants.VENDING_PACKAGE_NAME, 0)
        return info.longVersionCode to info.lastUpdateTime
    }

    private fun apkPaths(app: Application): List<String> =
        listOf(app.applicationInfo.sourceDir) + app.applicationInfo.splitSourceDirs.orEmpty()

    /** The names of the Google accounts on the device (the Play Store signs the request in with one), in the system's order. */
    private fun accountNames(context: Context): List<String> = runCatching {
        AccountManager.get(context).getAccountsByType("com.google").map { it.name }
    }.getOrDefault(emptyList())

    /** A Volley error listener: its one method takes the error. */
    private fun isErrorListener(type: Class<*>): Boolean =
        type.isInterface && type.methods.any { m -> m.parameterTypes.singleOrNull()?.let { Throwable::class.java.isAssignableFrom(it) } == true }

    /** An instance of the one-method listener interface [type] that passes its argument to [onCall]. */
    private fun listener(type: Class<*>, onCall: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PIB listener"
                else -> {
                    onCall(args?.firstOrNull())
                    null
                }
            }
        }

    private fun vendingVersionCode(): String = runCatching {
        PIBLoggerService.getCurrentApplication()?.packageManager?.getPackageInfo(Constants.VENDING_PACKAGE_NAME, 0)?.longVersionCode?.toString()
    }.getOrNull() ?: "unknown"
}
