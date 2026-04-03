package icu.nullptr.playintegritybreak.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.ServiceClient
import icu.nullptr.playintegritybreak.telemetry.TelemetryUploadScheduler
import icu.nullptr.playintegritybreak.ui.adapter.LogAdapter
import icu.nullptr.playintegritybreak.ui.util.contentResolver
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.ui.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.eldavo.pib_oss.R
import it.eldavo.pib_oss.databinding.FragmentLogsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class LogsFragment : Fragment(R.layout.fragment_logs) {

    private companion object {
        private const val MAX_RENDERED_LOG_ITEMS = 3000
    }

    private val binding by viewBinding(FragmentLogsBinding::bind)
    private val adapter by lazy { LogAdapter(requireContext()) }
    private var logCache: String? = null
    private var updateJob: Job? = null

    private val saveSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-log")) save@{ uri ->
            if (uri == null) return@save
            if (logCache.isNullOrEmpty()) {
                showToast(R.string.logs_empty)
                return@save
            }
            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) showToast(R.string.home_export_failed)
                else output.write(logCache!!.toByteArray())
            }
            showToast(R.string.logs_saved)
        }

    private fun parseLogs(rawText: String): List<LogAdapter.LogItem> {
        val parsed = buildList {
            val cur = StringBuilder()
            rawText.lineSequence().forEach { line ->
                if (line.startsWith('[') && cur.isNotEmpty()) {
                    LogAdapter.parseLog(cur.toString())?.let(::add)
                    cur.clear()
                }
                cur.append(line).append('\n')
            }
            if (cur.isNotEmpty()) {
                LogAdapter.parseLog(cur.toString())?.let(::add)
            }
        }

        val capped = if (parsed.size > MAX_RENDERED_LOG_ITEMS) {
            parsed.takeLast(MAX_RENDERED_LOG_ITEMS)
        } else {
            parsed
        }

        return if (PrefManager.logFilter_reverseOrder) capped else capped.reversed()
    }

    private fun updateLogs() {
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            val logsText = withContext(Dispatchers.IO) {
                runCatching {
                    ServiceClient.logs
                }.getOrElse {
                    val location = runCatching {
                        ServiceClient.logFileLocation
                    }.getOrDefault("the log file")
                    "[ERROR] 01-01 01:01:01 (${getString(R.string.app_name)}) Cannot read logs due to Binder issues, try reading $location manually"
                }
            }

            logCache = logsText
            if (logsText.isNullOrEmpty()) {
                binding.serviceOff.visibility = View.VISIBLE
                adapter.logs = emptyList()
                return@launch
            }

            val parsedLogs = withContext(Dispatchers.Default) {
                parseLogs(logsText)
            }

            if (!isAdded) return@launch

            binding.serviceOff.visibility = View.GONE
            adapter.logs = parsedLogs
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_refresh -> updateLogs()
            R.id.menu_save -> {
                val date = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.getDefault()).format(Date())
                saveSAFLauncher.launch("PIB_logs_$date.log")
            }
            R.id.menu_delete -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    ServiceClient.clearLogs()
                    withContext(Dispatchers.Main) { updateLogs() }
                }
            }
            R.id.menu_upload_telemetry -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (!ConfigManager.telemetryEnabled) {
                        withContext(Dispatchers.Main) {
                            showToast(R.string.logs_telemetry_disabled)
                        }
                        return@launch
                    }

                    TelemetryUploadScheduler.triggerImmediate(reason = "manual-logs")
                    val snapshot = ServiceClient.getTelemetryQueueSnapshot()

                    withContext(Dispatchers.Main) {
                        showToast(
                            getString(
                                R.string.logs_telemetry_upload_enqueued,
                                snapshot.pending,
                                snapshot.retry,
                                snapshot.inFlight,
                            )
                        )
                    }
                }
            }
            R.id.menu_show_telemetry_queue -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val snapshot = ServiceClient.getTelemetryQueueSnapshot()
                    withContext(Dispatchers.Main) {
                        showToast(
                            getString(
                                R.string.logs_telemetry_queue_snapshot,
                                snapshot.pending,
                                snapshot.retry,
                                snapshot.inFlight,
                                snapshot.acknowledged,
                                snapshot.failed,
                            )
                        )
                    }
                }
            }
            R.id.menu_filter_debug -> {
                item.isChecked = true
                PrefManager.logFilter_level = 0
                updateLogs()
            }
            R.id.menu_filter_info -> {
                item.isChecked = true
                PrefManager.logFilter_level = 1
                updateLogs()
            }
            R.id.menu_filter_warn -> {
                item.isChecked = true
                PrefManager.logFilter_level = 2
                updateLogs()
            }
            R.id.menu_filter_error -> {
                item.isChecked = true
                PrefManager.logFilter_level = 3
                updateLogs()
            }
            R.id.menu_reverse_order -> {
                item.isChecked = !item.isChecked
                PrefManager.logFilter_reverseOrder = item.isChecked
                updateLogs()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.title_logs),
                menuRes = R.menu.menu_logs,
                onMenuOptionSelected = this@LogsFragment::onMenuOptionSelected
            )
            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener { navController.popBackStack() }
            // isTitleCentered = true
        }

        with(binding.toolbar.menu) {
            when (PrefManager.logFilter_level) {
                0 -> findItem(R.id.menu_filter_debug).isChecked = true
                1 -> findItem(R.id.menu_filter_info).isChecked = true
                2 -> findItem(R.id.menu_filter_warn).isChecked = true
                3 -> findItem(R.id.menu_filter_error).isChecked = true
            }
            findItem(R.id.menu_reverse_order).isChecked = PrefManager.logFilter_reverseOrder
        }

        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        updateLogs()

        setEdge2EdgeFlags(binding.root)
    }

    override fun onDestroyView() {
        updateJob?.cancel()
        super.onDestroyView()
    }
}
