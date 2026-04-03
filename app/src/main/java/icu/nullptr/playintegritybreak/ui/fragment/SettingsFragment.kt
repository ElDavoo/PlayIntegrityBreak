package icu.nullptr.playintegritybreak.ui.fragment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.PropertyUtils
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.ui.util.enabledString
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.recreateMainActivity
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.ui.util.showToast
import icu.nullptr.playintegritybreak.ui.util.withAnimations
import icu.nullptr.playintegritybreak.util.ConfigUtils.Companion.getLocale
import icu.nullptr.playintegritybreak.util.LangList
import icu.nullptr.playintegritybreak.util.PackageHelper.findEnabledAppComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import it.eldavo.pib_oss.R
import it.eldavo.pib_oss.databinding.FragmentSettingsBinding
import it.eldavo.pib_oss.ui.activity.MainActivity
import it.eldavo.pib_oss.ui.preference.AppIconPreference
import java.util.Locale

class SettingsFragment : Fragment(R.layout.fragment_settings), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private val binding by viewBinding(FragmentSettingsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.title_settings),
                navigationIcon = R.drawable.baseline_arrow_back_24,
                navigationOnClick = { navController.navigateUp() }
            )
            // isTitleCentered = true
        }

        runBlocking {
            PrefManager.isLauncherIconInvisible.emit(findEnabledAppComponent(pibApp) == null)
        }

        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }

        setEdge2EdgeFlags(binding.root)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, pref.fragment!!)
        fragment.arguments = pref.extras
        childFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    class SettingsPreferenceDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return when (key) {
                "followSystemAccent" -> PrefManager.followSystemAccent
                "systemWallpaper" -> PrefManager.systemWallpaper
                "blackDarkTheme" -> PrefManager.blackDarkTheme
                "detailLog" -> ConfigManager.detailLog
                "errorOnlyLog" -> ConfigManager.errorOnlyLog
                "defaultHookRewriteEnabled" -> ConfigManager.defaultHookRewriteEnabled
                "defaultHookRewriteRemediable" -> ConfigManager.defaultHookRewriteRemediable
                "hideIcon" -> PrefManager.hideIcon
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning
                "appDataIsolation" -> ConfigManager.altAppDataIsolation
                "voldAppDataIsolation" -> ConfigManager.altVoldAppDataIsolation
                "skipSystemAppDataIsolation" -> ConfigManager.skipSystemAppDataIsolation
                "disableActivityLaunchProtection" -> ConfigManager.disableActivityLaunchProtection
                "forceMountData" -> ConfigManager.forceMountData
                "disableUpdate" -> PrefManager.disableUpdate
                "packageQueryWorkaround" -> ConfigManager.packageQueryWorkaround
                "telemetryEnabled" -> ConfigManager.telemetryEnabled
                "telemetryWifiOnly" -> ConfigManager.telemetryWifiOnly
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getString(key: String, defValue: String?): String {
            return when (key) {
                "language" -> PrefManager.locale
                "themeColor" -> PrefManager.themeColor
                "darkTheme" -> PrefManager.darkTheme.toString()
                "maxLogSize" -> ConfigManager.maxLogSize.toString()
                "defaultHookRewriteErrorCode" -> ConfigManager.defaultHookRewriteErrorCode.toString()
                "telemetryEndpointUrl" -> ConfigManager.telemetryEndpointUrl
                "telemetryAuthToken" -> ConfigManager.telemetryAuthToken
                "telemetryBatchSize" -> ConfigManager.telemetryBatchSize.toString()
                "telemetryUploadIntervalMinutes" -> ConfigManager.telemetryUploadIntervalMinutes.toString()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getInt(key: String, defValue: Int): Int {
            return when (key) {
                "systemWallpaperAlpha" -> PrefManager.systemWallpaperAlpha
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putBoolean(key: String, value: Boolean) {
            when (key) {
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "systemWallpaper" -> PrefManager.systemWallpaper = value
                "blackDarkTheme" -> PrefManager.blackDarkTheme = value
                "detailLog" -> ConfigManager.detailLog = value
                "errorOnlyLog" -> ConfigManager.errorOnlyLog = value
                "defaultHookRewriteEnabled" -> ConfigManager.defaultHookRewriteEnabled = value
                "defaultHookRewriteRemediable" -> ConfigManager.defaultHookRewriteRemediable = value
                "forceMountData" -> ConfigManager.forceMountData = value
                "disableUpdate" -> PrefManager.disableUpdate = value
                "hideIcon" -> PrefManager.hideIcon = value
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning = value
                "disableActivityLaunchProtection" -> ConfigManager.disableActivityLaunchProtection = value
                "appDataIsolation" -> ConfigManager.altAppDataIsolation = value
                "voldAppDataIsolation" -> ConfigManager.altVoldAppDataIsolation = value
                "skipSystemAppDataIsolation" -> ConfigManager.skipSystemAppDataIsolation = value
                "packageQueryWorkaround" -> ConfigManager.packageQueryWorkaround = value
                "telemetryEnabled" -> ConfigManager.telemetryEnabled = value
                "telemetryWifiOnly" -> ConfigManager.telemetryWifiOnly = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String, value: String?) {
            when (key) {
                "language" -> PrefManager.locale = value!!
                "themeColor" -> PrefManager.themeColor = value!!
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "maxLogSize" -> ConfigManager.maxLogSize = value!!.toInt()
                "defaultHookRewriteErrorCode" -> ConfigManager.defaultHookRewriteErrorCode = value?.toIntOrNull() ?: -8
                "telemetryEndpointUrl" -> ConfigManager.telemetryEndpointUrl = value.orEmpty()
                "telemetryAuthToken" -> ConfigManager.telemetryAuthToken = value.orEmpty()
                "telemetryBatchSize" -> ConfigManager.telemetryBatchSize = value?.toIntOrNull() ?: 100
                "telemetryUploadIntervalMinutes" -> ConfigManager.telemetryUploadIntervalMinutes = value?.toIntOrNull() ?: 30
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putInt(key: String, value: Int) {
            when (key) {
                "systemWallpaperAlpha" -> PrefManager.systemWallpaperAlpha = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    class DataIsolationPreferenceFragment(private val preferenceDataStore: PreferenceDataStore) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = preferenceDataStore
            setPreferencesFromResource(R.xml.settings_data_isolation, rootKey)

            findPreference<SwitchPreferenceCompat>("appDataIsolation")?.let {
                it.summary = getString(R.string.settings_need_reboot) + "\n\n" +
                        getString(
                            R.string.settings_default_value,
                            PropertyUtils.isAppDataIsolationEnabled.enabledString(resources)
                        )
            }

            findPreference<SwitchPreferenceCompat>("voldAppDataIsolation")?.let {
                it.summary = getString(R.string.settings_need_reboot) + "\n\n" +
                        getString(
                            R.string.settings_default_value,
                            PropertyUtils.isVoldAppDataIsolationEnabled.enabledString(resources)
                        )

                it.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    if (enabled) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.settings_warning)
                            .setMessage(R.string.settings_vold_warning)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                it.isChecked = true
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                it.isChecked = false
                            }
                            .setCancelable(false)
                            .show()
                    }
                    !enabled
                }
            }
        }
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {
        private fun configureDataIsolation() {
            findPreference<Preference>("dataIsolation")?.let {
                it.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                it.summary = when {
                    it.isEnabled -> getString(
                        R.string.settings_data_isolation_summary,
                        if (ConfigManager.altAppDataIsolation) getString(R.string.settings_overwritten)
                        else PropertyUtils.isAppDataIsolationEnabled.enabledString(resources),
                        if (ConfigManager.altVoldAppDataIsolation) getString(R.string.settings_overwritten)
                        else PropertyUtils.isVoldAppDataIsolationEnabled.enabledString(resources),
                        ConfigManager.forceMountData.enabledString(resources)
                    )
                    else -> getString(R.string.settings_data_isolation_unsupported)
                }
                it.setOnPreferenceClickListener { _ ->
                    parentFragmentManager.beginTransaction()
                        .withAnimations()
                        .replace(
                            R.id.settings_container,
                            DataIsolationPreferenceFragment(
                                preferenceManager.preferenceDataStore!!
                            )
                        )
                        .addToBackStack(null)
                        .commit()

                    true
                }
            }
        }

        @Suppress("deprecation")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<ListPreference>("language")?.let {
                val userLocale = getLocale()
                val entries = buildList {
                    for (lang in LangList.LOCALES) {
                        if (lang == "SYSTEM") add(getString(R.string.follow_system))
                        else {
                            val locale = Locale.forLanguageTag(lang)
                            add(locale.getDisplayName(locale))
                        }
                    }
                }
                it.entries = entries.toTypedArray()
                it.entryValues = LangList.LOCALES
                if (it.value == "SYSTEM") {
                    it.summary = getString(R.string.follow_system)
                } else {
                    val locale = Locale.forLanguageTag(it.value)
                    it.summary = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(userLocale) else locale.getDisplayName(userLocale)
                }
                it.setOnPreferenceChangeListener { _, newValue ->
                    val locale = getLocale()
                    val config = resources.configuration
                    config.setLocale(locale)
                    pibApp.resources.updateConfiguration(config, resources.displayMetrics)
                    recreateMainActivity()
                    true
                }
            }

            findPreference<Preference>("translation")?.let {
                it.summary = getString(R.string.settings_translate_summary, getString(R.string.app_name))
                it.setOnPreferenceClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Constants.TRANSLATE_URL.toUri()))
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("followSystemAccent")?.also {
                it.isVisible = DynamicColors.isDynamicColorAvailable()

                it.setOnPreferenceChangeListener { _, _ ->
                    recreateMainActivity()
                    true
                }
            }

            findPreference<ListPreference>("themeColor")?.also {
                if (!DynamicColors.isDynamicColorAvailable()) it.dependency = null

                it.setOnPreferenceChangeListener { _, _ ->
                    recreateMainActivity()
                    true
                }
            }

            findPreference<ListPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    recreateMainActivity()
                }
                true
            }

            findPreference<SwitchPreferenceCompat>("systemWallpaper")?.apply {
                isEnabled = findPreference<SwitchPreferenceCompat>("blackDarkTheme")?.isChecked != true
                setOnPreferenceChangeListener { _, value ->
                    recreateMainActivity(value as Boolean)

                    true
                }
            }

            findPreference<SeekBarPreference>("systemWallpaperAlpha")?.apply {
                setOnPreferenceChangeListener { _, value ->
                    (requireActivity() as MainActivity).applyWallpaperBackgroundColor(value as Int)

                    true
                }
            }

            val detailLog = findPreference<SwitchPreferenceCompat>("detailLog")
            val errorOnlyLog = findPreference<SwitchPreferenceCompat>("errorOnlyLog")

            detailLog?.apply {
                isEnabled = !(errorOnlyLog?.isChecked ?: false)

                setOnPreferenceChangeListener { _, value ->
                    errorOnlyLog?.isEnabled = !(value as Boolean)

                    true
                }
            }
            errorOnlyLog?.apply {
                isEnabled = !(detailLog?.isChecked ?: false)

                setOnPreferenceChangeListener { _, value ->
                    detailLog?.isEnabled = !(value as Boolean)

                    true
                }
            }

            findPreference<EditTextPreference>("defaultHookRewriteErrorCode")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }

            findPreference<EditTextPreference>("telemetryBatchSize")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }

            findPreference<EditTextPreference>("telemetryUploadIntervalMinutes")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }

            findPreference<EditTextPreference>("telemetryEndpointUrl")?.setOnPreferenceChangeListener { _, value ->
                val endpoint = (value as? String).orEmpty().trim()
                val valid = endpoint.isEmpty() || endpoint.startsWith("http://") || endpoint.startsWith("https://")
                if (!valid) {
                    showToast(R.string.settings_telemetry_endpoint_invalid)
                }
                valid
            }

            findPreference<EditTextPreference>("telemetryBatchSize")?.setOnPreferenceChangeListener { _, value ->
                val size = (value as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                if (size in 1..500) {
                    true
                } else {
                    showToast(R.string.settings_telemetry_batch_size_invalid)
                    false
                }
            }

            findPreference<EditTextPreference>("telemetryUploadIntervalMinutes")?.setOnPreferenceChangeListener { _, value ->
                val minutes = (value as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                if (minutes in 15..1440) {
                    true
                } else {
                    showToast(R.string.settings_telemetry_upload_interval_invalid)
                    false
                }
            }

            findPreference<EditTextPreference>("telemetryAuthToken")?.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> {
                    if (it.text.isNullOrBlank()) {
                        getString(R.string.settings_telemetry_auth_token_not_set)
                    } else {
                        getString(R.string.settings_telemetry_auth_token_set)
                    }
                }

            lifecycleScope.launch {
                PrefManager.isLauncherIconInvisible
                    .flowWithLifecycle(lifecycle)
                    .collect { _ ->
                        findPreference<AppIconPreference>("launcherIcon")?.apply {
                            updateHolder()
                        }
                    }
            }

            findPreference<Preference>("clearUninstalledPackageConfigs")?.apply {
                setOnPreferenceClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_clear_uninstalled_app_configs)
                        .setMessage(R.string.settings_no_undone_warning)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val progressDialog = MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.settings_clear_uninstalled_app_configs)
                                .setView(R.layout.dialog_loading)
                                .setCancelable(false)
                                .create()

                            progressDialog.show()

                            ConfigManager.clearUninstalledAppConfigs { isSuccess ->
                                lifecycleScope.launch {
                                    progressDialog.dismiss()

                                    if (isSuccess) {
                                        showToast(android.R.string.ok)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(false)
                        .show()

                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("blackDarkTheme")?.apply {
                isEnabled = findPreference<SwitchPreferenceCompat>("systemWallpaper")?.isChecked != true
                setOnPreferenceChangeListener { _, _ ->
                    recreateMainActivity()
                    true
                }
            }

            configureDataIsolation()

        }

        override fun onResume() {
            super.onResume()
            configureDataIsolation()
        }
    }
}
