package icu.nullptr.playintegritybreak.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import icu.nullptr.playintegritybreak.common.Constants
import icu.nullptr.playintegritybreak.common.JsonConfig
import icu.nullptr.playintegritybreak.common.PolicyKey
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
            val key = PolicyKey.fromKey(settingKey)
                ?: throw InvalidKeyException("Unsupported setting key: $settingKey")
            val value: Any = if (key.isInt) requireIntValue(extras) else requireBooleanValue(extras)
            applySetting(targetPackage, key, value)
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

    private fun applySetting(targetPackage: String, key: PolicyKey, value: Any) {
        if (targetPackage == Constants.DEFAULT_APP_PACKAGE_NAME) {
            ConfigManager.defaults = key.with(ConfigManager.defaults, value)
            return
        }

        var appConfig = key.with(ConfigManager.getAppConfig(targetPackage) ?: JsonConfig.AppConfig(), value)
        // Changing how a request is answered only makes sense if PIB intervenes for the app.
        if (key.impliesIntervention) {
            appConfig = PolicyKey.INTERVENTION.with(appConfig, true)
        }
        ConfigManager.setAppConfig(targetPackage, appConfig)
    }

    private fun requireBooleanValue(extras: Bundle): Boolean =
        requireValue(extras, Constants.INTENT_API_EXTRA_BOOLEAN_VALUE, "a boolean")

    private fun requireIntValue(extras: Bundle): Int =
        requireValue(extras, Constants.INTENT_API_EXTRA_INT_VALUE, "an int")

    // Bundle.getBoolean/getInt return a default on a type mismatch, so check the raw value instead.
    private inline fun <reified T> requireValue(extras: Bundle, key: String, typeName: String): T {
        if (!extras.containsKey(key)) {
            throw MissingValueException("Missing $key")
        }

        @Suppress("DEPRECATION")
        return extras.get(key) as? T ?: throw InvalidValueException("$key must be $typeName")
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
