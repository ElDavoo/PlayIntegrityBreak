package it.eldavo.pib.ui.preference

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.RelativeLayout
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import icu.nullptr.playintegritybreak.data.AppConstants.allAppIcons
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.ui.util.ThemeUtils.asDrawable
import icu.nullptr.playintegritybreak.util.PackageHelper.findEnabledAppComponent
import it.eldavo.pib.BuildConfig
import it.eldavo.pib.R


@Suppress("deprecation")
class AppIconPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    var viewHolder: PreferenceViewHolder? = null

    @SuppressLint("SetTextI18n")
    @Deprecated("Deprecated in Java")
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        viewHolder = holder

        super.onBindViewHolder(holder)

        updateHolder()
    }

    fun updateHolder() {
        if (viewHolder == null) return

        (viewHolder!!.itemView as ViewGroup).apply {
            val summary = findViewById<View>(android.R.id.summary)
            val parent = summary.parent as ViewGroup
            parent.removeView(summary)

            val view = LayoutInflater.from(context).inflate(R.layout.preference_app_icon, parent, false)
            view.id = android.R.id.summary
            (view.layoutParams as RelativeLayout.LayoutParams).addRule(RelativeLayout.BELOW, android.R.id.title)

            val appIconSelector: RadioGroup = view.findViewById(R.id.app_icon_selector)
            val selectableIcons = allAppIcons

            for (idx in 0 ..< selectableIcons.size) {
                val radioButton = object : AppCompatRadioButton(context) {
                    override fun setChecked(checked: Boolean) {
                        if (PrefManager.hideIcon) {
                            alpha = 0.4f
                            return
                        }

                        super.setChecked(checked)

                        alpha = if (checked) 1.0f else 0.4f
                    }
                }

                with(radioButton) {
                    layoutParams = RadioGroup.LayoutParams(-2, -2).apply {
                        val padding = context.resources.getDimensionPixelOffset(R.dimen.item_padding_mini2x)
                        setMargins(padding, padding, padding, padding)
                    }

                    id = idx
                    gravity = Gravity.CENTER_VERTICAL
                    buttonDrawable = selectableIcons[idx].first.asDrawable(context)
                    text = ""
                    buttonTintList = null
                }

                appIconSelector.addView(radioButton)
            }

            val selectedComponent = findEnabledAppComponent(context)?.className
            if (selectedComponent != null) {
                val selectedIndex = selectableIcons.indexOfFirst { it.second == selectedComponent }
                if (selectedIndex >= 0) {
                    appIconSelector.check(selectedIndex)
                } else if (selectableIcons.isNotEmpty()) {
                    appIconSelector.check(0)
                    setEnabledComponent(selectableIcons[0].second)
                }
            }

            appIconSelector.setOnCheckedChangeListener { _, checkedId ->
                setEnabledComponent(selectableIcons[checkedId].second)
            }

            parent.addView(view)
        }
    }

    private fun disableAppIcon() {
        val enabled = findEnabledAppComponent(context)
        if (enabled != null) {
            context.packageManager.setComponentEnabledSetting(
                enabled,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun setEnabledComponent(className: String) {
        disableAppIcon()

        context.packageManager.setComponentEnabledSetting(
            ComponentName(BuildConfig.APPLICATION_ID, className),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
