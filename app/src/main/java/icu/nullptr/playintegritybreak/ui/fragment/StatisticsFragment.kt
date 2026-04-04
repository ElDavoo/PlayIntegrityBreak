package icu.nullptr.playintegritybreak.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.telemetry.AppIntegrityEventStore
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.util.PackageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.eldavo.pib.R
import it.eldavo.pib.databinding.FragmentStatisticsBinding
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatisticsFragment : Fragment(R.layout.fragment_statistics) {

    private val binding by viewBinding(FragmentStatisticsBinding::bind)
    private var loadJob: Job? = null
    private var selectedWindow: StatsWindow = StatsWindow.ALL

    private enum class StatsWindow(@param:StringRes val titleRes: Int, val durationMs: Long?) {
        ALL(R.string.statistics_window_all, null),
        DAY(R.string.statistics_window_24h, 24 * 60 * 60 * 1000L),
        WEEK(R.string.statistics_window_7d, 7 * 24 * 60 * 60 * 1000L),
    }

    private fun refreshStats() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val fromTimestamp = selectedWindow.durationMs?.let { System.currentTimeMillis() - it } ?: 0L
            val stats = withContext(Dispatchers.IO) {
                AppIntegrityEventStore.getTelemetryStats(fromTimestamp)
            }

            if (!isAdded) return@launch

            binding.toolbar.subtitle = getString(selectedWindow.titleRes)
            val updatedAt = if (stats.generatedAtMs > 0L) stats.generatedAtMs else System.currentTimeMillis()
            val updatedAtText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(updatedAt))
            binding.updatedAt.text = getString(R.string.statistics_updated_at, updatedAtText)
            binding.summaryText.text = getString(
                R.string.statistics_summary_body,
                stats.totalEvents,
                stats.totalRequests,
                stats.totalResponses,
                stats.totalSuccessResponses,
                stats.totalErrorResponses,
            )
            binding.queueText.text = getString(
                R.string.statistics_queue_body,
                stats.queue.pending,
                stats.queue.retry,
                stats.queue.inFlight,
                stats.queue.acknowledged,
                stats.queue.failed,
            )

            renderTopPackages(stats.topPackages)
        }
    }

    private fun renderTopPackages(packages: List<icu.nullptr.playintegritybreak.common.TelemetryPackageStat>) {
        val table = binding.topPackagesTable
        table.removeAllViews()

        if (packages.isEmpty()) {
            table.visibility = View.GONE
            binding.topPackagesEmpty.visibility = View.VISIBLE
            return
        }

        table.visibility = View.VISIBLE
        binding.topPackagesEmpty.visibility = View.GONE

        table.addView(createHeaderRow())
        packages.forEachIndexed { index, stat ->
            table.addView(createPackageRow(index + 1, stat))
        }
    }

    private fun createHeaderRow(): TableRow {
        return createTableRow(isHeader = true).apply {
            addView(createCell("#", 0.7f, alignEnd = true, isHeader = true))
            addView(createCell("Req", 1.0f, alignEnd = true, isHeader = true))
            addView(createCell("Resp", 1.0f, alignEnd = true, isHeader = true))
            addView(createCell("Err", 1.0f, alignEnd = true, isHeader = true))
            addView(createCell("Ok%", 1.0f, alignEnd = true, isHeader = true))
            addView(createCell("Package", 4.0f, alignEnd = false, isHeader = true))
        }
    }

    private fun createPackageRow(rank: Int, stat: icu.nullptr.playintegritybreak.common.TelemetryPackageStat): TableRow {
        val successRatio = if (stat.responseCount > 0) {
            (((stat.responseCount - stat.errorCount).coerceAtLeast(0) * 100f) / stat.responseCount).toInt()
        } else {
            100
        }
        val appLabel = runCatching { PackageHelper.loadAppLabel(stat.packageName) }
            .getOrDefault(getString(R.string.statistics_package_unknown_label))

        return createTableRow(isHeader = false).apply {
            addView(createCell(rank.toString(), 0.7f, alignEnd = true))
            addView(createCell(stat.requestCount.toString(), 1.0f, alignEnd = true))
            addView(createCell(stat.responseCount.toString(), 1.0f, alignEnd = true))
            addView(createCell(stat.errorCount.toString(), 1.0f, alignEnd = true))
            addView(createCell("$successRatio%", 1.0f, alignEnd = true))
            addView(createPackageCell(stat.packageName, appLabel, 4.0f))
        }
    }

    private fun createTableRow(isHeader: Boolean): TableRow {
        return TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isClickable = false
            isFocusable = false
            if (isHeader) {
                setPadding(0, 0, 0, 12)
            } else {
                setPadding(0, 8, 0, 8)
            }
        }
    }

    private fun createCell(
        text: String,
        weight: Float,
        alignEnd: Boolean,
        isHeader: Boolean = false,
    ): MaterialTextView {
        return MaterialTextView(requireContext()).apply {
            layoutParams = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = if (isHeader) 0 else 8
            }
            this.text = text
            textAlignment = if (alignEnd) View.TEXT_ALIGNMENT_TEXT_END else View.TEXT_ALIGNMENT_TEXT_START
            setTextAppearance(
                if (isHeader) {
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
                } else {
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                }
            )
            setTypeface(typeface, if (isHeader) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            maxLines = 1
        }
    }

    private fun createPackageCell(packageName: String, appLabel: String, weight: Float): LinearLayout {
        val packageView = MaterialTextView(requireContext()).apply {
            text = packageName
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val labelView = MaterialTextView(requireContext()).apply {
            text = appLabel
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0.75f
        }

        return LinearLayout(requireContext()).apply {
            layoutParams = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            orientation = LinearLayout.VERTICAL
            addView(packageView)
            addView(labelView)
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_statistics_refresh -> refreshStats()
            R.id.menu_statistics_window_all -> {
                item.isChecked = true
                selectedWindow = StatsWindow.ALL
                refreshStats()
            }
            R.id.menu_statistics_window_24h -> {
                item.isChecked = true
                selectedWindow = StatsWindow.DAY
                refreshStats()
            }
            R.id.menu_statistics_window_7d -> {
                item.isChecked = true
                selectedWindow = StatsWindow.WEEK
                refreshStats()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.title_statistics),
            subtitle = getString(selectedWindow.titleRes),
            menuRes = R.menu.menu_statistics,
            onMenuOptionSelected = this::onMenuOptionSelected,
        )
        binding.toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        binding.toolbar.setNavigationOnClickListener { navController.popBackStack() }
        binding.toolbar.menu.findItem(R.id.menu_statistics_window_all)?.isChecked = true

        setEdge2EdgeFlags(binding.root)
        refreshStats()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }
}
