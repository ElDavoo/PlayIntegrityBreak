package icu.nullptr.playintegritybreak.telemetry

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.ConfigManager
import java.util.concurrent.TimeUnit

object TelemetryUploadScheduler {
    private const val UNIQUE_PERIODIC_WORK = "telemetry.periodic.upload"
    private const val UNIQUE_IMMEDIATE_WORK = "telemetry.immediate.upload"
    private const val INPUT_REASON = "reason"

    fun syncSchedule(context: Context = pibApp) {
        val workManager = WorkManager.getInstance(context)
        if (!ConfigManager.telemetryEnabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
            return
        }

        val request = PeriodicWorkRequestBuilder<TelemetryUploadWorker>(
            ConfigManager.telemetryUploadIntervalMinutes.toLong(),
            TimeUnit.MINUTES,
        ).setConstraints(createConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(workDataOf(INPUT_REASON to "periodic"))
            .addTag(UNIQUE_PERIODIC_WORK)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun triggerImmediate(context: Context = pibApp, reason: String = "manual") {
        if (!ConfigManager.telemetryEnabled) {
            return
        }

        val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
            .setConstraints(createConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(workDataOf(INPUT_REASON to reason))
            .addTag(UNIQUE_IMMEDIATE_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun createConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(
                if (ConfigManager.telemetryWifiOnly) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            .build()
    }
}
