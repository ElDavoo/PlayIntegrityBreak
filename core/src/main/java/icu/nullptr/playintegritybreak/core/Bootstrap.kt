package icu.nullptr.playintegritybreak.core

import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants

private const val TAG = "PIB-Bootstrap"

object Bootstrap {
    /**
     * Installs PIB into the current (Play Store) process using [backend].
     * @return true if hooks were installed, false if another backend already owns the process
     * or the installation failed.
     */
    fun start(backend: HookBackend, classLoader: ClassLoader): Boolean {
        val owner = Backend.claim(backend)
        if (owner != null) {
            Log.i("PIB", "${backend.name} backend skipped: hooks already installed by $owner")
            return false
        }

        return runCatching {
            PIBLoggerService.initialize()
            PIBLoggerService.tryPublishBinderToClientApp()
            IntegrityServiceHook.install(classLoader)
            logI(TAG, "Integrity hooks installed in ${Constants.VENDING_PACKAGE_NAME} (${backend.name})")
        }.onFailure {
            logE(TAG, "Failed to install Integrity hooks", it)
        }.isSuccess
    }

    /** Installs the integrity monitor into the checker app process (see [CheckerMonitorHook]). */
    fun startChecker(backend: HookBackend, classLoader: ClassLoader): Boolean {
        val owner = Backend.claim(backend)
        if (owner != null) {
            Log.i("PIB", "${backend.name} backend skipped: hooks already installed by $owner")
            return false
        }

        return runCatching {
            CheckerMonitorHook.install(classLoader)
        }.onFailure {
            logE(TAG, "Failed to install checker monitor hook", it)
        }.isSuccess
    }
}
