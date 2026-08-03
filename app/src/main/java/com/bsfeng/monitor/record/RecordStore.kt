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

    /**
     * 插入 APP_USAGE/USAGE 记录，如果 5 分钟内有同 App 的记录则合并（累加 duration）。
     * 其他类型（SCREEN、WIFI 等）正常插入。
     *
     * @return true 表示合并到了上一条记录（未新增），false 表示正常插入
     */
    fun insertOrMerge(record: Record): Boolean {
        if (record.type != RecordType.APP_USAGE || record.action != "USAGE") {
            insert(record)
            return false
        }
        // 查找 5 分钟内同 App 的上一条 USAGE 记录
        val fiveMinAgo = record.timestamp - 300_000L
        val lastSame = query(
            "type='APP_USAGE' AND action='USAGE' AND label = ? AND timestamp >= ? AND timestamp < ?",
            arrayOf(record.label, fiveMinAgo.toString(), record.timestamp.toString())
        ).lastOrNull()

        if (lastSame != null && record.timestamp - lastSame.timestamp < 300_000L) {
            // 合并：累加 duration 到上一条记录
            val newDuration = (lastSame.duration ?: 0L) + (record.duration ?: 0L)
            val cv = ContentValues().apply { put("duration", newDuration) }
            db.writableDatabase.update("records", cv, "id = ?", arrayOf(lastSame.id))
            return true
        }
        insert(record)
        return false
    }

    fun queryByDate(dateStart: Long, dateEnd: Long): List<Record> {
        return query("timestamp >= ? AND timestamp <= ?", arrayOf(dateStart.toString(), dateEnd.toString()))
    }

    fun queryByType(type: RecordType, dateStart: Long, dateEnd: Long): List<Record> {
        val typeName = RecordType.Companion.toString(type)
        return query("type = ? AND timestamp >= ? AND timestamp <= ?",
            arrayOf(typeName, dateStart.toString(), dateEnd.toString()))
    }

    /**
     * 按类型 + action 查询(SQL 层过滤,避免全量拉取后内存过滤,
     * 适用于 AD_SKIPPED 等高频记录类型的定向查询)。
     */
    fun queryByTypeAndAction(type: RecordType, action: String, dateStart: Long, dateEnd: Long): List<Record> {
        val typeName = RecordType.Companion.toString(type)
        return query("type = ? AND action = ? AND timestamp >= ? AND timestamp <= ?",
            arrayOf(typeName, action, dateStart.toString(), dateEnd.toString()))
    }

    fun getDailySummary(dateStart: Long, dateEnd: Long): DailySummary {
        val db = db.readableDatabase
        // 亮屏时长:UNLOCKED→OFF 配对计算,无配对时按 5 分钟估算
        val screenRecords = query(
            "type='SCREEN' AND (action='UNLOCKED' OR action='OFF') AND timestamp >= ? AND timestamp <= ?",
            arrayOf(dateStart.toString(), dateEnd.toString())
        )
        val totalScreenOnMinutes = computeScreenOnMillis(screenRecords) / 60_000L

        val chargeRecords = query("type='CHARGING' AND timestamp >= ? AND timestamp <= ?",
            arrayOf(dateStart.toString(), dateEnd.toString()))
        val chargeCount = chargeRecords.count { it.action == "STARTED" }

        val topApps = db.rawQuery(
            """SELECT label, SUM(COALESCE(duration,0)) as total_ms, COUNT(*) as cnt
               FROM records WHERE type='APP_USAGE' AND (action='CLOSED' OR action='USAGE')
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
            totalScreenOnMinutes = totalScreenOnMinutes,
            chargeCount = chargeCount,
            topApps = topApps,
            wifiChanges = wifiChanges
        )
    }

    fun getAppUsageRank(dateStart: Long, dateEnd: Long): List<AppUsageStat> {
        val db = db.readableDatabase
        return db.rawQuery(
            """SELECT label, SUM(COALESCE(duration,0)) as total_ms, COUNT(*) as cnt
               FROM records WHERE type='APP_USAGE' AND (action='CLOSED' OR action='USAGE')
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

/**
 * 根据 SCREEN 的 UNLOCKED/OFF 序列计算亮屏时长（毫秒）。
 *
 * 规则：
 * - UNLOCKED 标记一次亮屏开始，下一个 OFF 标记结束，时间差累加；
 * - 无配对 OFF 的 UNLOCKED（跨天/统计区间内未熄屏）与无配对 UNLOCKED 的 OFF
 *   （亮屏未解锁直接熄屏），按 [estimateMs] 估算。
 */
internal fun computeScreenOnMillis(records: List<Record>, estimateMs: Long = 5 * 60_000L): Long {
    var totalMs = 0L
    var lastUnlockTs: Long? = null
    var unmatched = 0
    for (record in records) {
        when (record.action) {
            "UNLOCKED" -> lastUnlockTs = record.timestamp
            "OFF" -> {
                val unlock = lastUnlockTs
                if (unlock != null) {
                    totalMs += (record.timestamp - unlock).coerceAtLeast(0L)
                    lastUnlockTs = null
                } else {
                    unmatched++
                }
            }
        }
    }
    if (lastUnlockTs != null) unmatched++
    return totalMs + unmatched * estimateMs
}
