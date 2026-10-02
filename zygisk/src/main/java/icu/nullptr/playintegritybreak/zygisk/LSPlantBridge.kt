package icu.nullptr.playintegritybreak.zygisk

import java.lang.reflect.Executable
import java.lang.reflect.Method

/** JNI bridge to LSPlant; natives are registered by the Zygisk module (main.cpp). */
object LSPlantBridge {
    /**
     * Replaces [target] with `callback(Object[] args)` invoked on [hooker].
     * @return the backup method to invoke the original implementation, or null on failure.
     */
    @JvmStatic
    external fun hook(target: Method, hooker: Any, callback: Method): Method?

    /** Forces [method] to run interpreted so hooked callees it inlined are honoured again. */
    @JvmStatic
    external fun deoptimize(method: Executable): Boolean
}
