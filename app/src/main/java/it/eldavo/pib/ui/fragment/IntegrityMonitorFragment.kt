package it.eldavo.pib.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.children
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.playintegritybreak.common.IntegrityAutomationPhase
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
import it.eldavo.pib.monitor.IntegrityAutomation
import it.eldavo.pib.monitor.IntegrityCheck
import it.eldavo.pib.monitor.IntegrityCheckStore
import it.eldavo.pib.monitor.IntegrityMonitor
import it.eldavo.pib.monitor.KeyboxInstaller
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

        binding.checkNow.setOnClickListener { IntegrityMonitor.checkInBackground() }
        binding.clearHistory.setOnClickListener {
            IntegrityCheckStore.clear()
            refresh()
        }

        setupPeriodicControls()
        setupAutomationControls()

        // The check runs outside of this screen, from the button or the periodic job. Every time the screen starts, and when
        // a check ends, the history is reread.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                IntegrityMonitor.checking.collect { running ->
                    binding.checkNow.isEnabled = !running
                    binding.checkProgress.visibility = if (running) View.VISIBLE else View.GONE
                    if (!running) refresh()
                }
            }
        }
    }

    private fun setupPeriodicControls() {
        binding.periodicSwitch.isChecked = PrefManager.integrityMonitorEnabled
        binding.periodicSwitch.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityMonitorEnabled = isChecked
            IntegrityMonitor.syncSchedule()
            if (isChecked) RequestAlert.requestPermission(requireActivity())
        }
    }

    private fun setupAutomationControls() {
        binding.automationNotifyDrop.isChecked = PrefManager.integrityAutomationNotifyDrop
        binding.automationNotifyDrop.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityAutomationNotifyDrop = isChecked
            if (isChecked) RequestAlert.requestPermission(requireActivity())
        }

        binding.automationNotifyRecover.isChecked = PrefManager.integrityAutomationNotifyRecover
        binding.automationNotifyRecover.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityAutomationNotifyRecover = isChecked
            if (isChecked) RequestAlert.requestPermission(requireActivity())
        }

        binding.automationSyntheticErrors.isChecked = PrefManager.integrityAutomationSyntheticErrors
        binding.automationSyntheticErrors.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityAutomationSyntheticErrors = isChecked
            // Turning it on while the verdict is down applies it now; turning it off restores the apps' settings now.
            IntegrityAutomation.reconcile(requireContext())
        }

        binding.automationKeybox.isChecked = PrefManager.integrityAutomationKeybox
        binding.automationKeybox.setOnCheckedChangeListener { switch, isChecked ->
            if (!isChecked) {
                PrefManager.integrityAutomationKeybox = false
                // The errors that came on because no keybox gave STRONG go off now.
                IntegrityAutomation.reconcile(requireContext())
                return@setOnCheckedChangeListener
            }
            // The action needs root and TrickyStore. Both are checked before it is turned on, and the switch goes back off
            // without them.
            viewLifecycleOwner.lifecycleScope.launch {
                val ready = withContext(Dispatchers.IO) { KeyboxInstaller.hasRoot() && KeyboxInstaller.moduleInstalled() }
                // Turned off again during the wait: that stands.
                if (!switch.isChecked) return@launch
                if (ready) {
                    PrefManager.integrityAutomationKeybox = true
                    // Turning it on is a request now: a run that failed earlier (for example, before root was granted)
                    // should not make it wait out its cooldown, nor count as given up before the new run tries a keybox.
                    PrefManager.integrityKeyboxLastFailedMs = 0L
                    PrefManager.integrityKeyboxExhausted = false
                    IntegrityAutomation.reconcile(requireContext())
                } else {
                    switch.isChecked = false
                    showToast(R.string.automation_keybox_needs_root)
                }
            }
        }
        binding.automationSyntheticIfNoKeybox.isChecked = PrefManager.integrityAutomationSyntheticIfNoKeybox
        binding.automationSyntheticIfNoKeybox.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.integrityAutomationSyntheticIfNoKeybox = isChecked
            // Applies now when the keybox action has already run out of keyboxes while the verdict is down.
            IntegrityAutomation.reconcile(requireContext())
        }
        showAutomationStatus()
    }

    private fun showAutomationStatus() {
        val phase = PrefManager.integrityAutomationPhase
        // The actions mean nothing before the device has passed STRONG once, so they cannot be changed until then.
        for (view in binding.automationActions.children) view.isEnabled = phase != IntegrityAutomationPhase.UNARMED
        binding.automationStatus.setText(
            when (phase) {
                IntegrityAutomationPhase.UNARMED -> R.string.automation_status_unarmed
                IntegrityAutomationPhase.PASSING -> R.string.automation_status_passing
                IntegrityAutomationPhase.DEGRADED -> R.string.automation_status_degraded
            },
        )
    }

    private fun refresh() {
        showAutomationStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { IntegrityCheckStore.all() }
            showLatest(history.firstOrNull())
            showHistory(history)
        }
    }

    private fun showLatest(check: IntegrityCheck?) {
        binding.latestTime.text = check?.let { formatTime(it.timestampMs) } ?: getString(R.string.monitor_never_checked)

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
        IntegrityCheck.Status.OK -> getString(R.string.monitor_status_no_verdict)
        IntegrityCheck.Status.PLAY_STORE_ERROR -> getString(R.string.monitor_status_play_store_error, check.payload)
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
}
