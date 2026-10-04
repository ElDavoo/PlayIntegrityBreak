package icu.nullptr.playintegritybreak.service

import android.util.AtomicFile
import android.util.Log
import androidx.core.util.readText
import androidx.core.util.writeText
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
import java.io.FileNotFoundException
import java.util.UUID

object ConfigManager {
    private const val TAG = "ConfigManager"
    private val serviceSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configFile = AtomicFile(File(pibApp.filesDir, "config.json"))

    /** Immutable snapshot; change it with [update]. */
    @Volatile
    var config = JsonConfig()
        private set

    fun init() {
        val loaded = runCatching {
            readConfigFile()
        }.onFailure {
            Log.e(TAG, "Config file too old or damaged, starting from defaults", it)
            configFile.baseFile.renameTo(File(configFile.baseFile.path + ".damaged"))
            showToast(R.string.config_damaged)
        }.getOrNull()

        config = withUserId(loaded ?: JsonConfig())
        saveConfig()
    }

    private fun readConfigFile(): JsonConfig? {
        val text = try {
            configFile.readText()
        } catch (_: FileNotFoundException) {
            return null
        }
        return parseChecked(text)
    }

    private fun parseChecked(json: String): JsonConfig {
        val parsed = JsonConfig.parse(json)
        require(parsed.configVersion >= BuildConfig.MIN_BACKUP_VERSION) { "Config version too old" }
        return parsed.copy(configVersion = BuildConfig.CONFIG_VERSION)
    }

    @Synchronized
    fun update(block: (JsonConfig) -> JsonConfig) {
        val updated = block(config)
        if (updated == config) return
        config = updated
        saveConfig()
    }

    /** Writes the config to disk and pushes it to the hook. */
    @Synchronized
    fun saveConfig() {
        val text = config.toString()
        configFile.writeText(text)
        serviceSyncScope.launch {
            ServiceClient.writeConfig(text)
        }

        runCatching {
            TelemetryUploadScheduler.syncSchedule()
        }.onFailure {
            Log.w(TAG, "Failed to sync telemetry scheduler", it)
        }
    }

    /** Backup contents. The telemetry user id stays on this device. */
    fun exportJson(): String = config.copy(userId = "").toString()

    fun importConfig(json: String) {
        val imported = parseChecked(json)
        update { withUserId(imported.copy(userId = it.userId)) }
    }

    var detailLog: Boolean
        get() = config.detailLog
        set(value) = update { it.copy(detailLog = value) }

    var errorOnlyLog: Boolean
        get() = config.errorOnlyLog
        set(value) = update { it.copy(errorOnlyLog = value) }

    var maxLogSize: Int
        get() = config.maxLogSize
        set(value) = update { it.copy(maxLogSize = value) }

    var telemetryEnabled: Boolean
        get() = config.telemetryEnabled
        set(value) = update { it.copy(telemetryEnabled = value) }

    var intentApiEnabled: Boolean
        get() = config.intentApiEnabled
        set(value) = update { it.copy(intentApiEnabled = value) }

    var packageQueryWorkaround: Boolean
        get() = config.packageQueryWorkaround
        set(value) {
            update { it.copy(packageQueryWorkaround = value) }
            PackageHelper.invalidateCache()
        }

    val userId: String
        get() = config.userId

    var defaults: JsonConfig.Policy
        get() = config.defaults
        set(value) = update { it.copy(defaults = value) }

    fun getAppConfig(packageName: String): JsonConfig.AppConfig? = config.scope[packageName]

    /** Stores the overrides for [packageName], or removes the app when [appConfig] is null. */
    fun setAppConfig(packageName: String, appConfig: JsonConfig.AppConfig?) = update {
        it.copy(scope = if (appConfig == null) it.scope - packageName else it.scope + (packageName to appConfig))
    }

    fun isConfigured(packageName: String): Boolean = packageName in config.scope

    private fun withUserId(config: JsonConfig): JsonConfig {
        val resolved = config.userId.trim()
            .ifEmpty { PrefManager.telemetryUserId.trim() }
            .ifEmpty { UUID.randomUUID().toString() }
        if (PrefManager.telemetryUserId != resolved) {
            PrefManager.telemetryUserId = resolved
        }
        return config.copy(userId = resolved)
    }

    fun bootstrapFavoritesIfNeeded() {
        if (PrefManager.appFavoritesBootstrapDone) return

        val fetchedFavorites = runCatching {
            FavoritesBootstrapApiClient.fetchFavoritePackages(AppConstants.FAVORITES_BOOTSTRAP_URL)
        }.onFailure {
            Log.w(TAG, "Failed to fetch favorite packages from remote endpoint, using fallback", it)
        }.getOrNull()

        val normalizedFavorites = (fetchedFavorites ?: AppConstants.FAVORITES_BOOTSTRAP_FALLBACK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        update { it.copy(favoritePackages = it.favoritePackages + normalizedFavorites) }
        PrefManager.appFavoritesBootstrapDone = true
    }

    fun isFavorite(packageName: String): Boolean = packageName in config.favoritePackages

    fun setFavorite(packageName: String, favorite: Boolean) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return

        update {
            it.copy(
                favoritePackages = if (favorite) it.favoritePackages + normalizedPackage
                else it.favoritePackages - normalizedPackage
            )
        }
    }

    fun clearUninstalledAppConfigs(onFinish: (success: Boolean) -> Unit) {
        PackageHelper.invalidateCache { throwable ->
            if (throwable != null) {
                onFinish(false)
                return@invalidateCache
            }

            val uninstalled = config.scope.keys.filterNot { PackageHelper.exists(it) }
            update { it.copy(scope = it.scope - uninstalled.toSet()) }
            ServiceClient.log(Log.INFO, TAG, "Pruned ${uninstalled.size} app logger config(s)")
            onFinish(true)
        }
    }
}
