package com.bsfeng.monitor.data

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.util.DiagnosticCounters

/**
 * 替换 Room 的系统内置 SQLite 数据库帮助类
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "monitor.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_EVENTS = "events"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                eventId TEXT PRIMARY KEY,
                eventType TEXT NOT NULL,
                timestampMs INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                extraJson TEXT NOT NULL,
                isUploaded INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_events_uploaded ON $TABLE_EVENTS(isUploaded)")
        db.execSQL("CREATE INDEX idx_events_timestamp ON $TABLE_EVENTS(timestampMs)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
        }
    }

    // ========== Insert ==========

    fun insert(event: EventEntity) {
        DiagnosticCounters.dbInsertCalledCount++
        Log.d("DatabaseHelper", "insert: eventId=${event.eventId}, type=${event.eventType}, ts=${event.timestampMs}")
        try {
            val db = writableDatabase
            val result = db.insertWithOnConflict(TABLE_EVENTS, null, event.toContentValues(), SQLiteDatabase.CONFLICT_IGNORE)
            if (result == -1L) {
                DiagnosticCounters.dbInsertConflictCount++
                Log.w("DatabaseHelper", "insert: CONFLICT_IGNORE returned -1, row not inserted (duplicate eventId?)")
            } else {
                DiagnosticCounters.dbInsertSuccessCount++
                Log.d("DatabaseHelper", "insert: success, rowId=$result")
            }
        } catch (e: Exception) {
            DiagnosticCounters.dbInsertFailCount++
            DiagnosticCounters.dbLastException = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("DatabaseHelper", "insert: FAILED", e)
        }
    }

    fun insertAll(events: List<EventEntity>) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (event in events) {
                db.insertWithOnConflict(TABLE_EVENTS, null, event.toContentValues(), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ========== Query ==========

    fun getUnsentEvents(limit: Int = 50): List<EventEntity> {
        return queryEvents(
            selection = "isUploaded = 0",
            orderBy = "timestampMs ASC",
            limit = limit
        )
    }

    fun getRecentEvents(limit: Int = 50): List<EventEntity> {
        return queryEvents(
            orderBy = "timestampMs DESC",
            limit = limit
        )
    }

    fun countUnsent(): Int {
        return count("isUploaded = 0")
    }

    fun countByTypeSince(since: Long, type: String): Int {
        return if (type.isEmpty()) {
            count("timestampMs > ?", arrayOf(since.toString()))
        } else {
            count("timestampMs > ? AND eventType = ?", arrayOf(since.toString(), type))
        }
    }

    // ========== Update / Delete ==========

    fun markUploaded(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.execSQL(
            "UPDATE $TABLE_EVENTS SET isUploaded = 1 WHERE eventId IN ($placeholders)",
            ids.toTypedArray()
        )
    }

    /** 将今日所有事件重新标记为未上传，用于手动重发 */
    fun resetTodayUploadStatus() {
        val now = System.currentTimeMillis()
        val todayStart = now - (now % 86400000L)
        val db = writableDatabase
        db.execSQL("UPDATE $TABLE_EVENTS SET isUploaded = 0 WHERE timestampMs >= ?", arrayOf(todayStart.toString()))
    }

    fun cleanOldEvents(before: Long) {
        writableDatabase.delete(
            TABLE_EVENTS,
            "isUploaded = 1 AND createdAt < ?",
            arrayOf(before.toString())
        )
    }

    /** 一键清空所有事件 */
    fun deleteAllEvents() {
        val db = writableDatabase
        db.delete(TABLE_EVENTS, null, null)
        Log.d("DatabaseHelper", "deleteAllEvents: all events cleared")
    }

    // ========== Internal ==========

    private fun queryEvents(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String? = null,
        limit: Int = 0
    ): List<EventEntity> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS, null, selection, selectionArgs,
            null, null, orderBy, if (limit > 0) limit.toString() else null
        )
        return cursor.toEventList()
    }

    private fun count(where: String, args: Array<String> = emptyArray()): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS WHERE $where", args)
        cursor.use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun Cursor.toEventList(): List<EventEntity> {
        val events = mutableListOf<EventEntity>()
        use { c ->
            while (c.moveToNext()) {
                events.add(
                    EventEntity(
                        eventId = c.getString(c.getColumnIndexOrThrow("eventId")),
                        eventType = c.getString(c.getColumnIndexOrThrow("eventType")),
                        timestampMs = c.getLong(c.getColumnIndexOrThrow("timestampMs")),
                        deviceId = c.getString(c.getColumnIndexOrThrow("deviceId")),
                        summary = c.getString(c.getColumnIndexOrThrow("summary")),
                        extraJson = c.getString(c.getColumnIndexOrThrow("extraJson")),
                        isUploaded = c.getInt(c.getColumnIndexOrThrow("isUploaded")) == 1,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt"))
                    )
                )
            }
        }
        return events
    }

    private fun EventEntity.toContentValues(): ContentValues = ContentValues().apply {
        put("eventId", eventId)
        put("eventType", eventType)
        put("timestampMs", timestampMs)
        put("deviceId", deviceId)
        put("summary", summary)
        put("extraJson", extraJson)
        put("isUploaded", if (isUploaded) 1 else 0)
        put("createdAt", createdAt)
    }
}
