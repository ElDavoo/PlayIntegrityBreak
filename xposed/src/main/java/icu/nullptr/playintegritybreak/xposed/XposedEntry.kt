package icu.nullptr.playintegritybreak.xposed

import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import icu.nullptr.playintegritybreak.common.Constants
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PIB-XposedEntry"

@Suppress("unused")
class XposedEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private val integrityHooksInstalled = AtomicBoolean(false)

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.VENDING_PACKAGE_NAME) return
        if (lpparam.processName != Constants.VENDING_PACKAGE_NAME) return
        if (!lpparam.isFirstApplication) return

        EzXposed.initHandleLoadPackage(lpparam)
        if (!integrityHooksInstalled.compareAndSet(false, true)) return

        runCatching {
            PIBLoggerService.initialize()
            PIBLoggerService.tryPublishBinderToClientApp()
            IntegrityServiceHook.install(lpparam.classLoader)
            logI(TAG, "Integrity hooks installed in ${lpparam.packageName}")
        }.onFailure {
            logE(TAG, "Failed to install Integrity hooks", it)
            integrityHooksInstalled.set(false)
        }
    }
}
