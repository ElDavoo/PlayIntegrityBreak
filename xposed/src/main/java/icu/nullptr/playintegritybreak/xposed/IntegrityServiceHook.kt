package icu.nullptr.playintegritybreak.xposed

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object IntegrityServiceHook {
    private const val TAG = "IntegrityServiceHook"

    private val packageNamePattern =
        Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")

    private val targetClasses = listOf(
        "com.google.android.finsky.integrityservice.IntegrityService",
        "com.google.android.finsky.integrityservice.BackgroundIntegrityService",
        "com.google.android.finsky.expressintegrityservice.ExpressIntegrityService",
    )

    private val runtimeHookedClasses = ConcurrentHashMap.newKeySet<String>()
    private val blockedCallbackRewrites = ConcurrentHashMap<String, RewriteSpec>()

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

                val callerPkg = extractCallerPackage(args)
                val policy = PIBLoggerService.resolvePolicy(callerPkg)
                val looksLikeRequest = hasBundleAndCallback(args)
                val requestPayload = isIntegrityRequestPayload(args)
                val responsePayload = hasResponsePayload(args)
                val callbackRewrite = extractRememberedBlockedCallback(args)

                val policyRewrite = if (policy.rewriteResponse) {
                    RewriteSpec(
                        packageName = callerPkg,
                        errorCode = policy.rewriteErrorCode,
                        remediable = policy.rewriteRemediable,
                    )
                } else {
                    null
                }

                if (policy.enabled && policy.logRequest && looksLikeRequest && callerPkg != "unknown") {
                    logI("Request from $callerPkg")
                }

                if (requestPayload) {
                    policyRewrite?.let { rememberBlockedCallback(args, it) }
                }

                val rewriteSpec = callbackRewrite ?: policyRewrite
                val shouldRewrite = !requestPayload && responsePayload && rewriteSpec != null

                if (shouldRewrite && rewriteResponseToBlockedError(args, rewriteSpec.errorCode, rewriteSpec.remediable)) {
                    val pkgForLog = if (callerPkg == "unknown") rewriteSpec.packageName else callerPkg
                    logResult(pkgForLog, false, rewriteSpec.errorCode, rewriteSpec.remediable, "blocked-rewrite-pre")
                    clearRememberedBlockedCallback(args)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result
                when (result) {
                    is IBinder -> hookBundleMethodsIfAny(result.javaClass, "binder-return")
                    is IInterface -> hookBundleMethodsIfAny(result.javaClass, "iinterface-return")
                }

                val args = param.args ?: emptyArray()
                val callerPkg = extractCallerPackage(args)
                val outcome = extractOutcome(args)
                val policy = PIBLoggerService.resolvePolicy(callerPkg)
                val shouldLogOutcome = outcome != null
                    && callerPkg != "unknown"
                    && policy.enabled
                    && policy.logResponse
                    && (!policy.errorOnly || !outcome.success)

                if (shouldLogOutcome) {
                    logResult(callerPkg, outcome.success, outcome.errorCode, outcome.retriable, "forwarded")
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
            logI("Result for $callerPkg: success ($source)")
            return
        }

        val errorText = errorCode?.toString() ?: "unknown"
        val retriableText = retriable?.toString() ?: "unknown"
        logI("Result for $callerPkg: failed (error=$errorText, retriable=$retriableText) ($source)")
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

    private fun extractCallerPackage(args: Array<Any?>): String {
        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach
            val pkg = bundle.getString("package.name")
                ?: bundle.getString("packageName")
                ?: bundle.getString("package_name")
            if (!pkg.isNullOrBlank()) {
                return pkg
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
            val hasResponse = bundle.containsKey("token") || bundle.containsKey("error")

            if (hasPkg && hasNonce && !hasResponse) {
                return true
            }
        }
        return false
    }

    private fun hasResponsePayload(args: Array<Any?>): Boolean {
        return args.any {
            val bundle = it as? Bundle ?: return@any false
            bundle.containsKey("token") || bundle.containsKey("error")
        }
    }

    private fun rememberBlockedCallback(args: Array<Any?>, rewriteSpec: RewriteSpec) {
        extractCallbackKey(args)?.let { blockedCallbackRewrites[it] = rewriteSpec }
    }

    private fun extractRememberedBlockedCallback(args: Array<Any?>): RewriteSpec? {
        val key = extractCallbackKey(args) ?: return null
        return blockedCallbackRewrites[key]
    }

    private fun clearRememberedBlockedCallback(args: Array<Any?>) {
        extractCallbackKey(args)?.let(blockedCallbackRewrites::remove)
    }

    private fun extractCallbackKey(args: Array<Any?>): String? {
        args.forEach { arg ->
            if (arg == null || !isIntegrityCallbackLike(arg)) return@forEach
            val descriptor = extractDescriptor(arg) ?: "unknown"
            val remote = extractFieldValue(arg, "mRemote")
            val keyObj = remote ?: arg
            return "$descriptor@${System.identityHashCode(keyObj)}"
        }
        return null
    }

    private fun rewriteResponseToBlockedError(args: Array<Any?>, errorCode: Int, remediable: Boolean): Boolean {
        var changed = false

        args.forEach { arg ->
            val bundle = arg as? Bundle ?: return@forEach
            val hasToken = bundle.containsKey("token")
            val hasError = bundle.containsKey("error")
            if (!hasToken && !hasError) return@forEach

            bundle.remove("token")
            bundle.remove("request.token.sid")
            bundle.putInt("error", errorCode)
            bundle.putBoolean("is.error.remediable", remediable)
            changed = true
        }

        return changed
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

    private fun extractFieldValue(target: Any, fieldName: String): Any? {
        return runCatching {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null && clazz != Any::class.java) {
                try {
                    val field: Field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    return field.get(target)
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

    private data class RewriteSpec(
        val packageName: String,
        val errorCode: Int,
        val remediable: Boolean,
    )
}
