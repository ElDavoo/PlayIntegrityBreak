package icu.nullptr.playintegritybreak.zygisk

import android.util.Log
import icu.nullptr.playintegritybreak.core.HookBackend
import icu.nullptr.playintegritybreak.core.HookParam
import icu.nullptr.playintegritybreak.core.MethodHook
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object LSPlantHookBackend : HookBackend {
    override val name = "Zygisk"

    private val callbackMethod: Method = Hooker::class.java.getDeclaredMethod(
        "callback", Array<Any?>::class.java
    )

    override fun hook(method: Method, hook: MethodHook) {
        val hooker = Hooker(method, hook)
        val backup = LSPlantBridge.hook(method, hooker, callbackMethod)
            ?: throw IllegalStateException("LSPlant failed to hook $method")
        backup.isAccessible = true
        hooker.backup = backup
    }

    override fun log(line: String) {
        Log.i("PIB", line)
    }

    /** Called by LSPlant in place of the hooked method; mirrors Xposed's before/original/after flow. */
    class Hooker(private val method: Method, private val hook: MethodHook) {
        @Volatile
        lateinit var backup: Method

        private val isStatic = Modifier.isStatic(method.modifiers)

        // Invoked by LSPlant: public Object callback(Object[] args).
        fun callback(rawArgs: Array<Any?>): Any? {
            val thisObject = if (isStatic) null else rawArgs[0]
            val args = if (isStatic) rawArgs else rawArgs.copyOfRange(1, rawArgs.size)
            val param = Param(method, thisObject, args)

            runCatching { hook.before(param) }.onFailure { logHookError("before", it) }

            if (!param.returnEarly) {
                try {
                    param.result = backup.invoke(thisObject, *param.args)
                } catch (e: InvocationTargetException) {
                    param.throwable = e.targetException
                }
            }

            runCatching { hook.after(param) }.onFailure { logHookError("after", it) }

            param.throwable?.let { throw it }
            return param.result
        }

        private fun logHookError(stage: String, cause: Throwable) {
            Log.e("PIB", "Hook $stage failed for $method", cause)
        }
    }

    private class Param(
        override val method: Method,
        override val thisObject: Any?,
        override val args: Array<Any?>,
    ) : HookParam() {
        var returnEarly = false
            private set

        override var result: Any? = null
            set(value) {
                field = value
                throwable = null
                returnEarly = true
            }

        override var throwable: Throwable? = null
            set(value) {
                field = value
                if (value != null) {
                    // Setting either result or throwable short-circuits the original method.
                    returnEarly = true
                }
            }
    }
}
