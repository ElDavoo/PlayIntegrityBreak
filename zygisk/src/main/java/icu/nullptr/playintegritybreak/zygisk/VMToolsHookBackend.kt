package icu.nullptr.playintegritybreak.zygisk

import android.os.Build
import android.util.Log
import com.v7878.unsafe.ArtMethodUtils
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.Hooks
import icu.nullptr.playintegritybreak.core.HookBackend
import icu.nullptr.playintegritybreak.core.HookParam
import icu.nullptr.playintegritybreak.core.MethodHook
import java.lang.invoke.MethodHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Hooks with AndroidVMTools, the pure-Java ART hooking library HMA-OSS uses for its Zygisk build.
 * It only swaps ArtMethod entry points, so unlike LSPlant/Dobby it does not patch libart and
 * can live in the same process as an Xposed framework (Vector/LSPosed).
 */
object VMToolsHookBackend : HookBackend {
    override val name = "Zygisk"

    override fun hook(method: Method, hook: MethodHook) {
        val hooker = Hooker(method, hook)
        val addresses = Hooks.hook(method, Hooks.EntryPointType.DIRECT, hooker::transform, Hooks.EntryPointType.DIRECT)
        hooker.hookedEntryPoint = addresses.first
        hooker.originalEntryPoint = addresses.second
    }

    override fun log(line: String) {
        Log.i("PIB", line)
    }

    private class Hooker(private val method: Method, private val hook: MethodHook) {
        private val isStatic = Modifier.isStatic(method.modifiers)

        // Used to call the original method below Android 13, see invokeOriginalLegacy.
        @Volatile
        var hookedEntryPoint = 0L

        @Volatile
        var originalEntryPoint = 0L

        /** Replaces [method]: [frame] holds the receiver (if any), the arguments and the return slot. */
        fun transform(original: MethodHandle, frame: EmulatedStackFrame) {
            val accessor = frame.accessor()
            val frameArgs = Array(accessor.argCount) { accessor.getValue(it) }
            val firstArg = if (isStatic) 0 else 1
            val param = Param(
                method = method,
                thisObject = if (isStatic) null else frameArgs[0],
                args = frameArgs.copyOfRange(firstArg, frameArgs.size),
            )

            runCatching { hook.before(param) }.onFailure { logHookError("before", it) }

            if (!param.returnEarly) {
                // Hooks may have replaced arguments in place.
                param.args.forEachIndexed { i, arg -> accessor.setValue(firstArg + i, arg) }
                try {
                    param.result = invokeOriginal(original, frame, param)
                } catch (e: Throwable) {
                    param.throwable = e
                }
            }

            runCatching { hook.after(param) }.onFailure { logHookError("after", it) }

            param.throwable?.let { throw it }
            accessor.setValue(RETURN_VALUE_IDX, param.result)
        }

        private fun invokeOriginal(original: MethodHandle, frame: EmulatedStackFrame, param: Param): Any? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return invokeOriginalLegacy(param)
            }
            Transformers.invokeExactNoChecks(original, frame)
            return frame.accessor().getValue(RETURN_VALUE_IDX)
        }

        /**
         * Below Android 13, calling [MethodHandle] `original` re-enters the hook, so (like HMA-OSS)
         * temporarily point the method back at its original code and call it through reflection.
         * Calls made by other threads in that window skip the hook.
         */
        private fun invokeOriginalLegacy(param: Param): Any? {
            ArtMethodUtils.setExecutableEntryPoint(method, originalEntryPoint)
            try {
                method.isAccessible = true
                return method.invoke(param.thisObject, *param.args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            } finally {
                ArtMethodUtils.setExecutableEntryPoint(method, hookedEntryPoint)
            }
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
