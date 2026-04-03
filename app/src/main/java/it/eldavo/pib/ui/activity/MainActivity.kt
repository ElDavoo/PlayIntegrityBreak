package it.eldavo.pib.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.navigation.findNavController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.ui.util.ThemeUtils
import icu.nullptr.playintegritybreak.util.ConfigUtils
import it.eldavo.pib.R
import it.eldavo.pib.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    var currentConfiguration: Configuration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // I add this manually because the E2E code is not working like I want
        // They should give us a separate method to set it for enableEdgeToEdge
        // Source: https://github.com/androidx/androidx/blob/c0f9aabcf6f32029249ac7647711744b68e2a003/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt#L299
        window.isNavigationBarContrastEnforced = !PrefManager.systemWallpaper

        DynamicColors.applyToActivityIfAvailable(
            this,
            DynamicColorsOptions.Builder().also {
                if (!ThemeUtils.isSystemAccent)
                    it.setThemeOverlay(ThemeUtils.getColorThemeStyleRes(this))
            }.build()
        )

        currentConfiguration = resources.configuration

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (isNightModeEnabled(currentConfiguration) != isNightModeEnabled(newConfig)) {
            recreate()
        }

        currentConfiguration = newConfig
    }

    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        super.onApplyThemeResource(theme, resid, first)
        if (!DynamicColors.isDynamicColorAvailable()) {
            theme.applyStyle(ThemeUtils.getColorThemeStyleRes(this), true)
        }

        theme.applyStyle(ThemeUtils.getOverlayThemeStyleRes(this), true)

        applyWallpaperBackgroundColor()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = this.findNavController(R.id.nav_host_fragment)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(getLocaleAppliedContext(newBase))
    }

    fun applyWallpaperBackgroundColor(value: Int = PrefManager.systemWallpaperAlpha) {
        if (PrefManager.systemWallpaper) {
            val color = (value shl 24) + if (ThemeUtils.isNightMode(this)) {
                0x00000000
            } else {
                0x00FFFFFF
            }

            window.setBackgroundDrawable(color.toDrawable())
        }
    }

    private fun getLocaleAppliedContext(context: Context?): Context? {
        val config = pibApp.resources.configuration
        config.setLocale(ConfigUtils.getLocale())

        return context?.createConfigurationContext(config)
    }

    private fun isNightModeEnabled(config: Configuration?) = config?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
}
