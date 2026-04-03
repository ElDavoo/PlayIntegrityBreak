package icu.nullptr.playintegritybreak.service

import android.os.Build
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.data.AppConstants
import icu.nullptr.playintegritybreak.data.FavoritesBootstrapApiClient
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.telemetry.TelemetryUploadScheduler
import icu.nullptr.playintegritybreak.ui.util.showToast
import icu.nullptr.playintegritybreak.util.PackageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import it.eldavo.pib.R
import it.eldavo.pib.common.BuildConfig
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

        runCatching {
            TelemetryUploadScheduler.syncSchedule()
        }.onFailure {
            Log.w(TAG, "Failed to sync telemetry scheduler", it)
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

    var maxLogSize: Int
        get() = config.maxLogSize
        set(value) {
            config.maxLogSize = value
            saveConfig()
        }

    var telemetryEnabled: Boolean
        get() = config.telemetryEnabled
        set(value) {
            config.telemetryEnabled = value
            saveConfig()
        }

    var telemetryBatchSize: Int
        get() = config.telemetryBatchSize.coerceIn(1, 500)
        set(value) {
            config.telemetryBatchSize = value.coerceIn(1, 500)
            saveConfig()
        }

    var telemetryUploadIntervalMinutes: Int
        get() = config.telemetryUploadIntervalMinutes.coerceIn(15, 1440)
        set(value) {
            config.telemetryUploadIntervalMinutes = value.coerceIn(15, 1440)
            saveConfig()
        }

    var telemetryWifiOnly: Boolean
        get() = config.telemetryWifiOnly
        set(value) {
            config.telemetryWifiOnly = value
            saveConfig()
        }

    var telemetryMaxAttempts: Int
        get() = config.telemetryMaxAttempts.coerceIn(1, 20)
        set(value) {
            config.telemetryMaxAttempts = value.coerceIn(1, 20)
            saveConfig()
        }

    var telemetryBaseRetrySeconds: Int
        get() = config.telemetryBaseRetrySeconds.coerceIn(5, 600)
        set(value) {
            config.telemetryBaseRetrySeconds = value.coerceIn(5, 600)
            saveConfig()
        }

    var telemetryLeaseDurationSeconds: Int
        get() = config.telemetryLeaseDurationSeconds.coerceIn(30, 900)
        set(value) {
            config.telemetryLeaseDurationSeconds = value.coerceIn(30, 900)
            saveConfig()
        }

    var telemetryStaleInFlightMinutes: Int
        get() = config.telemetryStaleInFlightMinutes.coerceIn(5, 120)
        set(value) {
            config.telemetryStaleInFlightMinutes = value.coerceIn(5, 120)
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
            appConfig.interventionEnabled = true
            appConfig.integrityLoggerEnabled = true
            appConfig.logIntegrityRequests = true
            appConfig.logIntegrityResponses = true
            appConfig.rewriteIntegrityResponseOverridden = false
            appConfig.rewriteIntegrityResponse = false
            appConfig.rewriteIntegrityErrorCode = -8
            appConfig.rewriteIntegrityErrorRemediable = true
            appConfig.deliverSyntheticResponse = true
            appConfig.delaySyntheticResponseDelivery = false
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

    fun setDefaultRewriteConfig(enabled: Boolean, errorCode: Int, remediable: Boolean) {
        config.defaultHookRewriteEnabled = enabled
        config.defaultHookRewriteErrorCode = errorCode
        config.defaultHookRewriteRemediable = remediable
        saveConfig()
    }

    fun bootstrapFavoritesIfNeeded() {
        if (PrefManager.appFavoritesBootstrapDone) return

        val fetchedFavorites = runCatching {
            FavoritesBootstrapApiClient.fetchFavoritePackages(AppConstants.FAVORITES_BOOTSTRAP_URL)
        }.onFailure {
            Log.w(TAG, "Failed to fetch favorite packages from remote endpoint, using fallback", it)
        }.getOrNull()

        val selectedFavorites = fetchedFavorites ?: AppConstants.FAVORITES_BOOTSTRAP_FALLBACK
        val normalizedFavorites = selectedFavorites
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        if (normalizedFavorites.isNotEmpty()) {
            val mergedFavorites = config.favoritePackages.toMutableSet().apply {
                addAll(normalizedFavorites)
            }
            if (mergedFavorites != config.favoritePackages) {
                config.favoritePackages = mergedFavorites
                saveConfig()
            }
        }

        PrefManager.appFavoritesBootstrapDone = true
    }

    fun isFavorite(packageName: String): Boolean {
        return config.favoritePackages.contains(packageName)
    }

    fun setFavorite(packageName: String, favorite: Boolean) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return

        val changed = if (favorite) {
            config.favoritePackages.add(normalizedPackage)
        } else {
            config.favoritePackages.remove(normalizedPackage)
        }

        if (changed) {
            saveConfig()
        }
    }

    fun isLoggerEnabled(packageName: String): Boolean {
        if (packageName == Constants.DEFAULT_APP_PACKAGE_NAME) {
            return config.defaultHookRewriteEnabled
        }

        val appConfig = config.scope[packageName] ?: return false
        return appConfig.interventionEnabled
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
