package icu.nullptr.playintegritybreak.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.service.ConfigManager

class IntentApiReceiver : BroadcastReceiver() {

    private companion object {
        private const val TAG = "IntentApiReceiver"
        private val PACKAGE_NAME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.INTENT_API_ACTION_SET_APP_SETTING) {
            reply(
                status = Constants.INTENT_API_STATUS_INVALID_ACTION,
                message = "Unsupported action",
            )
            return
        }

        if (!ConfigManager.intentApiEnabled) {
            reply(
                status = Constants.INTENT_API_STATUS_API_DISABLED,
                message = "Intent API is disabled in settings",
            )
            return
        }

        val extras = intent.extras ?: Bundle.EMPTY
        val targetPackage = extras.getString(Constants.INTENT_API_EXTRA_TARGET_PACKAGE)?.trim().orEmpty()
        if (targetPackage.isEmpty()) {
            reply(
                status = Constants.INTENT_API_STATUS_MISSING_EXTRA,
                message = "Missing ${Constants.INTENT_API_EXTRA_TARGET_PACKAGE}",
            )
            return
        }

        if (targetPackage != Constants.DEFAULT_APP_PACKAGE_NAME && !PACKAGE_NAME_PATTERN.matches(targetPackage)) {
            reply(
                status = Constants.INTENT_API_STATUS_INVALID_PACKAGE,
                message = "Invalid target package",
                targetPackage = targetPackage,
            )
            return
        }

        val settingKey = extras.getString(Constants.INTENT_API_EXTRA_SETTING_KEY)?.trim().orEmpty()
        if (settingKey.isEmpty()) {
            reply(
                status = Constants.INTENT_API_STATUS_MISSING_EXTRA,
                message = "Missing ${Constants.INTENT_API_EXTRA_SETTING_KEY}",
                targetPackage = targetPackage,
            )
            return
        }

