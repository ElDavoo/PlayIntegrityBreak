package icu.nullptr.playintegritybreak.ui.fragment

import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.ui.adapter.AppManageAdapter
import icu.nullptr.playintegritybreak.ui.util.navigate
import icu.nullptr.playintegritybreak.util.PackageHelper
import it.eldavo.pib_oss.R
import it.eldavo.pib_oss.ui.fragment.AppSettingsV2FragmentArgs

class AppManageFragment : AppSelectFragment() {

    override val firstComparator: Comparator<String> = Comparator.comparing(ConfigManager::isLoggerEnabled).reversed()

    override val adapter = AppManageAdapter {
        if (PackageHelper.exists(it)) {
            val args = AppSettingsV2FragmentArgs(it)
            navigate(R.id.nav_app_settings, args.toBundle())
        }
    }
}
