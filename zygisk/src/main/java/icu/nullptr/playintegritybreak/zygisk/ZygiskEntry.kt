package icu.nullptr.playintegritybreak.zygisk

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import com.v7878.unsafe.Reflection
import com.v7878.zygisk.ZygoteLoader
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.core.Bootstrap
import icu.nullptr.playintegritybreak.core.HookParam
import icu.nullptr.playintegritybreak.core.MethodHook
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PIB-ZygiskEntry"

/**
 * Entry point called by ZygoteLoader right after a Play Store (or Play Integrity API Checker)
 * process is specialized (module.prop "entrypoint"). The app is not bound yet at that point, so the
 * hooks are installed as soon as its Application has been created.
 */
object ZygiskEntry {
    private val started = AtomicBoolean(false)
    private val targetPackages = setOf(Constants.VENDING_PACKAGE_NAME, Constants.CHECKER_PACKAGE_NAME)

    /** Called before specialization, still with zygote privileges. Nothing to do. */
    @JvmStatic
    fun premain() {
    }

    @JvmStatic
    fun main() {
        // The payload is injected in every Play Store process, the Integrity service runs in the main one.
        // The checker app gets it too, for the integrity monitor.
        if (ZygoteLoader.getProcessName() !in targetPackages) return

        // Several bootstrap points, the first one that fires wins:
        // - Vector deoptimizes LoadedApk.makeApplication(Inner) and Instrumentation.newApplication
        //   right after we hook them, resetting their entry points and dropping our hooks;
        // - without Vector, boot image code may call Application.attach without going through
        //   its entry point.
        runCatching {
            hookAll(declaredMethods(Application::class.java, "attach"), object : MethodHook() {
                override fun after(param: HookParam) = start(param.thisObject as Application)
            })
            hookAll(declaredMethods(Instrumentation::class.java, "callApplicationOnCreate"), object : MethodHook() {
                override fun before(param: HookParam) = start(param.args[0] as Application)
            })
            hookAll(
                declaredMethods(Class.forName("android.app.LoadedApk"), "makeApplication", "makeApplicationInner"),
                object : MethodHook() {
                    override fun after(param: HookParam) {
                        (param.result as? Application)?.let(::start)
                    }
                },
            )
        }.onFailure {
            Log.e(TAG, "Failed to set up Zygisk entry", it)
        }
    }

    private fun declaredMethods(clazz: Class<*>, vararg names: String): List<Method> =
        Reflection.getHiddenExecutables(clazz).filterIsInstance<Method>().filter { it.name in names }

    private fun hookAll(methods: List<Method>, hook: MethodHook) {
        if (methods.isEmpty()) Log.w(TAG, "Bootstrap method not found")
        methods.forEach { method ->
            runCatching { VMToolsHookBackend.hook(method, hook) }
                .onFailure { Log.e(TAG, "Failed to hook $method", it) }
        }
    }

    private fun start(app: Application) {
        if (started.get() || app.packageName !in targetPackages) return
        if (!started.compareAndSet(false, true)) return
        if (app.packageName == Constants.CHECKER_PACKAGE_NAME) {
            Bootstrap.startChecker(VMToolsHookBackend, app.classLoader)
        } else {
            Bootstrap.start(VMToolsHookBackend, app.classLoader)
        }
    }
}
