package it.eldavo.pib.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.service.PrefManager
import icu.nullptr.playintegritybreak.service.RequestAlert
import icu.nullptr.playintegritybreak.ui.util.ThemeUtils.themeColor
import icu.nullptr.playintegritybreak.ui.util.navController
import icu.nullptr.playintegritybreak.ui.util.setEdge2EdgeFlags
import icu.nullptr.playintegritybreak.ui.util.setupToolbar
import icu.nullptr.playintegritybreak.ui.util.showToast
import it.eldavo.pib.R
import it.eldavo.pib.databinding.FragmentIntegrityMonitorBinding
import it.eldavo.pib.databinding.ItemIntegrityCheckBinding
import it.eldavo.pib.monitor.IntegrityCheck
import it.eldavo.pib.monitor.IntegrityCheckStore
import it.eldavo.pib.monitor.IntegrityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class IntegrityMonitorFragment : Fragment(R.layout.fragment_integrity_monitor) {

    private val binding by viewBinding(FragmentIntegrityMonitorBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.title_integrity_monitor),
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { navController.popBackStack() },
        )
        setEdge2EdgeFlags(binding.root)

        binding.checkerDownload.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, CHECKER_DOWNLOAD_URL.toUri()))
        }
        binding.checkNow.setOnClickListener { runCheck() }
        binding.clearHistory.setOnClickListener {
            IntegrityCheckStore.clear()
            refresh()
        }

        setupPeriodicControls()
    }

    override fun onStart() {
        super.onStart()
        binding.checkerMissingCard.visibility =
            if (IntegrityMonitor.isCheckerInstalled(requireContext())) View.GONE else View.VISIBLE
        refresh()
    }

    private fun setupPeriodicControls() {
        val group = binding.intervalGroup
        for (hours in IntegrityMonitor.intervalHoursOptions) {
            val button = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            button.id = View.generateViewId()
            button.tag = hours
            button.text = getString(R.string.monitor_interval_hours, hours)
            // Share the row equally, or the last buttons are cut off on narrow screens.
            group.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (hours == PrefManager.integrityMonitorIntervalHours) group.check(button.id)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val hours = group.findViewById<View>(checkedId).tag as Int
            if (hours == PrefManager.integrityMonitorIntervalHours) return@addOnButtonCheckedListener
            PrefManager.integrityMonitorIntervalHours = hours
            IntegrityMonitor.syncSchedule(replace = true)
        }

        binding.periodicSwitch.isChecked = PrefManager.integrityMonitorEnabled
        group.isEnabled = PrefManager.integrityMonitorEnabled
        binding.periodicSwitch.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityMonitorEnabled = isChecked
            group.isEnabled = isChecked
            IntegrityMonitor.syncSchedule()
            if (isChecked) RequestAlert.requestPermission(requireActivity())
        }
    }

    private fun runCheck() {
        binding.checkNow.isEnabled = false
        binding.checkProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { IntegrityMonitor.checkAndRecord() }
            if (!isAdded) return@launch
            binding.checkNow.isEnabled = true
            binding.checkProgress.visibility = View.GONE
            refresh()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { IntegrityCheckStore.all() }
            if (!isAdded) return@launch
            showLatest(history.firstOrNull())
            showHistory(history)
        }
    }

    private fun showLatest(check: IntegrityCheck?) {
        binding.latestTime.text = check?.let { formatTime(it.timestampMs) } ?: getString(R.string.monitor_never_checked)
        binding.showJson.visibility = if (check == null) View.GONE else View.VISIBLE
        binding.showJson.setOnClickListener { check?.let(::showDetails) }

        val verdicts = check?.deviceVerdicts
        val hasVerdict = check?.level != null
        setVerdictRow(binding.verdictBasic, hasVerdict, verdicts, IntegrityCheck.Level.BASIC)
        setVerdictRow(binding.verdictDevice, hasVerdict, verdicts, IntegrityCheck.Level.DEVICE)
        setVerdictRow(binding.verdictStrong, hasVerdict, verdicts, IntegrityCheck.Level.STRONG)

        val status = check?.takeIf { !hasVerdict }?.let(::statusText)
        binding.latestStatus.text = status
        binding.latestStatus.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    private fun setVerdictRow(view: TextView, hasVerdict: Boolean, verdicts: Set<String>?, level: IntegrityCheck.Level) {
        val passed = hasVerdict && verdicts.orEmpty().contains(level.label)
        val (icon, color) = when {
            !hasVerdict -> R.drawable.baseline_help_outline_24 to themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            passed -> R.drawable.baseline_check_circle_24 to themeColor(androidx.appcompat.R.attr.colorPrimary)
            else -> R.drawable.baseline_cancel_24 to themeColor(androidx.appcompat.R.attr.colorError)
        }
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color))
    }

    private fun showHistory(history: List<IntegrityCheck>) {
        binding.historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        binding.clearHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        val list = binding.historyList
        list.removeAllViews()
        for (check in history) {
            val item = ItemIntegrityCheckBinding.inflate(layoutInflater, list, false)
            val level = check.level
            item.title.text = level?.label ?: statusText(check)
            item.subtitle.text = formatTime(check.timestampMs)
            setLevelIcon(item.icon, level)
            item.root.setOnClickListener { showDetails(check) }
            list.addView(item.root)
        }
    }

    private fun setLevelIcon(view: ImageView, level: IntegrityCheck.Level?) {
        @DrawableRes val icon: Int
        val color: Int
        when (level) {
            null -> {
                icon = R.drawable.baseline_help_outline_24
                color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            IntegrityCheck.Level.NONE -> {
                icon = R.drawable.baseline_cancel_24
                color = themeColor(androidx.appcompat.R.attr.colorError)
            }
            else -> {
                icon = R.drawable.baseline_check_circle_24
                color = themeColor(androidx.appcompat.R.attr.colorPrimary)
            }
        }
        view.setImageResource(icon)
        view.imageTintList = ColorStateList.valueOf(color)
    }

    private fun statusText(check: IntegrityCheck): String = when (check.status) {
        IntegrityCheck.Status.OK -> check.backendError
            ?.let { getString(R.string.monitor_status_backend_error, it) }
            ?: getString(R.string.monitor_status_server_error)
        IntegrityCheck.Status.INTEGRITY_ERROR -> {
            val code = check.integrityErrorCode ?: 0
            getString(R.string.monitor_status_integrity_error, IntegrityCheck.integrityErrorName(code), code)
        }
        IntegrityCheck.Status.SERVER_ERROR -> getString(R.string.monitor_status_server_error)
        IntegrityCheck.Status.INTERNAL_ERROR -> getString(R.string.monitor_status_internal_error)
        IntegrityCheck.Status.NOT_HOOKED -> getString(R.string.monitor_status_not_hooked)
        IntegrityCheck.Status.CHECKER_NOT_INSTALLED -> getString(R.string.monitor_status_checker_missing)
        IntegrityCheck.Status.UNREACHABLE -> getString(R.string.monitor_status_unreachable)
    }

    private fun showDetails(check: IntegrityCheck) {
        val text = buildString {
            append(formatTime(check.timestampMs)).append('\n')
            if (check.level == null) append(statusText(check)).append("\n\n")
            append(check.prettyPayload)
        }.trim()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.monitor_details_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.monitor_copy) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("integrity_check", check.prettyPayload))
                showToast(R.string.monitor_copied)
            }
            .show()
            .findViewById<TextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    private fun formatTime(timestampMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestampMs))

    private companion object {
        const val CHECKER_DOWNLOAD_URL = "https://github.com/1nikolas/play-integrity-checker-app/releases"
    }
}
