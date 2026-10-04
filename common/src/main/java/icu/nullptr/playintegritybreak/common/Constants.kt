package icu.nullptr.playintegritybreak.common

import it.eldavo.pib.common.BuildConfig

object Constants {
    const val PROVIDER_AUTHORITY = "${BuildConfig.APP_PACKAGE_NAME}.ServiceProvider"
    const val PROVIDER_METHOD_LINK = "link"
    const val PROVIDER_METHOD_HEALTHCHECK = "healthcheck"
    const val PROVIDER_METHOD_PUBLISH_EVENT = "publishEvent"
    const val PROVIDER_EXTRA_BINDER = "binder"
    const val PROVIDER_EXTRA_EVENT_TIMESTAMP_MS = "eventTimestampMs"
    const val PROVIDER_EXTRA_EVENT_PACKAGE = "eventPackage"
    const val PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MAJOR = "eventPlayIntegrityVersionMajor"
    const val PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_MINOR = "eventPlayIntegrityVersionMinor"
    const val PROVIDER_EXTRA_EVENT_PLAY_INTEGRITY_VERSION_PATCH = "eventPlayIntegrityVersionPatch"
    const val PROVIDER_EXTRA_EVENT_TYPE = "eventType"
    const val PROVIDER_EXTRA_EVENT_SUCCESS = "eventSuccess"
    const val PROVIDER_EXTRA_EVENT_ERROR_CODE = "eventErrorCode"
    const val PROVIDER_EXTRA_EVENT_RETRIABLE = "eventRetriable"
    const val PROVIDER_EXTRA_EVENT_SOURCE = "eventSource"
    const val PROVIDER_EXTRA_EVENT_USER_ID = "eventUserId"
    const val PROVIDER_RESULT_OK = "ok"

    const val INTENT_API_ACTION_SET_APP_SETTING = "${BuildConfig.APP_PACKAGE_NAME}.action.SET_APP_SETTING"
    const val INTENT_API_EXTRA_TARGET_PACKAGE = "targetPackage"
    const val INTENT_API_EXTRA_SETTING_KEY = "settingKey"
    const val INTENT_API_EXTRA_BOOLEAN_VALUE = "booleanValue"
    const val INTENT_API_EXTRA_INT_VALUE = "intValue"
    const val INTENT_API_RESULT_STATUS = "status"
    const val INTENT_API_RESULT_MESSAGE = "message"
    const val INTENT_API_RESULT_TARGET_PACKAGE = "targetPackage"
    const val INTENT_API_RESULT_SETTING_KEY = "settingKey"
    const val INTENT_API_STATUS_APPLIED = 0
    const val INTENT_API_STATUS_API_DISABLED = 1
    const val INTENT_API_STATUS_INVALID_ACTION = 2
    const val INTENT_API_STATUS_MISSING_EXTRA = 3
    const val INTENT_API_STATUS_INVALID_PACKAGE = 4
    const val INTENT_API_STATUS_INVALID_KEY = 5
    const val INTENT_API_STATUS_INVALID_VALUE = 6
    const val INTENT_API_STATUS_INTERNAL_ERROR = 7

    const val DEFAULT_APP_PACKAGE_NAME = "default"
    const val GMS_PACKAGE_NAME = "com.google.android.gms"
    const val GSF_PACKAGE_NAME = "com.google.android.gsf"
    const val VENDING_PACKAGE_NAME = "com.android.vending"
    const val ANDROID_PACKAGE_NAME = "android"

    val gmsPackages = arrayOf(GMS_PACKAGE_NAME, GSF_PACKAGE_NAME)
    val riskyPackages = arrayOf(VENDING_PACKAGE_NAME) + gmsPackages

    val packagesExcludedFromSelection = setOf(
        "android",
        "android.media",
        "android.uid.system",
        "android.uid.shell",
        "android.uid.systemui",
        "com.android.permissioncontroller",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.providers.settings",
        "com.google.android.webview",
        "com.google.android.providers.media.module"
    )
}
