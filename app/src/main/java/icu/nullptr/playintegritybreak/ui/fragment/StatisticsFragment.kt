package icu.nullptr.playintegritybreak.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
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

            binding.topPackagesHeader.text = getString(R.string.statistics_top_packages_header)
            binding.topPackagesText.text = if (stats.topPackages.isEmpty()) {
                getString(R.string.statistics_top_packages_empty)
            } else {
                stats.topPackages.mapIndexed { index, stat ->
                    val successRatio = if (stat.responseCount > 0) {
                        (((stat.responseCount - stat.errorCount).coerceAtLeast(0) * 100f) / stat.responseCount).toInt()
                    } else {
                        100
                    }
                    val appLabel = runCatching { PackageHelper.loadAppLabel(stat.packageName) }
                        .getOrDefault(getString(R.string.statistics_package_unknown_label))
                    buildString {
                        append(
                            String.format(
                                Locale.getDefault(),
                                "%2d  %4d  %4d  %3d  %3d%%  %s",
                                index + 1,
                                stat.requestCount,
                                stat.responseCount,
                                stat.errorCount,
                                successRatio,
                                stat.packageName,
                            )
                        )
                        append('\n')
                        append("    ")
                        append(appLabel)
                    }
                }.joinToString("\n\n")
            }
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