        runCatching {
            if (targetPackage == Constants.DEFAULT_APP_PACKAGE_NAME) {
                applyDefaultSettingValue(extras, settingKey)
            } else {
                val appConfig = buildBaseConfig(targetPackage)
                applyPerAppSettingValue(extras, settingKey, appConfig)
                ConfigManager.setAppConfig(targetPackage, appConfig)
            }
        }.onSuccess {
            reply(
                status = Constants.INTENT_API_STATUS_APPLIED,
                message = "Setting applied",
                targetPackage = targetPackage,
                settingKey = settingKey,
            )
        }.onFailure { error ->
            val (status, message) = when (error) {
                is MissingValueException -> Constants.INTENT_API_STATUS_MISSING_EXTRA to (error.message ?: "Missing value")
                is InvalidValueException -> Constants.INTENT_API_STATUS_INVALID_VALUE to (error.message ?: "Invalid value")
                is InvalidKeyException -> Constants.INTENT_API_STATUS_INVALID_KEY to (error.message ?: "Unsupported setting key")
                else -> Constants.INTENT_API_STATUS_INTERNAL_ERROR to "Failed to apply setting"
            }
            Log.w(TAG, "Intent API apply failed for key=$settingKey package=$targetPackage", error)
            reply(
                status = status,
                message = message,
                targetPackage = targetPackage,
                settingKey = settingKey,
            )
        }
    }

    private fun buildBaseConfig(targetPackage: String): JsonConfig.AppConfig {
        val existing = ConfigManager.getAppConfig(targetPackage)
        if (existing != null) {
            return existing.copy()
        }

        return JsonConfig.AppConfig(
            interventionEnabled = ConfigManager.defaultInterventionEnabled,
            rewriteIntegrityResponseOverridden = false,
            rewriteIntegrityResponse = ConfigManager.defaultHookRewriteEnabled,
            rewriteIntegrityErrorCode = ConfigManager.defaultHookRewriteErrorCode,
            rewriteIntegrityErrorRemediable = ConfigManager.defaultHookRewriteRemediable,
            deliverSyntheticResponse = ConfigManager.defaultDeliverSyntheticResponse,
            delaySyntheticResponseDelivery = ConfigManager.defaultDelaySyntheticResponseDelivery,
        )
    }

    private fun applyPerAppSettingValue(extras: Bundle, settingKey: String, appConfig: JsonConfig.AppConfig) {
        when (settingKey) {
            Constants.INTENT_API_KEY_ENABLE_INTERVENTION -> {
                appConfig.interventionEnabled = requireBooleanValue(extras)
            }

            Constants.INTENT_API_KEY_ENABLE_LOGGER -> {
                val value = requireBooleanValue(extras)
                appConfig.interventionEnabled = true
                appConfig.rewriteIntegrityResponseOverridden = true
                appConfig.rewriteIntegrityResponse = value
            }

            Constants.INTENT_API_KEY_DELIVER_SYNTHETIC_RESPONSE -> {
                val value = requireBooleanValue(extras)
                appConfig.interventionEnabled = true
                appConfig.deliverSyntheticResponse = value
            }

            Constants.INTENT_API_KEY_DELAY_SYNTHETIC_RESPONSE -> {
                val value = requireBooleanValue(extras)
                appConfig.interventionEnabled = true
                appConfig.delaySyntheticResponseDelivery = value
            }

            Constants.INTENT_API_KEY_REWRITE_ERROR_CODE -> {
                val value = requireIntValue(extras)
                appConfig.interventionEnabled = true
                appConfig.rewriteIntegrityResponseOverridden = true
                appConfig.rewriteIntegrityErrorCode = value
            }

            Constants.INTENT_API_KEY_REWRITE_ERROR_REMEDIABLE -> {
                val value = requireBooleanValue(extras)
                appConfig.interventionEnabled = true
                appConfig.rewriteIntegrityResponseOverridden = true
                appConfig.rewriteIntegrityErrorRemediable = value
            }

            else -> {
                throw InvalidKeyException("Unsupported setting key: $settingKey")
            }
        }
    }

    private fun applyDefaultSettingValue(extras: Bundle, settingKey: String) {
        var interventionEnabled = ConfigManager.defaultInterventionEnabled
        var rewriteEnabled = ConfigManager.defaultHookRewriteEnabled
        var errorCode = ConfigManager.defaultHookRewriteErrorCode
        var remediable = ConfigManager.defaultHookRewriteRemediable
        var deliverSyntheticResponse = ConfigManager.defaultDeliverSyntheticResponse
        var delaySyntheticResponseDelivery = ConfigManager.defaultDelaySyntheticResponseDelivery

        when (settingKey) {
            Constants.INTENT_API_KEY_ENABLE_INTERVENTION -> {
                interventionEnabled = requireBooleanValue(extras)
            }

            Constants.INTENT_API_KEY_ENABLE_LOGGER -> {
                rewriteEnabled = requireBooleanValue(extras)
            }

            Constants.INTENT_API_KEY_DELIVER_SYNTHETIC_RESPONSE -> {
                deliverSyntheticResponse = requireBooleanValue(extras)
            }

            Constants.INTENT_API_KEY_DELAY_SYNTHETIC_RESPONSE -> {
                delaySyntheticResponseDelivery = requireBooleanValue(extras)
            }

            Constants.INTENT_API_KEY_REWRITE_ERROR_CODE -> {
                errorCode = requireIntValue(extras)
            }

            Constants.INTENT_API_KEY_REWRITE_ERROR_REMEDIABLE -> {
                remediable = requireBooleanValue(extras)
            }

            else -> {
                throw InvalidKeyException("Unsupported setting key: $settingKey")
            }
        }

        ConfigManager.setDefaultPolicyConfig(
            interventionEnabled = interventionEnabled,
            rewriteEnabled = rewriteEnabled,
            errorCode = errorCode,
            remediable = remediable,
            deliverSyntheticResponse = deliverSyntheticResponse,
            delaySyntheticResponseDelivery = delaySyntheticResponseDelivery,
        )
    }

    private fun requireBooleanValue(extras: Bundle): Boolean {
        if (!extras.containsKey(Constants.INTENT_API_EXTRA_BOOLEAN_VALUE)) {
            throw MissingValueException("Missing ${Constants.INTENT_API_EXTRA_BOOLEAN_VALUE}")
        }

        return runCatching {
            extras.getBoolean(Constants.INTENT_API_EXTRA_BOOLEAN_VALUE)
        }.getOrElse {
            throw InvalidValueException("${Constants.INTENT_API_EXTRA_BOOLEAN_VALUE} must be a boolean")
        }
    }

    private fun requireIntValue(extras: Bundle): Int {
        if (!extras.containsKey(Constants.INTENT_API_EXTRA_INT_VALUE)) {
            throw MissingValueException("Missing ${Constants.INTENT_API_EXTRA_INT_VALUE}")
        }

        return runCatching {
            extras.getInt(Constants.INTENT_API_EXTRA_INT_VALUE)
        }.getOrElse {
            throw InvalidValueException("${Constants.INTENT_API_EXTRA_INT_VALUE} must be an int")
        }
    }

    private fun reply(
        status: Int,
        message: String,
        targetPackage: String? = null,
        settingKey: String? = null,
    ) {
        val result = Bundle().apply {
            putInt(Constants.INTENT_API_RESULT_STATUS, status)
            putString(Constants.INTENT_API_RESULT_MESSAGE, message)
            if (!targetPackage.isNullOrEmpty()) {
                putString(Constants.INTENT_API_RESULT_TARGET_PACKAGE, targetPackage)
            }
            if (!settingKey.isNullOrEmpty()) {
                putString(Constants.INTENT_API_RESULT_SETTING_KEY, settingKey)
            }
        }

        setResult(status, message, result)
    }

    private class MissingValueException(message: String) : IllegalArgumentException(message)

    private class InvalidValueException(message: String) : IllegalArgumentException(message)

    private class InvalidKeyException(message: String) : IllegalArgumentException(message)
}
