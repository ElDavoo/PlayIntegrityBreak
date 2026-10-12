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
 * a failed send is not retried. [TelemetryEventPayload.success] tells whether the check got a verdict.
 */
object IntegrityTelemetry {
    private const val TAG = "IntegrityTelemetry"
    private const val EVENT_TYPE = "integrity"
    private const val SOURCE = "integrity-monitor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sends [check]. A check without a verdict is sent only with [withoutVerdict]: in a keybox run, each keybox tried is
     * sent, so that one the Play Store gave no verdict for still shows.
     */
    fun sendVerdict(check: IntegrityCheck, withoutVerdict: Boolean = false) {
        val level = check.level
        if (level == null && !withoutVerdict) return
        if (!ConfigManager.telemetryEnabled) return
        // Read now, not in the upload: the fallback can install the next keybox before the upload runs.
        val keyboxId = KeyboxInstaller.installedIdWithoutAsking()
        scope.launch {
            val event = TelemetryEventPayload(
                id = 0,
                timestampMs = check.timestampMs,
                userId = ConfigManager.userId.ifEmpty { null },
                packageName = Constants.VENDING_PACKAGE_NAME,
                eventType = EVENT_TYPE,
                source = SOURCE,
                success = level != null,
                verdict = level?.label,
                keyboxId = keyboxId,
            )
            val outcome = TelemetryApiClient.uploadBatch(
                TelemetryBatchPayload(batchId = "integrity-${UUID.randomUUID()}", events = listOf(event)),
            )
            if (!outcome.accepted) Log.w(TAG, "Verdict not sent: ${outcome.errorMessage}")
        }
    }
}
