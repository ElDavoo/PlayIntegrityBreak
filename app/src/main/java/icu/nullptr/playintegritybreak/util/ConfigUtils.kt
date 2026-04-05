package icu.nullptr.playintegritybreak.util

import android.os.Build
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import icu.nullptr.playintegritybreak.service.PrefManager
import java.util.Locale

class ConfigUtils private constructor() {
    companion object {
        private const val TAG_SYSTEM = "SYSTEM"

        fun getSystemLocale(): Locale = Resources.getSystem().configuration.getLocales().get(0)

        fun initializeAppLocale() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                migrateLegacyLocaleIfNeeded()
                PrefManager.locale = getAppLocaleTag()
                return
            }

            AppCompatDelegate.setApplicationLocales(localeListForTag(PrefManager.locale))
        }

        fun setAppLocale(tag: String) {
            PrefManager.locale = tag
            AppCompatDelegate.setApplicationLocales(localeListForTag(tag))
        }

        fun getAppLocaleTag(): String {
            val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return TAG_SYSTEM
            return normalizeLocaleTagForPreference(locale.toLanguageTag())
        }

        fun getCurrentDisplayLocale(): Locale {
            val locale = AppCompatDelegate.getApplicationLocales()[0]
            return locale ?: getSystemLocale()
        }

        private fun migrateLegacyLocaleIfNeeded() {
            if (PrefManager.localeMigratedToAppCompat) return

            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(localeListForTag(PrefManager.locale))
            }

            PrefManager.localeMigratedToAppCompat = true
        }

        private fun localeListForTag(tag: String): LocaleListCompat {
            return if (tag == TAG_SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
        }

        private fun normalizeLocaleTagForPreference(tag: String): String {
            return when {
                tag.equals("he", ignoreCase = true) -> "iw-IL"
                tag.equals("id", ignoreCase = true) -> "in-ID"
                tag.startsWith("he-", ignoreCase = true) -> "iw-" + tag.substringAfter('-')
                tag.startsWith("id-", ignoreCase = true) -> "in-" + tag.substringAfter('-')
                else -> tag
            }
        }
    }
}
