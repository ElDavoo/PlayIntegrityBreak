package icu.nullptr.playintegritybreak.telemetry

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import icu.nullptr.playintegritybreak.common.TelemetryBatchPayload
import icu.nullptr.playintegritybreak.common.TelemetryEventPayload
import icu.nullptr.playintegritybreak.common.TelemetryPackageStat
import icu.nullptr.playintegritybreak.common.TelemetryQueueSnapshot
import icu.nullptr.playintegritybreak.common.TelemetryQueueState
import icu.nullptr.playintegritybreak.common.TelemetryStatsPayload
import icu.nullptr.playintegritybreak.pibApp
import icu.nullptr.playintegritybreak.service.ConfigManager
import java.util.UUID

object AppIntegrityEventStore {
    private const val DB_NAME = "integrity_events.db"
    private const val DB_VERSION = 4

    private const val TABLE_EVENTS = "events"
    private const val COL_ID = "_id"
    private const val COL_TS = "ts"
    private const val COL_USER_ID = "user_id"
    private const val COL_PACKAGE = "package_name"
    private const val COL_PLAY_INTEGRITY_VERSION_MAJOR = "play_integrity_version_major"
    private const val COL_PLAY_INTEGRITY_VERSION_MINOR = "play_integrity_version_minor"
    private const val COL_PLAY_INTEGRITY_VERSION_PATCH = "play_integrity_version_patch"
    private const val COL_EVENT_TYPE = "event_type"
    private const val COL_SUCCESS = "success"
    private const val COL_ERROR_CODE = "error_code"
    private const val COL_RETRIABLE = "retriable"
    private const val COL_SOURCE = "source"
    private const val COL_TELEMETRY_STATE = "telemetry_state"
    private const val COL_TELEMETRY_BATCH_ID = "telemetry_batch_id"
    private const val COL_TELEMETRY_ATTEMPT_COUNT = "telemetry_attempt_count"
    private const val COL_TELEMETRY_NEXT_ATTEMPT_TS = "telemetry_next_attempt_ts"
    private const val COL_TELEMETRY_LAST_ATTEMPT_TS = "telemetry_last_attempt_ts"
    private const val COL_TELEMETRY_ACKED_TS = "telemetry_acked_ts"
    private const val COL_TELEMETRY_SERVER_ACK_ID = "telemetry_server_ack_id"
    private const val COL_TELEMETRY_LAST_ERROR = "telemetry_last_error"

    private const val EVENT_TYPE_REQUEST = "request"
    private const val EVENT_TYPE_RESPONSE = "response"
    private const val MAX_BATCH_EVENTS = 500
    private const val MIN_BATCH_LEASE_MS = 5_000L
    private const val MAX_BATCH_LEASE_MS = 30 * 60_000L

    @Volatile
    private var helper: EventDbHelper? = null

