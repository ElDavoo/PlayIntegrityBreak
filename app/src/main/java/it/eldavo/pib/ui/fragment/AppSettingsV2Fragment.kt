package it.eldavo.pib.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.ServiceClient
import icu.nullptr.playintegritybreak.ui.util.ThemeUtils.asDrawable
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
    }

    private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val viewModel by viewModels<AppSettingsViewModel>() {
        val args by navArgs<AppSettingsV2FragmentArgs>()
        val isDefaultMode = !args.bulkConfigMode && args.packageName == Constants.DEFAULT_APP_PACKAGE_NAME
        val cfg: JsonConfig.AppConfig? = if (args.bulkConfigMode) {
            if (args.bulkConfig != null) JsonConfig.AppConfig.parse(args.bulkConfig!!)
            else null
        } else if (isDefaultMode) {
            JsonConfig.AppConfig(
                interventionEnabled = true,
                rewriteIntegrityResponseOverridden = true,
                rewriteIntegrityResponse = ConfigManager.defaultHookRewriteEnabled,
                rewriteIntegrityErrorCode = ConfigManager.defaultHookRewriteErrorCode,
                rewriteIntegrityErrorRemediable = ConfigManager.defaultHookRewriteRemediable,
            )
        } else {
            ConfigManager.getAppConfig(args.packageName)
        }

        val pack = AppSettingsViewModel.Pack(
            app = args.packageName,
            enabled = if (isDefaultMode) true else cfg != null,
            bulkConfig =  args.bulkConfigMode,
            config = cfg ?: JsonConfig.AppConfig(),
            bulkApps = args.bulkConfigApps,
        )
        AppSettingsViewModel.Factory(pack)
    }

    private val isDefaultMode: Boolean
        get() = !viewModel.pack.bulkConfig && viewModel.pack.app == Constants.DEFAULT_APP_PACKAGE_NAME

    private fun saveConfig() {
        if (viewModel.pack.bulkConfig) {
            setFragmentResult("bulk_app_settings", Bundle().apply {
                putString(
                    "appConfig",
                    if (viewModel.pack.enabled) viewModel.pack.config.toString() else null,
                )
            })
        } else if (isDefaultMode) {
            ConfigManager.setDefaultRewriteConfig(
                enabled = viewModel.pack.config.rewriteIntegrityResponse,
                errorCode = viewModel.pack.config.rewriteIntegrityErrorCode,
                remediable = viewModel.pack.config.rewriteIntegrityErrorRemediable,
            )
        } else {
            ConfigManager.setAppConfig(
                viewModel.pack.app,
                if (viewModel.pack.enabled) viewModel.pack.config else null,
            )
        }
    }

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

    val subtitle: String by lazy {
        if (viewModel.pack.bulkConfig) {
            if (viewModel.pack.bulkApps.isNullOrEmpty()) {
                return@lazy getString(R.string.title_bulk_config_wizard)
            } else {
                return@lazy viewModel.pack.bulkApps!!.joinToString(", ") {
                    PackageHelper.loadAppLabel(it)
                }
            }
        }

        if (isDefaultMode) {
            return@lazy viewModel.pack.app
        }

        return@lazy PackageHelper.loadAppLabel(viewModel.pack.app)
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

    class AppPreferenceDataStore(private val pack: AppSettingsViewModel.Pack) : PreferenceDataStore() {
        private val isDefaultMode = !pack.bulkConfig && pack.app == Constants.DEFAULT_APP_PACKAGE_NAME

        private fun hasAppRewriteOverride(): Boolean {
            if (isDefaultMode) {
                return true
            }

            return pack.enabled && pack.config.interventionEnabled && pack.config.rewriteIntegrityResponseOverridden
        }

        private fun effectiveInterventionEnabled(): Boolean {
            if (isDefaultMode) {
                return true
            }

            return if (pack.enabled) {
                pack.config.interventionEnabled
            } else {
                ConfigManager.defaultHookRewriteEnabled
            }
        }

        private fun effectiveRewriteEnabled(): Boolean {
            if (!effectiveInterventionEnabled()) {
                return false
            }

            if (isDefaultMode) {
                return pack.config.rewriteIntegrityResponse
            }

            return if (hasAppRewriteOverride()) {
                pack.config.rewriteIntegrityResponse
            } else {
                ConfigManager.defaultHookRewriteEnabled
            }
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return when (key) {
                "enableIntervention" -> effectiveInterventionEnabled()
                "enableLogger" -> effectiveRewriteEnabled()
                "deliverSyntheticResponse" -> if (isDefaultMode) true else pack.config.deliverSyntheticResponse
                "rewriteIntegrityErrorRemediable" -> if (hasAppRewriteOverride()) {
                    pack.config.rewriteIntegrityErrorRemediable
                } else {
                    ConfigManager.defaultHookRewriteRemediable
                }
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getString(key: String, defValue: String?): String {
            return when (key) {
                "rewriteIntegrityErrorCode" -> if (hasAppRewriteOverride()) {
                    pack.config.rewriteIntegrityErrorCode.toString()
                } else {
                    ConfigManager.defaultHookRewriteErrorCode.toString()
                }
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putBoolean(key: String, value: Boolean) {
            when (key) {
                "enableIntervention" -> {
                    if (isDefaultMode) return

                    pack.enabled = true
                    pack.config.interventionEnabled = value
                }
                "enableLogger" -> {
                    pack.enabled = true
                    pack.config.interventionEnabled = true
                    pack.config.rewriteIntegrityResponseOverridden = true
                    pack.config.rewriteIntegrityResponse = value
                }
                "deliverSyntheticResponse" -> {
                    if (isDefaultMode) return

                    pack.enabled = true
                    pack.config.interventionEnabled = true
                    pack.config.deliverSyntheticResponse = value
                }
                "rewriteIntegrityErrorRemediable" -> {
                    pack.enabled = true
                    pack.config.interventionEnabled = true
                    pack.config.rewriteIntegrityResponseOverridden = true
                    pack.config.rewriteIntegrityErrorRemediable = value
                }
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String, value: String?) {
            when (key) {
                "rewriteIntegrityErrorCode" -> {
                    pack.enabled = true
                    pack.config.interventionEnabled = true
                    pack.config.rewriteIntegrityResponseOverridden = true
                    pack.config.rewriteIntegrityErrorCode = value?.toIntOrNull() ?: -8
                }
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    class AppPreferenceFragment : PreferenceFragmentCompat() {

        private val parent get() = requireParentFragment() as AppSettingsV2Fragment
        private val pack get() = parent.viewModel.pack
        private val isDefaultMode get() = !pack.bulkConfig && pack.app == Constants.DEFAULT_APP_PACKAGE_NAME

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

        @SuppressLint("DiscouragedApi")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = AppPreferenceDataStore(pack)
            setPreferencesFromResource(R.xml.app_settings_v2, rootKey)
            findPreference<EditTextPreference>("rewriteIntegrityErrorCode")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            if (isDefaultMode) {
                findPreference<Preference>("enableIntervention")?.isVisible = false
                findPreference<Preference>("deliverSyntheticResponse")?.isVisible = false
            }

            findPreference<Preference>("appInfo")?.let {
                if (pack.bulkConfig) {
                    it.icon = R.drawable.outline_storage_24.asDrawable(requireContext())
                    it.title = parent.subtitle
                    if (!pack.bulkApps.isNullOrEmpty()) {
                        it.isSingleLineTitle = true
                        it.summary = getString(R.string.title_bulk_config_wizard)
                    }
                } else if (isDefaultMode) {
                    it.icon = R.drawable.outline_shield_24.asDrawable(requireContext())
                    it.title = pack.app
                    it.summary = pack.app
                    it.isSelectable = false
                } else {
                    it.icon = PackageHelper.loadAppIcon(pack.app)
                    it.title = PackageHelper.loadAppLabel(pack.app)
                    it.summary = pack.app
                    it.setOnPreferenceClickListener {
                        parent.saveConfig()
                        val userId = PackageHelper.loadUserId(pack.app)
                        launchMainActivity(pack.app, userId)
                        true
                    }
                }
            }
        }
    }
}
