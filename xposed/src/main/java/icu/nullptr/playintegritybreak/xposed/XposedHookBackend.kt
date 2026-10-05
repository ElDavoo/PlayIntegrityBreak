package icu.nullptr.playintegritybreak.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import icu.nullptr.playintegritybreak.core.HookBackend
import icu.nullptr.playintegritybreak.core.HookParam
import icu.nullptr.playintegritybreak.core.MethodHook
import java.lang.reflect.Method

object XposedHookBackend : HookBackend {
    override val name = "Xposed"

    override fun hook(method: Method, hook: MethodHook) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                hook.before(XposedHookParam(method, param))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                hook.after(XposedHookParam(method, param))
            }
        })
    }

    override fun log(line: String) = XposedBridge.log(line)

    private class XposedHookParam(
        override val method: Method,
        private val param: XC_MethodHook.MethodHookParam,
    ) : HookParam() {
        override val thisObject: Any? get() = param.thisObject
        override val args: Array<Any?> get() = param.args ?: emptyArray()
        override var result: Any?
            get() = param.result
            set(value) { param.result = value }
        override var throwable: Throwable?
            get() = param.throwable
            set(value) { param.throwable = value }
    }
}
