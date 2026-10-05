package icu.nullptr.playintegritybreak.common

import icu.nullptr.playintegritybreak.common.JsonConfig.AppConfig
import icu.nullptr.playintegritybreak.common.JsonConfig.Policy

/**
 * Every policy setting, keyed by the string shared by the settings screen and the Intents API.
 * The keys are public API (see INTENTS_API.md), so they keep their historical names.
 */
enum class PolicyKey(val key: String, val isInt: Boolean = false, val impliesIntervention: Boolean = true) {
    INTERVENTION("enableIntervention", impliesIntervention = false),
    REWRITE("enableLogger"),
    REWRITE_ERROR_CODE("rewriteIntegrityErrorCode", isInt = true),
    REWRITE_REMEDIABLE("rewriteIntegrityErrorRemediable"),
    DELIVER_SYNTHETIC("deliverSyntheticResponse"),
    DELAY_SYNTHETIC("delaySyntheticResponseDelivery"),
    REQUEST_TOAST("integrityRequestToast", impliesIntervention = false);

    fun get(policy: Policy): Any = when (this) {
        INTERVENTION -> policy.interventionEnabled
        REWRITE -> policy.rewriteResponse
        REWRITE_ERROR_CODE -> policy.rewriteErrorCode
        REWRITE_REMEDIABLE -> policy.rewriteRemediable
        DELIVER_SYNTHETIC -> policy.deliverSyntheticResponse
        DELAY_SYNTHETIC -> policy.delaySyntheticResponse
        REQUEST_TOAST -> policy.requestToast
    }

    fun with(policy: Policy, value: Any): Policy = when (this) {
        INTERVENTION -> policy.copy(interventionEnabled = value as Boolean)
        REWRITE -> policy.copy(rewriteResponse = value as Boolean)
        REWRITE_ERROR_CODE -> policy.copy(rewriteErrorCode = value as Int)
        REWRITE_REMEDIABLE -> policy.copy(rewriteRemediable = value as Boolean)
        DELIVER_SYNTHETIC -> policy.copy(deliverSyntheticResponse = value as Boolean)
        DELAY_SYNTHETIC -> policy.copy(delaySyntheticResponse = value as Boolean)
        REQUEST_TOAST -> policy.copy(requestToast = value as Boolean)
    }

    /** Sets the override; null makes the app follow the default again. */
    fun with(app: AppConfig, value: Any?): AppConfig = when (this) {
        INTERVENTION -> app.copy(interventionEnabled = value as Boolean?)
        REWRITE -> app.copy(rewriteResponse = value as Boolean?)
        REWRITE_ERROR_CODE -> app.copy(rewriteErrorCode = value as Int?)
        REWRITE_REMEDIABLE -> app.copy(rewriteRemediable = value as Boolean?)
        DELIVER_SYNTHETIC -> app.copy(deliverSyntheticResponse = value as Boolean?)
        DELAY_SYNTHETIC -> app.copy(delaySyntheticResponse = value as Boolean?)
        REQUEST_TOAST -> app.copy(requestToast = value as Boolean?)
    }

    companion object {
        fun fromKey(key: String): PolicyKey? = entries.firstOrNull { it.key == key }
    }
}
