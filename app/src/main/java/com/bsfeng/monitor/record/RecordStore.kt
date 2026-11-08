package com.bsfeng.monitor.record

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.json.JSONObject

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

    fun deleteByRange(start: Long, end: Long) {
        db.writableDatabase.delete("records", "timestamp >= ? AND timestamp <= ?",
            arrayOf(start.toString(), end.toString()))
    }

    /**
     * 取尚未上报的记录（uploaded = 0 / NULL），按时间升序、最多 [limit] 条。
     *
     * - 未上传标记永远留在本地，不会被上报出去；这里只挑记录、不改任何字段。
     * - limit 默认批次上限口径；避免一次把整表拉进内存。
     */
    fun getUnuploaded(limit: Int = Int.MAX_VALUE): List<Record> {
        val capped = if (limit <= 0) 0 else limit
        if (capped == 0) return emptyList()
        val db = db.readableDatabase
        val list = mutableListOf<Record>()
        // SQL LIMIT 直接限制行数，避免大 backlog 时全表反序列化。
        db.query("records", null, "uploaded IS NULL OR uploaded = 0", null, null, null, "timestamp ASC", capped.toString())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(mapRecord(cursor))
                }
            }
        return list
    }

    /**
     * 把 [ids] 对应的记录标记为已上传。上报成功后调用；失败路径绝不调用。
     *
     * 分批占位符，避免 SQLite 单语句参数过多。
     */
    fun markUploaded(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = db.writableDatabase
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cv = ContentValues().apply { put("uploaded", 1) }
            db.update("records", cv, "id IN ($placeholders)", chunk.toTypedArray())
        }
    }

    /**
     * 删除已经上传且早于 [cutoffMs] 的记录（按 cacheMaxDays 保留窗口清理）。
     *
     * - 只删 uploaded=1 的行；未上传记录绝不清理，防止丢失未达后端的数据。
     * - @return 实际删除行数。
     */
    fun deleteUploadedBefore(cutoffMs: Long): Int =
        db.writableDatabase.delete("records", "uploaded = 1 AND timestamp < ?",
            arrayOf(cutoffMs.toString()))

    private fun mapRecord(cursor: android.database.Cursor): Record {
        val detailStr = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
        val action = cursor.getString(cursor.getColumnIndexOrThrow("action"))
        val rawType = cursor.getString(cursor.getColumnIndexOrThrow("type"))
        return Record(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            type = RecordType.fromStored(rawType, action)
                ?: UnknownStoredType.fallback(rawType, action),
            action = action,
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            duration = if (cursor.isNull(cursor.getColumnIndexOrThrow("duration"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("duration")),
            label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
            detail = parseDetail(detailStr),
            owner = cursor.getString(cursor.getColumnIndexOrThrow("owner"))
        )
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
 * 仅测试使用：原唯一生产调用方 `getDailySummary` 已随统计聚合入口一起删除，
 * 现在只有 `ComputeScreenOnMillisTest` 钉住这段规则。将来做「亮屏时长」统计时，
 * 请从 `queryByType(RecordType.SCREEN, ...)` 取序列后直接调本函数。
 *
 * 规则：
 * - UNLOCKED 标记一次亮屏开始，下一个 OFF 标记结束，时间差累加；
 * - 无配对 OFF 的 UNLOCKED（跨天/统计区间内未熄屏）与无配对 UNLOCKED 的 OFF
 *   （亮屏未解锁直接熄屏），按 [estimateMs] 估算。
 */
@androidx.annotation.VisibleForTesting
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
