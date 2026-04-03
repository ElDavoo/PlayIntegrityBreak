package icu.nullptr.playintegritybreak.data

import it.eldavo.pib_oss.BuildConfig
import it.eldavo.pib_oss.R

object AppConstants {
    const val COMPONENT_NAME_DEFAULT         = "${BuildConfig.APPLICATION_ID}.MainActivityLauncher"

    val allAppIcons = listOf(
        R.mipmap.ic_launcher       to COMPONENT_NAME_DEFAULT,
    )

    const val UPDATE_CHECK_URL = "https://api.github.com/repos/frknkrc44/PIB-OSS/releases/latest"
}