    private fun getHelper(app: Application): EventDbHelper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: EventDbHelper(app).also { helper = it }
        }
    }

    fun recordRequest(packageName: String) {
        recordRequest(pibApp, packageName)
    }

    fun recordResponse(
        packageName: String,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        recordResponse(
            app = pibApp,
            packageName = packageName,
            playIntegrityVersionMajor = null,
            playIntegrityVersionMinor = null,
            playIntegrityVersionPatch = null,
            success = success,
            errorCode = errorCode,
            retriable = retriable,
            source = source,
        )
    }

    fun appendPublishedEvent(
        timestampMs: Long,
        packageName: String,
        playIntegrityVersionMajor: Int?,
        playIntegrityVersionMinor: Int?,
        playIntegrityVersionPatch: Int?,
        userId: String?,
        eventType: String,
        success: Boolean?,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ): Boolean {
        if (eventType != EVENT_TYPE_REQUEST && eventType != EVENT_TYPE_RESPONSE) {
            return false
        }

        return insertEvent(
            app = pibApp,
            packageName = packageName,
            playIntegrityVersionMajor = playIntegrityVersionMajor,
            playIntegrityVersionMinor = playIntegrityVersionMinor,
            playIntegrityVersionPatch = playIntegrityVersionPatch,
            userId = userId,
            eventType = eventType,
            success = success,
            errorCode = errorCode,
            retriable = retriable,
            source = source,
            timestampMs = timestampMs,
        )
    }

    fun recordRequest(app: Application, packageName: String) {
        insertEvent(
            app = app,
            packageName = packageName,
            playIntegrityVersionMajor = null,
            playIntegrityVersionMinor = null,
            playIntegrityVersionPatch = null,
            userId = ConfigManager.userId,
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
        playIntegrityVersionMajor: Int?,
        playIntegrityVersionMinor: Int?,
        playIntegrityVersionPatch: Int?,
        success: Boolean,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
    ) {
        insertEvent(
            app = app,
            packageName = packageName,
            playIntegrityVersionMajor = playIntegrityVersionMajor,
            playIntegrityVersionMinor = playIntegrityVersionMinor,
            playIntegrityVersionPatch = playIntegrityVersionPatch,
            userId = ConfigManager.userId,
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
            Log.w("IntegrityEventStore", "Failed to count events", it)
            0
        }
    }

    fun countEvents(): Int = countEvents(pibApp)

    fun clear(app: Application) {
        runCatching {
            val db = getHelper(app).writableDatabase
            db.delete(TABLE_EVENTS, null, null)
        }.onFailure {
            Log.w("IntegrityEventStore", "Failed to clear events", it)
        }
    }

    fun clear() {
        clear(pibApp)
    }

    fun recoverStaleInFlight(app: Application, staleBeforeTimestampMs: Long): Int {
        if (staleBeforeTimestampMs <= 0L) return 0
        return runCatching {
            val db = getHelper(app).writableDatabase
            val updates = ContentValues().apply {
                put(COL_TELEMETRY_STATE, TelemetryQueueState.RETRY)
                putNull(COL_TELEMETRY_BATCH_ID)
                put(COL_TELEMETRY_NEXT_ATTEMPT_TS, System.currentTimeMillis())
                put(COL_TELEMETRY_LAST_ERROR, "Recovered stale in-flight lease")
            }
            db.update(
                TABLE_EVENTS,
                updates,
                "$COL_TELEMETRY_STATE = ? AND $COL_TELEMETRY_LAST_ATTEMPT_TS <= ?",
                arrayOf(TelemetryQueueState.IN_FLIGHT, staleBeforeTimestampMs.toString()),
            )
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to recover stale in-flight events", it)
            0
        }
    }

    fun recoverStaleInFlight(staleBeforeTimestampMs: Long): Int {
        return recoverStaleInFlight(pibApp, staleBeforeTimestampMs)
    }

    fun dequeueTelemetryBatch(app: Application, maxEvents: Int, leaseDurationMs: Long): TelemetryBatchPayload {
        val batchSize = maxEvents.coerceIn(1, MAX_BATCH_EVENTS)
        val leaseMs = leaseDurationMs.coerceIn(MIN_BATCH_LEASE_MS, MAX_BATCH_LEASE_MS)

        return runCatching {
            val now = System.currentTimeMillis()
            val db = getHelper(app).writableDatabase

            db.beginTransaction()
            try {
                val selectedRows = mutableListOf<QueuedEvent>()
                db.rawQuery(
                    """
                                        SELECT $COL_ID, $COL_TS, $COL_USER_ID, $COL_PACKAGE, $COL_PLAY_INTEGRITY_VERSION_MAJOR, $COL_PLAY_INTEGRITY_VERSION_MINOR, $COL_PLAY_INTEGRITY_VERSION_PATCH, $COL_EVENT_TYPE, $COL_SUCCESS, $COL_ERROR_CODE, $COL_RETRIABLE, $COL_SOURCE, $COL_TELEMETRY_ATTEMPT_COUNT
                    FROM $TABLE_EVENTS
                    WHERE ($COL_TELEMETRY_STATE = ? OR $COL_TELEMETRY_STATE = ?)
                      AND $COL_TELEMETRY_NEXT_ATTEMPT_TS <= ?
                    ORDER BY $COL_TS ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        TelemetryQueueState.PENDING,
                        TelemetryQueueState.RETRY,
                        now.toString(),
                        batchSize.toString(),
                    ),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val ts = cursor.getLong(1)
                        val userId = cursor.getString(2)
                        val packageName = cursor.getString(3)
                        val playIntegrityVersionMajor = if (cursor.isNull(4)) null else cursor.getInt(4)
                        val playIntegrityVersionMinor = if (cursor.isNull(5)) null else cursor.getInt(5)
                        val playIntegrityVersionPatch = if (cursor.isNull(6)) null else cursor.getInt(6)
                        val eventType = cursor.getString(7)
                        val success = if (cursor.isNull(8)) null else cursor.getInt(8) == 1
                        val errorCode = if (cursor.isNull(9)) null else cursor.getInt(9)
                        val retriable = if (cursor.isNull(10)) null else cursor.getInt(10) == 1
                        val source = cursor.getString(11)
                        val attemptCount = cursor.getInt(12)

                        selectedRows += QueuedEvent(
                            id = id,
                            payload = TelemetryEventPayload(
                                id = id,
                                timestampMs = ts,
                                userId = userId,
                                packageName = packageName,
                                playIntegrityVersionMajor = playIntegrityVersionMajor,
                                playIntegrityVersionMinor = playIntegrityVersionMinor,
                                playIntegrityVersionPatch = playIntegrityVersionPatch,
                                eventType = eventType,
                                success = success,
                                errorCode = errorCode,
                                retriable = retriable,
                                source = source,
                                attemptCount = attemptCount,
                            ),
                        )
                    }
                }

                if (selectedRows.isEmpty()) {
                    db.setTransactionSuccessful()
                    return@runCatching TelemetryBatchPayload()
                }

                val batchId = "batch-$now-${UUID.randomUUID()}"
                selectedRows.forEach { row ->
                    db.execSQL(
                        """
                        UPDATE $TABLE_EVENTS
                        SET $COL_TELEMETRY_STATE = ?,
                            $COL_TELEMETRY_BATCH_ID = ?,
                            $COL_TELEMETRY_LAST_ATTEMPT_TS = ?,
                            $COL_TELEMETRY_ATTEMPT_COUNT = $COL_TELEMETRY_ATTEMPT_COUNT + 1,
                            $COL_TELEMETRY_LAST_ERROR = NULL
                        WHERE $COL_ID = ?
                        """.trimIndent(),
                        arrayOf<Any>(
                            TelemetryQueueState.IN_FLIGHT,
                            batchId,
                            now,
                            row.id,
                        ),
                    )
                }

                db.setTransactionSuccessful()

                TelemetryBatchPayload(
                    batchId = batchId,
                    leaseExpiresAtMs = now + leaseMs,
                    events = selectedRows.map { row ->
                        row.payload.copy(attemptCount = row.payload.attemptCount + 1)
                    },
                )
            } finally {
                db.endTransaction()
            }
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to dequeue telemetry batch", it)
            TelemetryBatchPayload()
        }
    }

    fun dequeueTelemetryBatch(maxEvents: Int, leaseDurationMs: Long): TelemetryBatchPayload {
        return dequeueTelemetryBatch(pibApp, maxEvents, leaseDurationMs)
    }

    fun ackTelemetryBatch(app: Application, batchId: String, serverAckId: String?): Int {
        if (batchId.isBlank()) return 0
        return runCatching {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COL_TELEMETRY_STATE, TelemetryQueueState.ACKED)
                put(COL_TELEMETRY_ACKED_TS, now)
                if (serverAckId.isNullOrBlank()) putNull(COL_TELEMETRY_SERVER_ACK_ID)
                else put(COL_TELEMETRY_SERVER_ACK_ID, serverAckId)
                putNull(COL_TELEMETRY_LAST_ERROR)
                put(COL_TELEMETRY_NEXT_ATTEMPT_TS, 0L)
            }
            getHelper(app).writableDatabase.update(
                TABLE_EVENTS,
                values,
                "$COL_TELEMETRY_BATCH_ID = ? AND $COL_TELEMETRY_STATE = ?",
                arrayOf(batchId, TelemetryQueueState.IN_FLIGHT),
            )
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to acknowledge telemetry batch", it)
            0
        }
    }

    fun ackTelemetryBatch(batchId: String, serverAckId: String?): Int {
        return ackTelemetryBatch(pibApp, batchId, serverAckId)
    }

    fun nackTelemetryBatch(
        app: Application,
        batchId: String,
        retriable: Boolean,
        nextAttemptTimestampMs: Long,
        lastError: String?,
    ): Int {
        if (batchId.isBlank()) return 0
        return runCatching {
            val now = System.currentTimeMillis()
            val updates = ContentValues().apply {
                put(COL_TELEMETRY_STATE, if (retriable) TelemetryQueueState.RETRY else TelemetryQueueState.FAILED)
                putNull(COL_TELEMETRY_BATCH_ID)
                put(COL_TELEMETRY_NEXT_ATTEMPT_TS, if (retriable) nextAttemptTimestampMs.coerceAtLeast(now) else 0L)
                put(COL_TELEMETRY_LAST_ATTEMPT_TS, now)
                if (lastError.isNullOrBlank()) putNull(COL_TELEMETRY_LAST_ERROR)
                else put(COL_TELEMETRY_LAST_ERROR, lastError.take(256))
            }
            getHelper(app).writableDatabase.update(
                TABLE_EVENTS,
                updates,
                "$COL_TELEMETRY_BATCH_ID = ? AND $COL_TELEMETRY_STATE = ?",
                arrayOf(batchId, TelemetryQueueState.IN_FLIGHT),
            )
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to reject telemetry batch", it)
            0
        }
    }

    fun nackTelemetryBatch(
        batchId: String,
        retriable: Boolean,
        nextAttemptTimestampMs: Long,
        lastError: String?,
    ): Int {
        return nackTelemetryBatch(pibApp, batchId, retriable, nextAttemptTimestampMs, lastError)
    }

    fun getTelemetryQueueSnapshot(app: Application): TelemetryQueueSnapshot {
        return runCatching {
            val db = getHelper(app).readableDatabase
            db.rawQuery(
                """
                SELECT
                    SUM(CASE WHEN $COL_TELEMETRY_STATE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_TELEMETRY_STATE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_TELEMETRY_STATE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_TELEMETRY_STATE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_TELEMETRY_STATE = ? THEN 1 ELSE 0 END)
                FROM $TABLE_EVENTS
                """.trimIndent(),
                arrayOf(
                    TelemetryQueueState.PENDING,
                    TelemetryQueueState.RETRY,
                    TelemetryQueueState.IN_FLIGHT,
                    TelemetryQueueState.ACKED,
                    TelemetryQueueState.FAILED,
                ),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use TelemetryQueueSnapshot()
                TelemetryQueueSnapshot(
                    pending = cursor.getNullableInt(0),
                    retry = cursor.getNullableInt(1),
                    inFlight = cursor.getNullableInt(2),
                    acknowledged = cursor.getNullableInt(3),
                    failed = cursor.getNullableInt(4),
                )
            }
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to read telemetry queue snapshot", it)
            TelemetryQueueSnapshot()
        }
    }

    fun getTelemetryQueueSnapshot(): TelemetryQueueSnapshot = getTelemetryQueueSnapshot(pibApp)

    fun getTelemetryStats(app: Application, fromTimestampMs: Long): TelemetryStatsPayload {
        val fromTs = fromTimestampMs.coerceAtLeast(0L)
        val generatedAt = System.currentTimeMillis()

        return runCatching {
            val db = getHelper(app).readableDatabase
            val totals = db.rawQuery(
                """
                SELECT
                    COUNT(*),
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? AND $COL_SUCCESS = 1 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? AND $COL_SUCCESS = 0 THEN 1 ELSE 0 END)
                FROM $TABLE_EVENTS
                WHERE $COL_TS >= ?
                """.trimIndent(),
                arrayOf(
                    EVENT_TYPE_REQUEST,
                    EVENT_TYPE_RESPONSE,
                    EVENT_TYPE_RESPONSE,
                    EVENT_TYPE_RESPONSE,
                    fromTs.toString(),
                ),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    Totals()
                } else {
                    Totals(
                        total = cursor.getNullableInt(0),
                        requests = cursor.getNullableInt(1),
                        responses = cursor.getNullableInt(2),
                        success = cursor.getNullableInt(3),
                        errors = cursor.getNullableInt(4),
                    )
                }
            }

            val topPackages = db.rawQuery(
                """
                SELECT
                    $COL_PACKAGE,
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? THEN 1 ELSE 0 END) AS req_count,
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? THEN 1 ELSE 0 END) AS resp_count,
                    SUM(CASE WHEN $COL_EVENT_TYPE = ? AND $COL_SUCCESS = 0 THEN 1 ELSE 0 END) AS err_count
                FROM $TABLE_EVENTS
                WHERE $COL_TS >= ?
                GROUP BY $COL_PACKAGE
                ORDER BY req_count DESC, resp_count DESC
                """.trimIndent(),
                arrayOf(
                    EVENT_TYPE_REQUEST,
                    EVENT_TYPE_RESPONSE,
                    EVENT_TYPE_RESPONSE,
                    fromTs.toString(),
                ),
            ).use { cursor ->
                val stats = mutableListOf<TelemetryPackageStat>()
                while (cursor.moveToNext()) {
                    stats += TelemetryPackageStat(
                        packageName = cursor.getString(0),
                        requestCount = cursor.getNullableInt(1),
                        responseCount = cursor.getNullableInt(2),
                        errorCount = cursor.getNullableInt(3),
                    )
                }
                stats
            }

            TelemetryStatsPayload(
                generatedAtMs = generatedAt,
                fromTimestampMs = fromTs,
                toTimestampMs = generatedAt,
                totalEvents = totals.total,
                totalRequests = totals.requests,
                totalResponses = totals.responses,
                totalSuccessResponses = totals.success,
                totalErrorResponses = totals.errors,
                queue = getTelemetryQueueSnapshot(app),
                topPackages = topPackages,
            )
        }.getOrElse {
            Log.w("IntegrityEventStore", "Failed to read telemetry statistics", it)
            TelemetryStatsPayload(
                generatedAtMs = generatedAt,
                fromTimestampMs = fromTs,
                toTimestampMs = generatedAt,
                queue = TelemetryQueueSnapshot(),
            )
        }
    }

    fun getTelemetryStats(fromTimestampMs: Long): TelemetryStatsPayload {
        return getTelemetryStats(pibApp, fromTimestampMs)
    }

    private fun insertEvent(
        app: Application,
        packageName: String,
        playIntegrityVersionMajor: Int?,
        playIntegrityVersionMinor: Int?,
        playIntegrityVersionPatch: Int?,
        userId: String?,
        eventType: String,
        success: Boolean?,
        errorCode: Int?,
        retriable: Boolean?,
        source: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            val db = getHelper(app).writableDatabase
            val values = ContentValues().apply {
                val now = timestampMs.coerceAtLeast(0L)
                val normalizedUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ConfigManager.userId.trim().takeIf { it.isNotEmpty() }
                put(COL_TS, now)
                if (normalizedUserId == null) putNull(COL_USER_ID) else put(COL_USER_ID, normalizedUserId)
                put(COL_PACKAGE, packageName)
                if (playIntegrityVersionMajor == null) putNull(COL_PLAY_INTEGRITY_VERSION_MAJOR) else put(COL_PLAY_INTEGRITY_VERSION_MAJOR, playIntegrityVersionMajor)
                if (playIntegrityVersionMinor == null) putNull(COL_PLAY_INTEGRITY_VERSION_MINOR) else put(COL_PLAY_INTEGRITY_VERSION_MINOR, playIntegrityVersionMinor)
                if (playIntegrityVersionPatch == null) putNull(COL_PLAY_INTEGRITY_VERSION_PATCH) else put(COL_PLAY_INTEGRITY_VERSION_PATCH, playIntegrityVersionPatch)
                put(COL_EVENT_TYPE, eventType)
                if (success == null) putNull(COL_SUCCESS) else put(COL_SUCCESS, if (success) 1 else 0)
                if (errorCode == null) putNull(COL_ERROR_CODE) else put(COL_ERROR_CODE, errorCode)
                if (retriable == null) putNull(COL_RETRIABLE) else put(COL_RETRIABLE, if (retriable) 1 else 0)
                put(COL_SOURCE, source)
                put(COL_TELEMETRY_STATE, TelemetryQueueState.PENDING)
                put(COL_TELEMETRY_NEXT_ATTEMPT_TS, now)
                put(COL_TELEMETRY_ATTEMPT_COUNT, 0)
            }
            db.insert(TABLE_EVENTS, null, values) != -1L
        }.onFailure {
            Log.w("IntegrityEventStore", "Failed to record event", it)
        }.getOrDefault(false)
    }

    private class EventDbHelper(app: Application) :
        SQLiteOpenHelper(app.applicationContext, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TS INTEGER NOT NULL,
                    $COL_USER_ID TEXT,
                    $COL_PACKAGE TEXT NOT NULL,
                    $COL_PLAY_INTEGRITY_VERSION_MAJOR INTEGER,
                    $COL_PLAY_INTEGRITY_VERSION_MINOR INTEGER,
                    $COL_PLAY_INTEGRITY_VERSION_PATCH INTEGER,
                    $COL_EVENT_TYPE TEXT NOT NULL,
                    $COL_SUCCESS INTEGER,
                    $COL_ERROR_CODE INTEGER,
                    $COL_RETRIABLE INTEGER,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_TELEMETRY_STATE TEXT NOT NULL DEFAULT '${TelemetryQueueState.PENDING}',
                    $COL_TELEMETRY_BATCH_ID TEXT,
                    $COL_TELEMETRY_ATTEMPT_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COL_TELEMETRY_NEXT_ATTEMPT_TS INTEGER NOT NULL DEFAULT 0,
                    $COL_TELEMETRY_LAST_ATTEMPT_TS INTEGER,
                    $COL_TELEMETRY_ACKED_TS INTEGER,
                    $COL_TELEMETRY_SERVER_ACK_ID TEXT,
                    $COL_TELEMETRY_LAST_ERROR TEXT
                )
                """.trimIndent()
            )
            createIndexes(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == newVersion) return
            if (oldVersion < 2) {
                addColumnIfMissing(db, COL_TELEMETRY_STATE, "TEXT NOT NULL DEFAULT '${TelemetryQueueState.PENDING}'")
                addColumnIfMissing(db, COL_TELEMETRY_BATCH_ID, "TEXT")
                addColumnIfMissing(db, COL_TELEMETRY_ATTEMPT_COUNT, "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, COL_TELEMETRY_NEXT_ATTEMPT_TS, "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, COL_TELEMETRY_LAST_ATTEMPT_TS, "INTEGER")
                addColumnIfMissing(db, COL_TELEMETRY_ACKED_TS, "INTEGER")
                addColumnIfMissing(db, COL_TELEMETRY_SERVER_ACK_ID, "TEXT")
                addColumnIfMissing(db, COL_TELEMETRY_LAST_ERROR, "TEXT")

                db.execSQL(
                    """
                    UPDATE $TABLE_EVENTS
                    SET $COL_TELEMETRY_STATE = '${TelemetryQueueState.PENDING}'
                    WHERE $COL_TELEMETRY_STATE IS NULL OR $COL_TELEMETRY_STATE = ''
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE $TABLE_EVENTS
                    SET $COL_TELEMETRY_NEXT_ATTEMPT_TS = $COL_TS
                    WHERE $COL_TELEMETRY_NEXT_ATTEMPT_TS = 0
                    """.trimIndent()
                )
            }

            if (oldVersion < 3) {
                addColumnIfMissing(db, COL_PLAY_INTEGRITY_VERSION_MAJOR, "INTEGER")
                addColumnIfMissing(db, COL_PLAY_INTEGRITY_VERSION_MINOR, "INTEGER")
                addColumnIfMissing(db, COL_PLAY_INTEGRITY_VERSION_PATCH, "INTEGER")
            }

            if (oldVersion < 4) {
                addColumnIfMissing(db, COL_USER_ID, "TEXT")
            }

            createIndexes(db)
        }

        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON $TABLE_EVENTS($COL_TS)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_pkg ON $TABLE_EVENTS($COL_PACKAGE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_type ON $TABLE_EVENTS($COL_EVENT_TYPE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_telemetry_state ON $TABLE_EVENTS($COL_TELEMETRY_STATE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_telemetry_next_attempt ON $TABLE_EVENTS($COL_TELEMETRY_NEXT_ATTEMPT_TS)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_telemetry_batch ON $TABLE_EVENTS($COL_TELEMETRY_BATCH_ID)")
        }

        private fun addColumnIfMissing(db: SQLiteDatabase, columnName: String, definition: String) {
            if (hasColumn(db, columnName)) return
            db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN $columnName $definition")
        }

        private fun hasColumn(db: SQLiteDatabase, columnName: String): Boolean {
            db.rawQuery("PRAGMA table_info($TABLE_EVENTS)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (columnName == cursor.getString(1)) {
                        return true
                    }
                }
            }
            return false
        }
    }

    private data class Totals(
        val total: Int = 0,
        val requests: Int = 0,
        val responses: Int = 0,
        val success: Int = 0,
        val errors: Int = 0,
    )

    private data class QueuedEvent(
        val id: Long,
        val payload: TelemetryEventPayload,
    )

    private fun android.database.Cursor.getNullableInt(index: Int): Int {
        if (isNull(index)) return 0
        return getInt(index)
    }
}
