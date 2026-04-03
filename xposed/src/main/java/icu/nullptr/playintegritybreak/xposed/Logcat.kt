package icu.nullptr.playintegritybreak.xposed

import android.util.Log
import de.robv.android.xposed.XposedBridge
import it.eldavo.pib_oss.common.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun parseLog(level: Int, tag: String, msg: String, cause: Throwable? = null) = buildString {
    val levelStr = when (level) {
        Log.VERBOSE -> "VERBS"
        Log.DEBUG   -> "DEBUG"
        Log.INFO    -> " INFO"
        Log.WARN    -> " WARN"
        Log.ERROR   -> "ERROR"
        else        -> "?WTF?"
    }
    val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    append("[$levelStr] $date ($tag) $msg")
    if (!endsWith('\n')) append('\n')
    if (cause != null) append(Log.getStackTraceString(cause))
    if (!endsWith('\n')) append('\n')
}

fun logWithLevel(level: Int, tag: String, msg: String, cause: Throwable? = null) {
    val errorOnly = PIBLoggerService.isErrorOnlyLogging()
    val detailLogEnabled = PIBLoggerService.isDetailLogging()

    if (level != Log.ERROR && errorOnly) return
    if (level <= Log.DEBUG && !detailLogEnabled) return
    if (level == Log.VERBOSE && !BuildConfig.DEBUG) return
    val parsedLog = parseLog(level, tag, msg, cause)

    if (PIBLoggerService.isActive()) {
        PIBLoggerService.appendParsedLog(parsedLog)
        XposedBridge.log("[PIB] $parsedLog")
        return
    }

    XposedBridge.log("[PIB] $parsedLog")
}

fun logV(tag: String, msg: String, cause: Throwable? = null) = logWithLevel(Log.VERBOSE, tag, msg, cause)
fun logD(tag: String, msg: String, cause: Throwable? = null) = logWithLevel(Log.DEBUG, tag, msg, cause)
fun logI(tag: String, msg: String, cause: Throwable? = null) = logWithLevel(Log.INFO, tag, msg, cause)
fun logW(tag: String, msg: String, cause: Throwable? = null) = logWithLevel(Log.WARN, tag, msg, cause)
fun logE(tag: String, msg: String, cause: Throwable? = null) = logWithLevel(Log.ERROR, tag, msg, cause)
