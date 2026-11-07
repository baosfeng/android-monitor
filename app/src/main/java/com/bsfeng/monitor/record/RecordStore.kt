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

    /**
     * 区间内的记录条数（只 COUNT，不把行读回内存）。
     *
     * 为什么不复用 [queryByDate]：那里每行都要反序列化 detail 的 JSON；桌面组件每次刷新都走这条路径，
     * 事件表上万行时耗时从毫秒级涨到百毫秒级，直接体现在「组件刷新要顿一下」。
     */
    fun countByDate(dateStart: Long, dateEnd: Long): Int = countWhere(
        "timestamp >= ? AND timestamp <= ?",
        arrayOf(dateStart.toString(), dateEnd.toString()),
    )

    /** 类型 + action 的区间计数（AD_SKIPPED 这类高频定向统计）。 */
    fun countByTypeAndAction(type: RecordType, action: String, dateStart: Long, dateEnd: Long): Int = countWhere(
        "type = ? AND action = ? AND timestamp >= ? AND timestamp <= ?",
        arrayOf(RecordType.Companion.toString(type), action, dateStart.toString(), dateEnd.toString()),
    )

    private fun countWhere(selection: String, args: Array<String>): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE " + selection, args)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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
                val action = cursor.getString(cursor.getColumnIndexOrThrow("action"))
                val rawType = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                list.add(Record(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    // 规范名 → 历史混淆名 → 按 action 推断；全不认识才走兜底并记一次日志
                    type = RecordType.fromStored(rawType, action)
                        ?: UnknownStoredType.fallback(rawType, action),
                    action = action,
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    duration = if (cursor.isNull(cursor.getColumnIndexOrThrow("duration"))) null
                        else cursor.getLong(cursor.getColumnIndexOrThrow("duration")),
                    label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
                    // 单次解析：keys() 与取值共用同一个 JSONObject（原实现每行 new 了 keys+1 次）。
                    // 解析失败按 null 处理：detail 可能来自备份还原 / 旧版写入，一条坏行不该让整页查询抛异常。
                    detail = parseDetail(detailStr),
                    owner = cursor.getString(cursor.getColumnIndexOrThrow("owner"))
                ))
            }
            list
        }
    }
}

/**
 * 解析 `records.detail` 的 JSON。
 *
 * 失败返回 null 而不是抛：detail 不是本进程独占写出来的 —— 备份还原会整库替换成用户选的 zip、
 * v5 迁移按启发式重写过、旧版本也可能留下非 JSON 值。一条坏行不该让 [RecordStore.queryByDate]
 * 整体抛异常，那会把实时页 / 概览 / 行程一起打成空白。
 *
 * 每个不同的坏值只报一次 W 日志（照 [UnknownStoredType] 的去重思路），避免高频查询刷屏。
 */
private fun parseDetail(raw: String?): Map<String, String>? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val obj = JSONObject(raw)
        obj.keys().asSequence().associateWith { obj.optString(it) }
    }.getOrElse { e ->
        val key = raw.take(64)
        if (brokenDetailKeys.putIfAbsent(key, true) == null) {
            android.util.Log.w("RecordStore", "detail 不是合法 JSON，按空值处理（此值只报一次）", e)
        }
        null
    }
}

/** 已报过警的坏 detail 前缀。查询在多个 IO 线程上跑，用并发容器；超过 32 条就不再记（日志本就够用）。 */
private val brokenDetailKeys = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/**
 * 库里的 `type` 既不规范、也推不出类型时的最后兜底。
 *
 * 为什么不直接抛：`Record.type` 是非空 [RecordType]，而 UI 侧对 [RecordType] 的 `when`
 * 是穷尽的（图标/文案/配色），新增 `UNKNOWN` 子类型会让那些表漏项。所以这里记日志 + 记计数，
 * 再落到 SCREEN —— **不静默**：每个不同的 (type, action) 组合只报一次，避免高频轮询刷屏。
 */
private object UnknownStoredType {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun fallback(raw: String?, action: String): RecordType {
        val key = "${raw.orEmpty()}|$action"
        if (seen.putIfAbsent(key, true) == null) {
            android.util.Log.w(
                "RecordStore",
                "无法识别的记录类型：type=$raw action=$action → 按 SCREEN 兜底（此组合只报一次）",
            )
        }
        return RecordType.SCREEN
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
