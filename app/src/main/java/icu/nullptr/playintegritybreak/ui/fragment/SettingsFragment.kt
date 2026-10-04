package icu.nullptr.playintegritybreak.ui.fragment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.recreateMainActivity
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.ui.util.showToast
import icu.nullptr.playintegritybreak.util.ConfigUtils
import icu.nullptr.playintegritybreak.util.LangList
import icu.nullptr.playintegritybreak.util.PackageHelper.findEnabledAppComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import it.eldavo.pib.R
import it.eldavo.pib.databinding.FragmentSettingsBinding
import it.eldavo.pib.ui.activity.MainActivity
import it.eldavo.pib.ui.preference.AppIconPreference
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
                "hideIcon" -> PrefManager.hideIcon
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning
                "disableUpdate" -> PrefManager.disableUpdate
                "packageQueryWorkaround" -> ConfigManager.packageQueryWorkaround
                "telemetryEnabled" -> ConfigManager.telemetryEnabled
                "intentApiEnabled" -> ConfigManager.intentApiEnabled
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getString(key: String, defValue: String?): String {
            return when (key) {
                "language" -> ConfigUtils.getAppLocaleTag()
                "themeColor" -> PrefManager.themeColor
                "darkTheme" -> PrefManager.darkTheme.toString()
                "maxLogSize" -> ConfigManager.maxLogSize.toString()
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
                "disableUpdate" -> PrefManager.disableUpdate = value
                "hideIcon" -> PrefManager.hideIcon = value
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning = value
                "packageQueryWorkaround" -> ConfigManager.packageQueryWorkaround = value
                "telemetryEnabled" -> ConfigManager.telemetryEnabled = value
                "intentApiEnabled" -> ConfigManager.intentApiEnabled = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String, value: String?) {
            when (key) {
                "language" -> ConfigUtils.setAppLocale(value!!)
                "themeColor" -> PrefManager.themeColor = value!!
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "maxLogSize" -> ConfigManager.maxLogSize = value!!.toInt()
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

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {
        private fun getLocaleSummary(tag: String): String {
            if (tag == "SYSTEM") return getString(R.string.follow_system)

            val locale = Locale.forLanguageTag(tag)
            val displayLocale = ConfigUtils.getCurrentDisplayLocale()

            return if (locale.script.isNotEmpty()) {
                locale.getDisplayScript(displayLocale)
            } else {
                locale.getDisplayName(displayLocale)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
            setPreferencesFromResource(R.xml.settings, rootKey)

            val isSystemLanguagePickerAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            findPreference<PreferenceCategory>("languageCategory")?.isVisible = !isSystemLanguagePickerAvailable

            if (!isSystemLanguagePickerAvailable) {
                findPreference<ListPreference>("language")?.let {
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
                    it.summary = getLocaleSummary(it.value)
                    it.setOnPreferenceChangeListener { _, newValue ->
                        val localeTag = newValue as String
                        ConfigUtils.setAppLocale(localeTag)
                        it.summary = getLocaleSummary(localeTag)
                        recreateMainActivity()
                        true
                    }
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

            var bypassIntentApiEnableConfirmation = false
            findPreference<SwitchPreferenceCompat>("intentApiEnabled")?.setOnPreferenceChangeListener { pref, value ->
                val enableIntentApi = value as Boolean
                if (!enableIntentApi || bypassIntentApiEnableConfirmation) {
                    return@setOnPreferenceChangeListener true
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_intent_api_enable_title)
                    .setMessage(R.string.settings_intent_api_enable_message)
                    .setPositiveButton(R.string.settings_intent_api_enable_confirm) { _, _ ->
                        bypassIntentApiEnableConfirmation = true
                        (pref as SwitchPreferenceCompat).isChecked = true
                        bypassIntentApiEnableConfirmation = false
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .show()

                false
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
        }
    }
}
