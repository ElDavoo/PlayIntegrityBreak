package icu.nullptr.playintegritybreak.zygisk

import android.app.Application
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
 * Entry point called by ZygoteLoader right after a Play Store process is specialized
 * (module.prop "entrypoint"). The app is not bound yet at that point, so the hooks are installed
 * as soon as its Application has been created.
 */
object ZygiskEntry {
    private val started = AtomicBoolean(false)

    /** Called before specialization, still with zygote privileges. Nothing to do. */
    @JvmStatic
    fun premain() {
    }

    @JvmStatic
    fun main() {
        // The payload is injected in every Play Store process, the Integrity service runs in the main one.
        if (ZygoteLoader.getProcessName() != Constants.VENDING_PACKAGE_NAME) return

        runCatching {
            // makeApplication(Inner) is too large to be inlined into handleBindApplication, and,
            // unlike handleBindApplication, Xposed frameworks don't hook it.
            val makeApplication = Reflection.getHiddenExecutables(Class.forName("android.app.LoadedApk"))
                .filterIsInstance<Method>()
                .filter {
                    (it.name == "makeApplication" || it.name == "makeApplicationInner") &&
                        it.returnType == Application::class.java
                }
            check(makeApplication.isNotEmpty()) { "LoadedApk.makeApplication not found" }

            val hook = object : MethodHook() {
                override fun after(param: HookParam) {
                    val app = param.result as? Application ?: return
                    if (app.packageName != Constants.VENDING_PACKAGE_NAME) return
                    if (!started.compareAndSet(false, true)) return
                    Bootstrap.start(VMToolsHookBackend, app.classLoader)
                }
            }
            makeApplication.forEach { VMToolsHookBackend.hook(it, hook) }
        }.onFailure {
            Log.e(TAG, "Failed to set up Zygisk entry", it)
        }
    }
}
