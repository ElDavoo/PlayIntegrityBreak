package icu.nullptr.playintegritybreak.xposed

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

object IntegrityServiceHook {
    private const val TAG = "IntegrityServiceHook"
    private const val RESPONSE_SOURCE_SERVICE = "service-response"
    private const val RESPONSE_SOURCE_SYNTHETIC_PRE_SERVICE = "synthetic-pre-service"
    private const val RESPONSE_SOURCE_SHORT_CIRCUIT_NO_DELIVERY = "short-circuit-no-delivery"
    private const val SYNTHETIC_RESPONSE_DELAY_MIN_MS = 300L
    private const val SYNTHETIC_RESPONSE_DELAY_MAX_MS = 2_000L

    private val packageNamePattern =
        Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")

    private val targetClasses = listOf(
        "com.google.android.finsky.integrityservice.IntegrityService",
        "com.google.android.finsky.integrityservice.BackgroundIntegrityService",
        "com.google.android.finsky.expressintegrityservice.ExpressIntegrityService",
    )

    private val runtimeHookedClasses = ConcurrentHashMap.newKeySet<String>()
    private val syntheticDeliveryDepth = ThreadLocal<Int>()

    @Volatile
    private var hookedMethodCount = 0

    fun install(classLoader: ClassLoader) {
        hookedMethodCount = 0
        for (className in targetClasses) {
            runCatching {
                hookClassMethods(classLoader.loadClass(className))
            }.onFailure {
                logW("Skip hook for $className", it)
            }
        }
        logI("Hooked $hookedMethodCount methods")
    }

    private fun hookClassMethods(clazz: Class<*>) {
        clazz.declaredMethods.forEach { method ->
            hookMethod(method)
            hookedMethodCount++
        }
    }

    private fun hookBundleMethodsIfAny(clazz: Class<*>, reason: String) {
        val key = "${clazz.name}#$reason"
        if (!runtimeHookedClasses.add(key)) return

        var newlyHooked = 0
        clazz.declaredMethods.forEach { method ->
            if (!hasBundleParameter(method)) return@forEach
            hookMethod(method)
            hookedMethodCount++
            newlyHooked++
        }

        if (newlyHooked > 0) {
            logI("Runtime-hooked $newlyHooked Bundle methods in ${clazz.name}")
        }
    }

    private fun hasBundleParameter(method: Method): Boolean {
        return method.parameterTypes.any { it == Bundle::class.java }
    }

