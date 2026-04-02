package icu.nullptr.playintegritybreak.xposed.hook

import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.xposed.PIBService
import icu.nullptr.playintegritybreak.xposed.XposedConstants.ZYGOTE_PROCESS_CLASS
import icu.nullptr.playintegritybreak.xposed.logD
import icu.nullptr.playintegritybreak.xposed.logV

class ZygoteHook(private val service: PIBService): IFrameworkHook {
    companion object {
        private const val TAG = "ZygoteHook"
    }

    private val hooks = mutableListOf<XC_MethodHook.Unhook>()

    override fun load() {
        findMethodOrNull(ZYGOTE_PROCESS_CLASS) {
            name == "start"
        }?.hookBefore { param ->
            logV(TAG, "@startZygoteProcess: Starting ${param.args.contentToString()}")

            // ignore if the GIDs array is null
            val gIDsIndex = param.args.indexOfFirst { it is IntArray }
            if (gIDsIndex < 0) return@hookBefore

            val caller = param.args.lastOrNull { it is String } as String? ?: return@hookBefore
            var perms = service.getRestrictedZygotePermissions(caller) ?: return@hookBefore
            if (perms.isNotEmpty()) {
                val gIDs = param.args[gIDsIndex] as IntArray

                // add more security, reject if not available in GID_PAIRS
                perms = perms.filter { Constants.GID_PAIRS.containsValue(it) }

                logD(TAG, "@startZygoteProcess: GIDs are ${gIDs.contentToString()}, removing $perms now")
                param.args[gIDsIndex] = gIDs.filter { it !in perms }.toIntArray()
                service.increaseOthersFilterCount(caller)
            }
        }?.let {
            logD(TAG, "Loaded ZygoteProcess start hook!")
            hooks += it
        }
    }

    override fun unload() {
        hooks.forEach(XC_MethodHook.Unhook::unhook)
        hooks.clear()
    }
}
