package it.eldavo.pib.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.common.PolicyKey
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.RequestAlert
import icu.nullptr.playintegritybreak.service.ServiceClient
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.ui.util.showToast
import icu.nullptr.playintegritybreak.ui.viewmodel.AppSettingsViewModel
import icu.nullptr.playintegritybreak.util.PackageHelper
import it.eldavo.pib.R
import it.eldavo.pib.databinding.FragmentSettingsBinding

class AppSettingsV2Fragment : Fragment(R.layout.fragment_settings) {
    companion object {
        private const val TAG = "AppSettingsV2Fragment"

        /** Global, so it is only shown on the Default options page. */
        private const val ALERT_STYLE_KEY = "requestAlertStyle"
    }

    private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val viewModel by viewModels<AppSettingsViewModel> {
        val args by navArgs<AppSettingsV2FragmentArgs>()
        AppSettingsViewModel.Factory(args.packageName)
    }

    private fun saveConfig() = viewModel.save()

    private fun onBack() {
        if (!parentFragmentManager.popBackStackImmediate()) {
            saveConfig()
            navController.navigateUp()
        }
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    private val subtitle: String? by lazy {
        if (viewModel.isDefaults) null else PackageHelper.loadAppLabel(viewModel.packageName)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { onBack() }
        setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.title_app_settings),
            subtitle = subtitle,
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { onBack() }
        )

        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, AppPreferenceFragment())
                .commit()
        }

        setEdge2EdgeFlags(binding.root)
    }

    /** Shows the effective policy and writes only the setting that was touched. */
    class AppPreferenceDataStore(private val viewModel: AppSettingsViewModel) : PreferenceDataStore() {
        private fun policyKey(key: String) = PolicyKey.fromKey(key) ?: throw IllegalArgumentException("Invalid key: $key")

        override fun getBoolean(key: String, defValue: Boolean) = viewModel.get(policyKey(key)) as Boolean

        override fun getString(key: String, defValue: String?) = when (key) {
            ALERT_STYLE_KEY -> ConfigManager.requestAlertStyle.name
            else -> viewModel.get(policyKey(key)).toString()
        }

        override fun putBoolean(key: String, value: Boolean) = viewModel.set(policyKey(key), value)

        override fun putString(key: String, value: String?) {
            if (key == ALERT_STYLE_KEY) {
                value?.let { ConfigManager.requestAlertStyle = JsonConfig.AlertStyle.valueOf(it) }
                return
            }
            val policyKey = policyKey(key)
            require(policyKey.isInt) { "Invalid key: $key" }
            value?.toIntOrNull()?.let { viewModel.set(policyKey, it) }
        }
    }

    class AppPreferenceFragment : PreferenceFragmentCompat() {

        private val parent get() = requireParentFragment() as AppSettingsV2Fragment
        private val viewModel get() = parent.viewModel

        private fun launchMainActivity(packageName: String, userId: Int) {
            if (userId != 0) {
                // TODO: Try to find a method to launch apps across user profiles
                return
            }

            try {
                val pkgMgr = requireContext().packageManager
                val pkgInfo = pkgMgr.getPackageInfo(packageName, 0)
                if (pkgInfo.applicationInfo?.enabled == true) {
                    val resolvedIntent = pkgMgr.getLaunchIntentForPackage(packageName)
                    if (resolvedIntent != null) {
                        startActivity(resolvedIntent)
                    } else {
                        throw RuntimeException("No main activity found to launch this app")
                    }
                } else {
                    throw RuntimeException("Package is disabled")
                }
            } catch (e: Throwable) {
                showToast(R.string.app_launch_failed)
                ServiceClient.log(Log.ERROR, TAG, e.stackTraceToString())
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = AppPreferenceDataStore(viewModel)
            loadPreferences()
        }

        private fun loadPreferences() {
            setPreferencesFromResource(R.xml.app_settings_v2, null)
            findPreference<EditTextPreference>(PolicyKey.REWRITE_ERROR_CODE.key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            findPreference<Preference>("rewriteIntegrityErrorCodeReference")?.setOnPreferenceClickListener {
                IntegrityErrorCodeReferenceDialogFragment()
                    .show(parentFragmentManager, "IntegrityErrorCodeReferenceDialog")
                true
            }
            findPreference<Preference>(PolicyKey.REQUEST_ALERT.key)?.setOnPreferenceChangeListener { _, enabled ->
                if (enabled == true) RequestAlert.requestPermission(requireActivity())
                true
            }

            if (viewModel.isDefaults) {
                findPreference<Preference>("appInfo")?.isVisible = false
                findPreference<Preference>("resetToDefaults")?.isVisible = false
                findPreference<Preference>(PolicyKey.REQUEST_ALERT.key)
                    ?.setSummary(R.string.app_integrity_request_alert_default_desc)
                return
            }

            findPreference<Preference>(ALERT_STYLE_KEY)?.isVisible = false

            val packageName = viewModel.packageName
            findPreference<Preference>("appInfo")?.let {
                it.icon = PackageHelper.loadAppIcon(packageName)
                it.title = PackageHelper.loadAppLabel(packageName)
                it.summary = packageName
                it.setOnPreferenceClickListener {
                    parent.saveConfig()
                    launchMainActivity(packageName, PackageHelper.loadUserId(packageName))
                    true
                }
            }
            findPreference<Preference>("resetToDefaults")?.let {
                it.setOnPreferenceClickListener {
                    viewModel.resetToDefaults()
                    loadPreferences()
                    true
                }
            }
        }
    }
}
