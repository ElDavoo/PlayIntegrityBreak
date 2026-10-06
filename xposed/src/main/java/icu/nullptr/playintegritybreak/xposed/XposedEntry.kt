package icu.nullptr.playintegritybreak.xposed

import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.core.Bootstrap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
class XposedEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private val integrityHooksInstalled = AtomicBoolean(false)

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName != Constants.VENDING_PACKAGE_NAME && packageName != Constants.CHECKER_PACKAGE_NAME) return
        if (lpparam.processName != packageName) return
        if (!lpparam.isFirstApplication) return

        EzXposed.initHandleLoadPackage(lpparam)
        if (!integrityHooksInstalled.compareAndSet(false, true)) return

        if (packageName == Constants.CHECKER_PACKAGE_NAME) {
            Bootstrap.startChecker(XposedHookBackend, lpparam.classLoader)
        } else {
            Bootstrap.start(XposedHookBackend, lpparam.classLoader)
        }
    }
}
