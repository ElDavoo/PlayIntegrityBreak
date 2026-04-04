package icu.nullptr.playintegritybreak.data

import icu.nullptr.playintegritybreak.common.Constants
import it.eldavo.pib.BuildConfig
import it.eldavo.pib.R

object AppConstants {
    const val COMPONENT_NAME_DEFAULT         = "${BuildConfig.APPLICATION_ID}.MainActivityLauncher"

    val allAppIcons = listOf(
        R.mipmap.ic_launcher       to COMPONENT_NAME_DEFAULT,
    )

    const val UPDATE_CHECK_URL = "https://api.github.com/repos/eldavoo/PlayIntegrityBreak/releases/latest"
    const val FAVORITES_BOOTSTRAP_URL = "http://localhost/favorites"
    val FAVORITES_BOOTSTRAP_FALLBACK = listOf(
        Constants.VENDING_PACKAGE_NAME,
        Constants.GMS_PACKAGE_NAME,
        Constants.GSF_PACKAGE_NAME,
    )
}
