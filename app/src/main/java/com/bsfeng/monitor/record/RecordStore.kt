package com.bsfeng.monitor.record

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.json.JSONObject
import java.util.UUID

class RecordStore(private val db: DatabaseHelper) {

    fun insert(record: Record) {
        val cv = ContentValues().apply {
            put("id", record.id)
            put("type", RecordType.Companion.toString(record.type))
            put("action", record.action)
            put("timestamp", record.timestamp)
            record.duration?.let { put("duration", it) }
            put("label", record.label)
            put("detail", record.detail?.let { JSONObject(it).toString() })
            put("owner", record.owner)
        }
        db.writableDatabase.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun queryByDate(dateStart: Long, dateEnd: Long): List<Record> {
        return query("timestamp >= ? AND timestamp <= ?", arrayOf(dateStart.toString(), dateEnd.toString()))
    }

    fun queryByType(type: RecordType, dateStart: Long, dateEnd: Long): List<Record> {
        val typeName = RecordType.Companion.toString(type)
        return query("type = ? AND timestamp >= ? AND timestamp <= ?",
            arrayOf(typeName, dateStart.toString(), dateEnd.toString()))
    }

    fun getDailySummary(dateStart: Long, dateEnd: Long): DailySummary {
        val db = db.readableDatabase
        // count screen ON events
        val screenOnCount = db.rawQuery(
            "SELECT COUNT(*) FROM records WHERE type='SCREEN' AND action='ON' AND timestamp >= ? AND timestamp <= ?",
            arrayOf(dateStart.toString(), dateEnd.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val chargeRecords = query("type='CHARGING' AND timestamp >= ? AND timestamp <= ?",
            arrayOf(dateStart.toString(), dateEnd.toString()))
        val chargeCount = chargeRecords.count { it.action == "STARTED" }

        val topApps = db.rawQuery(
            """SELECT label, SUM(COALESCE(duration,0)) as total_ms, COUNT(*) as cnt
               FROM records WHERE type='APP_USAGE' AND action='CLOSED'
               AND timestamp >= ? AND timestamp <= ?
               GROUP BY label ORDER BY total_ms DESC LIMIT 10""",
            arrayOf(dateStart.toString(), dateEnd.toString())
        ).use { cursor ->
            val list = mutableListOf<AppUsageStat>()
            while (cursor.moveToNext()) {
                list.add(AppUsageStat(
                    packageName = cursor.getString(0),
                    appName = cursor.getString(0),
                    totalMinutes = cursor.getLong(1) / 60000,
                    openCount = cursor.getInt(2)
                ))
            }
            list
        }

        val wifiChanges = db.rawQuery(
            "SELECT COUNT(*) FROM records WHERE type='WIFI' AND timestamp >= ? AND timestamp <= ?",
            arrayOf(dateStart.toString(), dateEnd.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        return DailySummary(
            totalScreenOnMinutes = screenOnCount * 5L,  // 估算值，精确计算需相邻事件时间差
            chargeCount = chargeCount,
            topApps = topApps,
            wifiChanges = wifiChanges
        )
    }

    fun getAppUsageRank(dateStart: Long, dateEnd: Long): List<AppUsageStat> {
        val db = db.readableDatabase
        return db.rawQuery(
            """SELECT label, SUM(COALESCE(duration,0)) as total_ms, COUNT(*) as cnt
               FROM records WHERE type='APP_USAGE' AND action='CLOSED'
               AND timestamp >= ? AND timestamp <= ?
               GROUP BY label ORDER BY total_ms DESC""",
            arrayOf(dateStart.toString(), dateEnd.toString())
        ).use { cursor ->
            val list = mutableListOf<AppUsageStat>()
            while (cursor.moveToNext()) {
                list.add(AppUsageStat(
                    packageName = cursor.getString(0),
                    appName = cursor.getString(0),
                    totalMinutes = cursor.getLong(1) / 60000,
                    openCount = cursor.getInt(2)
                ))
            }
            list
        }
    }

    fun deleteBefore(timestamp: Long) {
        db.writableDatabase.delete("records", "timestamp < ?", arrayOf(timestamp.toString()))
    }

    fun deleteByRange(start: Long, end: Long) {
        db.writableDatabase.delete("records", "timestamp >= ? AND timestamp <= ?",
            arrayOf(start.toString(), end.toString()))
    }

    fun deleteByAction(action: String) {
        db.writableDatabase.delete("records", "action = ?", arrayOf(action))
    }

    private fun query(selection: String, args: Array<String>): List<Record> {
        val db = db.readableDatabase
        return db.query("records", null, selection, args, null, null, "timestamp ASC").use { cursor ->
            val list = mutableListOf<Record>()
            while (cursor.moveToNext()) {
                val detailStr = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                list.add(Record(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    type = RecordType.fromString(cursor.getString(cursor.getColumnIndexOrThrow("type"))) ?: RecordType.SCREEN,
                    action = cursor.getString(cursor.getColumnIndexOrThrow("action")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    duration = if (cursor.isNull(cursor.getColumnIndexOrThrow("duration"))) null
                        else cursor.getLong(cursor.getColumnIndexOrThrow("duration")),
                    label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
                    detail = if (detailStr.isNullOrBlank()) null
                        else JSONObject(detailStr).keys().asSequence().associateWith { JSONObject(detailStr).optString(it) },
                    owner = cursor.getString(cursor.getColumnIndexOrThrow("owner"))
                ))
            }
            list
        }
    }
}
