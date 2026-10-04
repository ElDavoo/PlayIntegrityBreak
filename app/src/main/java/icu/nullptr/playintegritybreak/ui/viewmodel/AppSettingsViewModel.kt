package icu.nullptr.playintegritybreak.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.common.PolicyKey
import icu.nullptr.playintegritybreak.service.ConfigManager

/**
 * Draft of either the default policy ([packageName] is [Constants.DEFAULT_APP_PACKAGE_NAME])
 * or one app's overrides. Nothing reaches [ConfigManager] until [save].
 */
class AppSettingsViewModel(val packageName: String) : ViewModel() {

    class Factory(private val packageName: String) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AppSettingsViewModel(packageName) as T
            } else throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    val isDefaults = packageName == Constants.DEFAULT_APP_PACKAGE_NAME

    private var defaults = ConfigManager.defaults
    private var savedOverrides = ConfigManager.getAppConfig(packageName)
    private var overrides = savedOverrides ?: JsonConfig.AppConfig()

    private val effective: JsonConfig.Policy
        get() = if (isDefaults) defaults else overrides.applyTo(ConfigManager.defaults)

    fun get(key: PolicyKey): Any = key.get(effective)

    fun set(key: PolicyKey, value: Any) {
        if (isDefaults) defaults = key.with(defaults, value)
        else overrides = key.with(overrides, value)
    }

    fun resetToDefaults() {
        overrides = JsonConfig.AppConfig()
    }

    fun save() {
        if (isDefaults) {
            ConfigManager.defaults = defaults
            return
        }

        val toSave = overrides.takeUnless { it.isEmpty() }
        if (toSave == savedOverrides) return
        ConfigManager.setAppConfig(packageName, toSave)
        savedOverrides = toSave
    }
}
