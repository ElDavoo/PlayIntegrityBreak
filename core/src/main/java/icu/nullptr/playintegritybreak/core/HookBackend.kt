package icu.nullptr.playintegritybreak.core

import android.util.Log
import java.lang.reflect.Method

/**
 * State of a single hooked invocation, shared between [MethodHook.before] and [MethodHook.after].
 *
 * Mirrors Xposed semantics: assigning [result] or [throwable] inside [MethodHook.before]
 * skips the original method.
 */
abstract class HookParam {
    abstract val method: Method
    abstract val thisObject: Any?
    abstract val args: Array<Any?>
    abstract var result: Any?
    abstract var throwable: Throwable?
}

abstract class MethodHook {
    open fun before(param: HookParam) {}
    open fun after(param: HookParam) {}
}

/** A hooking framework that can run PIB's hooks (Xposed, Zygisk + LSPlant, ...). */
interface HookBackend {
    val name: String
    fun hook(method: Method, hook: MethodHook)
    fun log(line: String)
}

object Backend {
    /**
     * JVM-wide marker so that two backends loaded in the same process (each with its own
     * copy of this class in a separate classloader) never install hooks twice.
     */
    private const val CLAIM_PROPERTY = "pib.hooks.backend"

    @Volatile
    lateinit var current: HookBackend
        private set

    /**
     * Claims the current process for [backend].
     * @return the name of the backend that already owns the process, or null if [backend] won.
     */
    fun claim(backend: HookBackend): String? {
        synchronized(System.getProperties()) {
            val owner = System.getProperty(CLAIM_PROPERTY)
            if (owner != null) return owner
            System.setProperty(CLAIM_PROPERTY, backend.name)
        }
        current = backend
        return null
    }

    val name: String
        get() = if (::current.isInitialized) current.name else "unknown"

    fun log(line: String) {
        if (::current.isInitialized) current.log(line) else Log.i("PIB", line)
    }
}
