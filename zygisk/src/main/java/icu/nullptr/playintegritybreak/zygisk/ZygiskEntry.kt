package icu.nullptr.playintegritybreak.zygisk

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import icu.nullptr.playintegritybreak.core.Bootstrap
import icu.nullptr.playintegritybreak.core.HookParam
import icu.nullptr.playintegritybreak.core.MethodHook
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PIB-ZygiskEntry"

/**
 * Entry point called by the Zygisk module right after the Play Store process is specialized.
 * The app is not bound yet at that point, so the hooks are installed once its Application exists.
 */
object ZygiskEntry {
    private val started = AtomicBoolean(false)

    @JvmStatic
    fun main() {
        runCatching {
            val callApplicationOnCreate = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            LSPlantHookBackend.hook(callApplicationOnCreate, object : MethodHook() {
                override fun before(param: HookParam) {
                    val app = param.args[0] as? Application ?: return
                    if (!started.compareAndSet(false, true)) return
                    Bootstrap.start(LSPlantHookBackend, app.classLoader)
                }
            })

            // handleBindApplication may have inlined the tiny callApplicationOnCreate.
            Class.forName("android.app.ActivityThread").declaredMethods
                .filter { it.name == "handleBindApplication" }
                .forEach { LSPlantBridge.deoptimize(it) }
        }.onFailure {
            Log.e(TAG, "Failed to set up Zygisk entry", it)
        }
    }
}
