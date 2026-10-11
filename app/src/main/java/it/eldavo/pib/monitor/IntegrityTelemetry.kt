package it.eldavo.pib.monitor

import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.TelemetryBatchPayload
import icu.nullptr.playintegritybreak.common.TelemetryEventPayload
import icu.nullptr.playintegritybreak.service.ConfigManager
import icu.nullptr.playintegritybreak.telemetry.TelemetryApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Sends the verdict of each check, with the ID of the keybox the device had, to the telemetry server. It is sent at once
 * and not queued, since a verdict only means something while it is current. Nothing is sent without telemetry on, and
 * a failed send is not retried.
 */
object IntegrityTelemetry {
    private const val TAG = "IntegrityTelemetry"
    private const val EVENT_TYPE = "integrity"
    private const val SOURCE = "integrity-monitor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendVerdict(check: IntegrityCheck) {
        val level = check.level ?: return
        if (!ConfigManager.telemetryEnabled) return
        // Read now, not in the upload: the fallback can install the next keybox before the upload runs. The root shell is
        // used only when root was granted already, so that a check never asks for it.
        val keyboxId = if (KeyboxInstaller.rootGranted()) KeyboxInstaller.installedId() else null
        scope.launch {
            val event = TelemetryEventPayload(
                id = 0,
                timestampMs = check.timestampMs,
                userId = ConfigManager.userId.ifEmpty { null },
                packageName = Constants.VENDING_PACKAGE_NAME,
                eventType = EVENT_TYPE,
                source = SOURCE,
                verdict = level.label,
                keyboxId = keyboxId,
            )
            val outcome = TelemetryApiClient.uploadBatch(
                TelemetryBatchPayload(batchId = "integrity-${UUID.randomUUID()}", events = listOf(event)),
            )
            if (!outcome.accepted) Log.w(TAG, "Verdict not sent: ${outcome.errorMessage}")
        }
    }
}
