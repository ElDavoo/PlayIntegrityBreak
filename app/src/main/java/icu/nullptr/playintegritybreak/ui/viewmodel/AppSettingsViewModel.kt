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
    private var savedDefaults = defaults
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
            // Only the settings changed here are written: something else, such as the integrity automation, may have
            // changed the others since this draft was taken.
            val changed = PolicyKey.entries.filter { it.get(defaults) != it.get(savedDefaults) }
            if (changed.isEmpty()) return
            ConfigManager.update { config ->
                config.copy(defaults = changed.fold(config.defaults) { policy, key -> key.with(policy, key.get(defaults)) })
            }
            savedDefaults = defaults
            return
        }

        // The same for an app: the integrity automation may have changed its other settings since the draft was taken.
        val saved = savedOverrides ?: JsonConfig.AppConfig()
        val changed = PolicyKey.entries.filter { it.get(overrides) != it.get(saved) }
        if (changed.isEmpty()) return
        ConfigManager.update { config ->
            val current = config.scope[packageName] ?: JsonConfig.AppConfig()
            val merged = changed.fold(current) { app, key -> key.with(app, key.get(overrides)) }
            config.copy(scope = if (merged.isEmpty()) config.scope - packageName else config.scope + (packageName to merged))
        }
        savedOverrides = overrides.takeUnless { it.isEmpty() }
    }
}