    private fun hookMethod(method: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: emptyArray()

                logBinderPayload("before", param.method, args)

                val callerPkg = extractCallerPackage(args)
                val playIntegrityVersion = extractPlayIntegrityVersion(args)
                val policy = PIBLoggerService.resolvePolicy(callerPkg)
                val looksLikeRequest = hasBundleAndCallback(args)
                val requestPayload = isIntegrityRequestPayload(args)

                if (policy.enabled && requestPayload && callerPkg != "unknown") {
                    PIBLoggerService.recordIntegrityRequest(
                        callerPkg = callerPkg,
                        playIntegrityVersionMajor = playIntegrityVersion?.major,
                        playIntegrityVersionMinor = playIntegrityVersion?.minor,
                        playIntegrityVersionPatch = playIntegrityVersion?.patch,
                    )
                }

                if (policy.enabled && policy.logRequest && looksLikeRequest && callerPkg != "unknown") {
                    logI("Integrity request intercepted from $callerPkg")
                }

                val shouldShortCircuit = policy.enabled && policy.rewriteResponse && requestPayload
                if (shouldShortCircuit) {
                    val deliveryEnabled = policy.deliverSyntheticResponse
                    val callback = if (deliveryEnabled) extractCallback(args) else null

                    val delivered = if (deliveryEnabled) {
                        if (callback == null) {
                            logW("Synthetic callback delivery is enabled, but no callback argument was found for $callerPkg")
                            false
                        } else {
                            deliverSyntheticBlockedResponse(
                                callback = callback,
                                errorCode = policy.rewriteErrorCode,
                                remediable = policy.rewriteRemediable,
                                applyDelay = policy.delaySyntheticResponseDelivery,
                                callerPkg = callerPkg,
                            )
                        }
                    } else {
                        false
                    }

                    if (deliveryEnabled && !delivered) {
                        logW("Synthetic callback delivery failed for $callerPkg, falling back to original service path")
                        return
                    }

                    val responseSource = if (deliveryEnabled) {
                        RESPONSE_SOURCE_SYNTHETIC_PRE_SERVICE
                    } else {
                        RESPONSE_SOURCE_SHORT_CIRCUIT_NO_DELIVERY
                    }

                    if (callerPkg != "unknown") {
                        PIBLoggerService.recordIntegrityResponse(
                            callerPkg = callerPkg,
                            playIntegrityVersionMajor = playIntegrityVersion?.major,
                            playIntegrityVersionMinor = playIntegrityVersion?.minor,
                            playIntegrityVersionPatch = playIntegrityVersion?.patch,
                            success = false,
                            errorCode = policy.rewriteErrorCode,
                            retriable = policy.rewriteRemediable,
                            source = responseSource,
                        )
                    }

                    val shouldLogSynthetic = callerPkg != "unknown" && policy.logResponse

                    if (shouldLogSynthetic) {
                        logResult(
                            callerPkg = callerPkg,
                            success = false,
                            errorCode = policy.rewriteErrorCode,
                            retriable = policy.rewriteRemediable,
                            source = responseSource,
                        )
                    }

                    if (!deliveryEnabled) {
                        logI("Short-circuited integrity request for $callerPkg without synthetic callback delivery")
                    }

                    param.result = defaultReturnValue(method.returnType)
                    return
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (isSyntheticDeliveryInProgress()) {
                    return
                }

                val result = param.result
                when (result) {
                    is IBinder -> hookBundleMethodsIfAny(result.javaClass, "binder-return")
                    is IInterface -> hookBundleMethodsIfAny(result.javaClass, "iinterface-return")
                }

                val args = param.args ?: emptyArray()
                logBinderPayload("after", param.method, args)
                val callerPkg = extractCallerPackage(args)
                val playIntegrityVersion = extractPlayIntegrityVersion(args)
                val outcome = extractOutcome(args)
                val policy = PIBLoggerService.resolvePolicy(callerPkg)

                if (outcome != null && callerPkg != "unknown" && policy.enabled) {
                    PIBLoggerService.recordIntegrityResponse(
                        callerPkg = callerPkg,
                        playIntegrityVersionMajor = playIntegrityVersion?.major,
                        playIntegrityVersionMinor = playIntegrityVersion?.minor,
                        playIntegrityVersionPatch = playIntegrityVersion?.patch,
                        success = outcome.success,
                        errorCode = outcome.errorCode,
                        retriable = outcome.retriable,
                        source = RESPONSE_SOURCE_SERVICE,
                    )
                }

                val shouldLogOutcome = outcome != null
                    && callerPkg != "unknown"
                    && policy.enabled
                    && policy.logResponse
                    && (!policy.errorOnly || !outcome.success)

                if (shouldLogOutcome) {
                    logResult(
                        callerPkg = callerPkg,
                        success = outcome.success,
                        errorCode = outcome.errorCode,
                        retriable = outcome.retriable,
                        source = RESPONSE_SOURCE_SERVICE,
                    )
                }
            }
        })
    }

    private fun logResult(
        callerPkg: String,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        if (success) {
            logI("Integrity response for $callerPkg: success [source=$source]")
            return
        }

        val errorText = errorCode?.toString() ?: "unknown"
        val retriableText = retriable?.toString() ?: "unknown"
        logI("Integrity response for $callerPkg: failed (error=$errorText, retriable=$retriableText) [source=$source]")
    }

    private fun extractOutcome(args: Array<Any?>): Outcome? {
        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach
            if (bundle.containsKey("token")) {
                return Outcome(success = true, errorCode = null, retriable = null)
            }
            if (bundle.containsKey("error")) {
                val retriable = if (bundle.containsKey("is.error.remediable")) {
                    bundle.getBoolean("is.error.remediable", false)
                } else {
                    null
                }
                return Outcome(success = false, errorCode = bundle.getInt("error"), retriable = retriable)
            }
        }
        return null
    }

    private fun extractPlayIntegrityVersion(args: Array<Any?>): PlayIntegrityVersion? {
        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach
            val major = bundle.getIntOrNull("playcore.integrity.version.major")
            val minor = bundle.getIntOrNull("playcore.integrity.version.minor")
            val patch = bundle.getIntOrNull("playcore.integrity.version.patch")
            if (major != null || minor != null || patch != null) {
                return PlayIntegrityVersion(major = major, minor = minor, patch = patch)
            }
        }
        return null
    }

    private fun logBinderPayload(stage: String, member: Member, args: Array<Any?>) {
        val bundleArgs = args.withIndex().mapNotNull { (index, arg) ->
            (arg as? Bundle)?.let { index to it }
        }

        if (bundleArgs.isEmpty()) return

        XposedBridge.log(
            "[PIB] $TAG binder payload dump [$stage] ${member.declaringClass.name}#${member.name} bundleArgs=${bundleArgs.size}"
        )

        bundleArgs.forEach { (index, bundle) ->
            dumpBundle(bundle, "arg[$index]", 1)
        }
    }

    private fun dumpBundle(bundle: Bundle, label: String, depth: Int) {
        val indent = "  ".repeat(depth)
        val keys = runCatching { bundle.keySet().toList().sorted() }.getOrDefault(emptyList())

        XposedBridge.log("[PIB] $TAG ${indent}$label keyCount=${keys.size}")
        if (keys.isEmpty()) {
            XposedBridge.log("[PIB] $TAG ${indent}$label <empty>")
            return
        }

        keys.forEach { key ->
            val value = runCatching { bundle.get(key) }.getOrNull()
            when (value) {
                is Bundle -> {
                    XposedBridge.log(
                        "[PIB] $TAG ${indent}  $key = Bundle(size=${runCatching { value.keySet().size }.getOrDefault(0)})"
                    )
                    dumpBundle(value, key, depth + 2)
                }

                is Array<*> -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentDeepToString()} (${value.javaClass.name})"
                )

                is IntArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is LongArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is DoubleArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is FloatArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is BooleanArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is ShortArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is ByteArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                is CharArray -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${value.contentToString()} (${value.javaClass.name})"
                )

                else -> XposedBridge.log(
                    "[PIB] $TAG ${indent}  $key = ${describeBundleValue(value)}"
                )
            }
        }
    }

    private fun describeBundleValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is String -> "\"$value\" (${value.javaClass.name})"
            is CharSequence -> "\"$value\" (${value.javaClass.name})"
            is Number, is Boolean, is Char -> "$value (${value.javaClass.name})"
            else -> "$value (${value.javaClass.name})"
        }
    }

    private fun extractCallerPackage(args: Array<Any?>): String {
        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach
            val pkg = bundle.getString("package.name")
                ?: bundle.getString("packageName")
                ?: bundle.getString("package_name")
            if (!pkg.isNullOrBlank()) {
                return pkg
            }

            // Fallback: scan all string values in bundle for a package-like token.
            runCatching {
                bundle.keySet().forEach { key ->
                    val value = bundle.get(key) as? String ?: return@forEach
                    if (packageNamePattern.matcher(value).matches()) {
                        return value
                    }
                }
            }
        }

        args.forEach { arg ->
            val value = arg as? String ?: return@forEach
            if (packageNamePattern.matcher(value).matches()) {
                return value
            }
        }

        return "unknown"
    }

    private fun hasBundleAndCallback(args: Array<Any?>): Boolean {
        val hasBundle = args.any { it is Bundle }
        val hasCallback = args.any { it != null && isIntegrityCallbackLike(it) }
        return hasBundle && hasCallback
    }

    private fun isIntegrityRequestPayload(args: Array<Any?>): Boolean {
        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach

            val hasPkg = bundle.containsKey("package.name")
                || bundle.containsKey("packageName")
                || bundle.containsKey("package_name")
            val hasNonce = bundle.containsKey("nonce")
            val hasNonceLikeKey = runCatching {
                bundle.keySet().any { key -> key.contains("nonce", ignoreCase = true) }
            }.getOrDefault(false)
            val hasPackageLikeString = runCatching {
                bundle.keySet().any { key ->
                    val value = bundle.get(key) as? String ?: return@any false
                    packageNamePattern.matcher(value).matches()
                }
            }.getOrDefault(false)
            val hasResponse = bundle.containsKey("token") || bundle.containsKey("error")

            // Keep this permissive: key names can vary across Play Store versions/builds.
            if (!hasResponse && (hasNonce || hasNonceLikeKey || hasPkg || hasPackageLikeString)) {
                return true
            }
        }
        return false
    }

    private fun deliverSyntheticBlockedResponse(
        callback: Any,
        errorCode: Int,
        remediable: Boolean,
        applyDelay: Boolean,
        callerPkg: String,
    ): Boolean {
        val callbackMethod = findCallbackBundleMethod(callback) ?: return false
        val syntheticResponse = Bundle().apply {
            putInt("error", errorCode)
            putBoolean("is.error.remediable", remediable)
        }

        if (applyDelay) {
            applyRandomSyntheticResponseDelay(callerPkg)
        }

        return runCatching {
            beginSyntheticDelivery()
            try {
                callbackMethod.isAccessible = true
                callbackMethod.invoke(callback, syntheticResponse)
            } finally {
                endSyntheticDelivery()
            }
        }.onFailure {
            logW("Failed to deliver synthetic callback response", it)
        }.isSuccess
    }

    private fun applyRandomSyntheticResponseDelay(callerPkg: String) {
        val delayMs = ThreadLocalRandom.current()
            .nextLong(SYNTHETIC_RESPONSE_DELAY_MIN_MS, SYNTHETIC_RESPONSE_DELAY_MAX_MS + 1)

        try {
            Thread.sleep(delayMs)
        } catch (cause: InterruptedException) {
            Thread.currentThread().interrupt()
            logW("Synthetic response delay interrupted for $callerPkg", cause)
        }
    }

    private fun extractCallback(args: Array<Any?>): Any? {
        args.forEach { arg ->
            if (arg == null) return@forEach
            if (hasIntegrityCallbackDescriptor(arg)) {
                return arg
            }
        }

        args.forEach { arg ->
            if (arg != null && isIntegrityCallbackLike(arg)) {
                return arg
            }
        }
        return null
    }

    private fun hasIntegrityCallbackDescriptor(value: Any): Boolean {
        val descriptor = extractDescriptor(value)
        if (descriptor != null && descriptor.contains("IIntegrityServiceCallback")) {
            return true
        }

        if (value.javaClass.name.contains("IIntegrityServiceCallback")) {
            return true
        }

        return value.javaClass.interfaces.any { it.name.contains("IIntegrityServiceCallback") }
    }

    private fun findCallbackBundleMethod(callback: Any): Method? {
        callback.javaClass.methods.firstOrNull {
            isBundleCallbackMethod(it) && it.returnType == Void.TYPE
        }?.let { return it }

        callback.javaClass.declaredMethods.firstOrNull {
            isBundleCallbackMethod(it) && it.returnType == Void.TYPE
        }?.let { return it }

        callback.javaClass.methods.firstOrNull(::isBundleCallbackMethod)?.let { return it }
        callback.javaClass.declaredMethods.firstOrNull(::isBundleCallbackMethod)?.let { return it }

        return null
    }

    private fun isBundleCallbackMethod(method: Method): Boolean {
        val params = method.parameterTypes
        return !Modifier.isStatic(method.modifiers)
            && params.size == 1
            && params[0] == Bundle::class.java
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }

    private fun beginSyntheticDelivery() {
        val currentDepth = syntheticDeliveryDepth.get() ?: 0
        syntheticDeliveryDepth.set(currentDepth + 1)
    }

    private fun endSyntheticDelivery() {
        val currentDepth = syntheticDeliveryDepth.get() ?: 0
        if (currentDepth <= 1) {
            syntheticDeliveryDepth.remove()
            return
        }
        syntheticDeliveryDepth.set(currentDepth - 1)
    }

    private fun isSyntheticDeliveryInProgress(): Boolean {
        return (syntheticDeliveryDepth.get() ?: 0) > 0
    }

    private fun isIntegrityCallbackLike(value: Any): Boolean {
        val descriptor = extractDescriptor(value)
        if (descriptor != null && descriptor.contains("IIntegrityServiceCallback")) {
            return true
        }

        if (value.javaClass.methods.any { method ->
                val params = method.parameterTypes
                params.size == 1 && params[0] == Bundle::class.java
            }) {
            return true
        }

        return value.javaClass.declaredMethods.any { method ->
            val params = method.parameterTypes
            params.size == 1 && params[0] == Bundle::class.java
        }
    }

    private fun extractDescriptor(callbackLike: Any): String? {
        return runCatching {
            var clazz: Class<*>? = callbackLike.javaClass
            while (clazz != null && clazz != Any::class.java) {
                try {
                    val field = clazz.getDeclaredField("mDescriptor")
                    field.isAccessible = true
                    val value = field.get(callbackLike)
                    if (value is String) {
                        return value
                    }
                } catch (_: NoSuchFieldException) {
                }
                clazz = clazz.superclass
            }
            null
        }.getOrNull()
    }

    private fun logI(message: String) = logI(TAG, message)

    private fun logW(message: String, cause: Throwable? = null) = logW(TAG, message, cause)

    private data class Outcome(
        val success: Boolean,
        val errorCode: Int?,
        val retriable: Boolean?,
    )

    private data class PlayIntegrityVersion(
        val major: Int?,
        val minor: Int?,
        val patch: Int?,
    )

    private fun Bundle.getIntOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        return getInt(key)
    }

}
