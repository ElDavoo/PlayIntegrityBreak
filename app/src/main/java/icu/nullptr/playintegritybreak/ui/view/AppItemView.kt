package icu.nullptr.playintegritybreak.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import dev.androidbroadcast.vbpd.CreateMethod
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.util.PackageHelper
import it.eldavo.pib_oss.R
import it.eldavo.pib_oss.databinding.AppItemViewBinding

class AppItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding by viewBinding<AppItemViewBinding>(createMethod = CreateMethod.INFLATE)

    init {
        updateFavoriteToggle()
    }

    var showEnabled: Boolean
        get() = binding.enabled.isVisible
        set(value) {
            binding.enabled.visibility = if (value) VISIBLE else GONE
        }

    var isChecked: Boolean
        get() = binding.checkbox.isChecked
        set(value) {
            binding.checkbox.isChecked = value
        }

    var isFavorite: Boolean
        get() = binding.favoriteToggle.isSelected
        set(value) {
            binding.favoriteToggle.isSelected = value
            updateFavoriteToggle()
        }

    constructor(context: Context, isCheckable: Boolean) : this(context) {
        binding.checkbox.visibility = if (isCheckable) VISIBLE else GONE
        binding.favoriteToggle.visibility = if (isCheckable) GONE else VISIBLE
    }

    fun setOnFavoriteClickListener(onClickListener: View.OnClickListener?) {
        binding.favoriteToggle.setOnClickListener(onClickListener)
    }

    fun load(packageName: String) {
        binding.packageName.text = packageName
        try {
            binding.label.text = PackageHelper.loadAppLabel(packageName)
            binding.icon.setImageDrawable(PackageHelper.loadAppIcon(packageName))
        } catch (_: Throwable) {
            binding.label.text = packageName
            binding.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    private fun updateFavoriteToggle() {
        val isFavorite = binding.favoriteToggle.isSelected
        binding.favoriteToggle.setImageResource(
            if (isFavorite) R.drawable.baseline_star_24
            else R.drawable.outline_star_border_24
        )
        binding.favoriteToggle.contentDescription = context.getString(
            if (isFavorite) R.string.app_favorite_remove
            else R.string.app_favorite_add
        )
    }
}
