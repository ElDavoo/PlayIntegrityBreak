package icu.nullptr.playintegritybreak.service

import android.os.Build
import android.util.Log
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.ui.util.showToast
import icu.nullptr.playintegritybreak.util.PackageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.frknkrc44.pib_oss.R
import org.frknkrc44.pib_oss.common.BuildConfig
import java.io.File

object ConfigManager {
    private const val TAG = "ConfigManager"
    private lateinit var config: JsonConfig
    private val serviceSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val configFile = File("${pibApp.filesDir.absolutePath}/config.json")

    fun init() {
        val configFileIsNew = !configFile.exists()
        if (configFileIsNew) {
            config = JsonConfig()
            configFile.writeText(config.toString())
        }
        runCatching {
            if (!configFileIsNew) config = JsonConfig.parse(configFile.readText())
            val configVersion = config.configVersion
            if (configVersion < BuildConfig.MIN_BACKUP_VERSION) throw RuntimeException("Config version too old")
            config.configVersion = BuildConfig.CONFIG_VERSION
            applyLoggerMigrationIfNeeded()
        }.onSuccess {
            saveConfig()
        }.onFailure { catch ->
            runCatching {
                config = JsonConfig.parse(ServiceClient.readConfig() ?: throw RuntimeException("Service config is unavailable"))
                config.configVersion = BuildConfig.CONFIG_VERSION
                applyLoggerMigrationIfNeeded()
                showToast(R.string.home_restore_config)
            }.onSuccess {
                saveConfig()
            }.onFailure {
                showToast(R.string.config_damaged)
                throw RuntimeException("Config file too old or damaged", catch)
            }
        }
    }

    fun saveConfig() {
        val text = config.toString()
        configFile.writeText(text)
        serviceSyncScope.launch {
            runCatching {
                ServiceClient.writeConfig(text)
            }.onFailure {
                Log.w(TAG, "Failed to sync config to service", it)
            }
        }
    }

    var detailLog: Boolean
        get() = config.detailLog
        set(value) {
            config.detailLog = value
            saveConfig()
        }

    var errorOnlyLog: Boolean
        get() = config.errorOnlyLog
        set(value) {
            config.errorOnlyLog = value
            saveConfig()
        }

    var defaultHookRewriteEnabled: Boolean
        get() = config.defaultHookRewriteEnabled
        set(value) {
            config.defaultHookRewriteEnabled = value
            saveConfig()
        }

    var defaultHookRewriteErrorCode: Int
        get() = config.defaultHookRewriteErrorCode
        set(value) {
            config.defaultHookRewriteErrorCode = value
            saveConfig()
        }

    var defaultHookRewriteRemediable: Boolean
        get() = config.defaultHookRewriteRemediable
        set(value) {
            config.defaultHookRewriteRemediable = value
            saveConfig()
        }

    var defaultHookRewriteCallerPackages: String
        get() = config.defaultHookRewriteCallerPackages.joinToString(",")
        set(value) {
            config.defaultHookRewriteCallerPackages = value
                .split(',', ';', '\n', '\r', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableSet()
            saveConfig()
        }

    var maxLogSize: Int
        get() = config.maxLogSize
        set(value) {
            config.maxLogSize = value
            saveConfig()
        }

    var forceMountData: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config.forceMountData
            else false
        set(value) {
            config.forceMountData = value
            saveConfig()
        }

    var disableActivityLaunchProtection: Boolean
        get() = config.disableActivityLaunchProtection
        set(value) {
            config.disableActivityLaunchProtection = value
            saveConfig()
        }

    var altAppDataIsolation: Boolean
        get() = config.altAppDataIsolation
        set(value) {
            config.altAppDataIsolation = value
            saveConfig()
        }

    var altVoldAppDataIsolation: Boolean
        get() = config.altVoldAppDataIsolation
        set(value) {
            config.altVoldAppDataIsolation = value
            saveConfig()
        }

    var skipSystemAppDataIsolation: Boolean
        get() = config.skipSystemAppDataIsolation
        set(value) {
            config.skipSystemAppDataIsolation = value
            saveConfig()
        }

    var packageQueryWorkaround: Boolean
        get() = config.packageQueryWorkaround
        set(value) {
            config.packageQueryWorkaround = value
            saveConfig()
            PackageHelper.invalidateCache()
        }

    fun importConfig(json: String) {
        config = JsonConfig.parse(json)
        config.configVersion = BuildConfig.CONFIG_VERSION
        applyLoggerMigrationIfNeeded()
        saveConfig()
    }

    private fun applyLoggerMigrationIfNeeded() {
        if (config.integrityModeMigrated) return

        config.scope.values.forEach { appConfig ->
            appConfig.integrityLoggerEnabled = true
            appConfig.logIntegrityRequests = true
            appConfig.logIntegrityResponses = true
            appConfig.logIntegrityErrorsOnly = false
            appConfig.rewriteIntegrityResponse = false
            appConfig.rewriteIntegrityErrorCode = -8
            appConfig.rewriteIntegrityErrorRemediable = true
        }

        config.integrityModeMigrated = true
        runCatching {
            ServiceClient.log(
                Log.INFO,
                TAG,
                "Config migration applied: migrated ${config.scope.size} scoped app entries to Integrity logger defaults",
            )
        }
    }

    fun isLoggerEnabled(packageName: String): Boolean {
        return config.scope[packageName]?.rewriteIntegrityResponse == true
    }

    fun getAppConfig(packageName: String): JsonConfig.AppConfig? {
        return config.scope[packageName]
    }

    fun setAppConfig(packageName: String, appConfig: JsonConfig.AppConfig?) {
        if (appConfig == null) config.scope.remove(packageName)
        else config.scope[packageName] = appConfig
        saveConfig()
    }

    fun clearUninstalledAppConfigs(onFinish: (success: Boolean) -> Unit) {
        PackageHelper.invalidateCache { throwable ->
            if (throwable == null) {
                val scopeMarkedToRemove = mutableListOf<String>()
                config.scope.keys.forEach { packageName ->
                    if (!PackageHelper.exists(packageName)) {
                        scopeMarkedToRemove.add(packageName)
                    }
                }

                if (scopeMarkedToRemove.isNotEmpty()) {
                    scopeMarkedToRemove.forEach { config.scope.remove(it) }
                }

                ServiceClient.log(Log.INFO, TAG, "Pruned ${scopeMarkedToRemove.size} app logger config(s)")
                if (scopeMarkedToRemove.isNotEmpty()) {
                    saveConfig()
                }

                onFinish(true)
            } else {
                onFinish(false)
            }
        }
    }
}
