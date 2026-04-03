package icu.nullptr.playintegritybreak.ui.adapter

import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.ui.view.AppItemView
import it.eldavo.pib_oss.R

class AppManageAdapter(
    private val onItemClickListener: (String) -> Unit,
    private val onFavoriteChanged: () -> Unit,
) : AppSelectAdapter() {

    inner class ViewHolder(view: AppItemView) : AppSelectAdapter.ViewHolder(view) {
        init {
            view.setOnClickListener {
                if (!PrefManager.bypassRiskyPackageWarning && Constants.riskyPackages.contains(view.binding.packageName.text)) {
                    MaterialAlertDialogBuilder(view.context)
                        .setTitle(R.string.app_warning_risky_package_title)
                        .setMessage(R.string.app_warning_risky_package_desc)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            onItemClickListener.invoke(filteredList[absoluteAdapterPosition])
                        }
                        .show()

                    return@setOnClickListener
                }

                onItemClickListener.invoke(filteredList[absoluteAdapterPosition])
            }
        }

        override fun bind(packageName: String) {
            val appItemView = itemView as AppItemView
            appItemView.let {
                it.load(packageName)
                it.showEnabled = ConfigManager.isLoggerEnabled(packageName)
                it.isFavorite = ConfigManager.isFavorite(packageName)
                it.setOnFavoriteClickListener {
                    val newFavoriteState = !ConfigManager.isFavorite(packageName)
                    ConfigManager.setFavorite(packageName, newFavoriteState)
                    appItemView.isFavorite = newFavoriteState
                    onFavoriteChanged.invoke()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = AppItemView(parent.context, false)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(view)
    }
}
