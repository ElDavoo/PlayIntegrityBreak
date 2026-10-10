package icu.nullptr.playintegritybreak.core.playstore

/** The log tag of the Play Store check. */
internal const val LOG_TAG = "PIB-PlayStoreCheck"

/** This Play Store build does not have what the check needs, or has it in a shape the check does not know. */
internal open class Unsupported(message: String) : Exception(message)
