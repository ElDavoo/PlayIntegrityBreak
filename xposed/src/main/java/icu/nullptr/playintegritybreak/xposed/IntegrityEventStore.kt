package icu.nullptr.playintegritybreak.xposed

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object IntegrityEventStore {
    private const val DB_NAME = "integrity_events.db"
    private const val DB_VERSION = 1

    private const val TABLE_EVENTS = "events"
    private const val COL_ID = "_id"
    private const val COL_TS = "ts"
    private const val COL_PACKAGE = "package_name"
    private const val COL_EVENT_TYPE = "event_type"
    private const val COL_SUCCESS = "success"
    private const val COL_ERROR_CODE = "error_code"
    private const val COL_RETRIABLE = "retriable"
    private const val COL_SOURCE = "source"

    private const val EVENT_TYPE_REQUEST = "request"
    private const val EVENT_TYPE_RESPONSE = "response"

    @Volatile
    private var helper: EventDbHelper? = null

    private fun getHelper(app: Application): EventDbHelper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: EventDbHelper(app).also { helper = it }
        }
    }

    fun recordRequest(app: Application, packageName: String) {
        insertEvent(
            app = app,
            packageName = packageName,
            eventType = EVENT_TYPE_REQUEST,
            success = null,
            errorCode = null,
            retriable = null,
            source = "request-intercepted",
        )
    }

    fun recordResponse(
        app: Application,
        packageName: String,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        insertEvent(
            app = app,
            packageName = packageName,
            eventType = EVENT_TYPE_RESPONSE,
            success = success,
            errorCode = errorCode,
            retriable = retriable,
            source = source,
        )
    }

    fun countEvents(app: Application): Int {
        return runCatching {
            val db = getHelper(app).readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null).use { cursor ->
                if (!cursor.moveToFirst()) return@use 0
                cursor.getInt(0)
            }
        }.getOrElse {
            logW("IntegrityEventStore", "Failed to count events", it)
            0
        }
    }

    fun clear(app: Application) {
        runCatching {
            val db = getHelper(app).writableDatabase
            db.delete(TABLE_EVENTS, null, null)
        }.onFailure {
            logW("IntegrityEventStore", "Failed to clear events", it)
        }
    }

    private fun insertEvent(
        app: Application,
        packageName: String,
        eventType: String,
        success: Boolean?,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        runCatching {
            val db = getHelper(app).writableDatabase
            val values = ContentValues().apply {
                put(COL_TS, System.currentTimeMillis())
                put(COL_PACKAGE, packageName)
                put(COL_EVENT_TYPE, eventType)
                if (success == null) putNull(COL_SUCCESS) else put(COL_SUCCESS, if (success) 1 else 0)
                if (errorCode == null) putNull(COL_ERROR_CODE) else put(COL_ERROR_CODE, errorCode)
                if (retriable == null) putNull(COL_RETRIABLE) else put(COL_RETRIABLE, if (retriable) 1 else 0)
                put(COL_SOURCE, source)
            }
            db.insert(TABLE_EVENTS, null, values)
        }.onFailure {
            logW("IntegrityEventStore", "Failed to record event", it)
        }
    }

    private class EventDbHelper(app: Application) :
        SQLiteOpenHelper(app.applicationContext, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TS INTEGER NOT NULL,
                    $COL_PACKAGE TEXT NOT NULL,
                    $COL_EVENT_TYPE TEXT NOT NULL,
                    $COL_SUCCESS INTEGER,
                    $COL_ERROR_CODE INTEGER,
                    $COL_RETRIABLE INTEGER,
                    $COL_SOURCE TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON $TABLE_EVENTS($COL_TS)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_pkg ON $TABLE_EVENTS($COL_PACKAGE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_type ON $TABLE_EVENTS($COL_EVENT_TYPE)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == newVersion) return
            db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
            onCreate(db)
        }
    }
}
