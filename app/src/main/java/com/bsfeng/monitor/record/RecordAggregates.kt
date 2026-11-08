package com.bsfeng.monitor.record

import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.RecordType

/** 一种已归并的类型 + 条数（顺序 = 该类型在区间内首次出现的时间序）。 */
data class RecordTypeCount(val type: RecordType, val count: Int)

/** 一种 label + 条数（顺序 = 该 label 在区间内首次出现的时间序）。 */
data class LabelCount(val label: String, val count: Int)

/**
 * 记录表的 SQL 聚合读口（概览页用）。
 *
 * 为什么单独一个文件：概览每 5 秒刷一次，旧实现按窗口调 `queryByDate` 把整段 Record
 * （含 detail JSON 反序列化）拉回内存再数；这里全部下推到 SQL，只回聚合后的几十行。
 * 只接受 [DatabaseHelper]，不依赖 Android 上下文，Robolectric 单测可直接开库对拍。
 *
 * 类型过滤按「规范名 + [RecordType.LEGACY_ALIASES] 中映射到它的历史名」走 IN 列表，
 * 与读路径 [RecordType.fromStored] 对老数据的抢救口径一致；彻底不可识别、只能靠 action
 * 反推的坏行（v5 迁移已尽量改写成规范名）不计入聚合，属已知且可接受的偏差。
 */
class RecordAggregates(private val db: DatabaseHelper) {

    /** 区间内条数；[type] / [action] 为空表示不限。 */
    fun count(start: Long, end: Long, type: RecordType? = null, action: String? = null): Int {
        val args = mutableListOf<String>()
        val where = whereRange(start, end, type, action, args)
        return db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM records WHERE " + where, args.toTypedArray())
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    /** 区间内某类型的 duration 总和（毫秒）；无记录返回 0。 */
    fun sumDurationMs(start: Long, end: Long, type: RecordType): Long {
        val args = mutableListOf<String>()
        val where = whereRange(start, end, type, null, args)
        return db.readableDatabase
            .rawQuery("SELECT COALESCE(SUM(duration), 0) FROM records WHERE " + where, args.toTypedArray())
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    /** 按 [dayStarts] 切桶的条数（[dayStarts] 升序，桶 i = [dayStarts[i], dayStarts[i+1])，末桶到 [end]）。 */
    fun dailyCounts(
        dayStarts: LongArray,
        end: Long,
        type: RecordType? = null,
        action: String? = null,
    ): IntArray = bucketCounts(dayStarts, end, type, action)

    /**
     * 按自然日切桶的「活跃分钟」：`SUM(duration / 60000)`。
     *
     * 必须是 SQL 整数除法而不是「先 SUM 再除」：旧实现逐条 `duration / 60_000` 截断后相加，
     * 两条 30 秒记录算 0 分钟。换口径会让概览 7/30 天柱状图的数字整体变大。
     */
    fun dailyUsageMinutes(dayStarts: LongArray, end: Long): LongArray =
        bucketUsageMinutes(dayStarts, end)

    /** 滚动 24 小时逐小时条数（[hourStarts] 升序，桶 i = [hourStarts[i], hourStarts[i+1])，末桶到 [end]）。 */
    fun hourlyCounts(hourStarts: LongArray, end: Long): IntArray =
        bucketCounts(hourStarts, end, null, null)

    /** 区间内按「类型」聚合的条数（已把历史混淆名归并到规范类型）。 */
    fun typeCounts(start: Long, end: Long): List<RecordTypeCount> {
        val args = mutableListOf<String>()
        val where = whereRange(start, end, null, null, args)
        val merged = LinkedHashMap<RecordType, Int>()
        db.readableDatabase.rawQuery(
            "SELECT type, action, COUNT(*) AS c, MIN(timestamp) AS first FROM records WHERE " + where +
                " GROUP BY type, action ORDER BY first ASC, MIN(rowid) ASC",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0)
                val action = cursor.getString(1)
                val resolved = RecordType.fromStored(raw, action) ?: RecordType.SCREEN
                merged[resolved] = (merged[resolved] ?: 0) + cursor.getInt(2)
            }
        }
        return merged.map { RecordTypeCount(it.key, it.value) }
    }

    /** 区间内（可按类型过滤）某 action 的 label 分组计数（顺序 = label 首次出现的时间序）。 */
    fun labelCounts(
        start: Long,
        end: Long,
        type: RecordType?,
        action: String,
    ): List<LabelCount> {
        val args = mutableListOf<String>()
        val where = whereRange(start, end, type, action, args)
        val result = mutableListOf<LabelCount>()
        db.readableDatabase.rawQuery(
            "SELECT label, COUNT(*) AS c, MIN(timestamp) AS first FROM records WHERE " + where +
                " GROUP BY label ORDER BY first ASC, MIN(rowid) ASC",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LabelCount(cursor.getString(0).orEmpty(), cursor.getInt(1))
            }
        }
        return result
    }

    // ─────────────────────────── 内部 SQL 拼装 ───────────────────────────

    private fun bucketCounts(
        boundaries: LongArray,
        end: Long,
        type: RecordType?,
        action: String?,
    ): IntArray {
        require(boundaries.isNotEmpty()) { "桶边界不能为空" }
        val result = IntArray(boundaries.size)
        val args = mutableListOf<String>()
        val where = whereRange(boundaries[0], end, type, action, args)
        db.readableDatabase.rawQuery(
            "SELECT " + bucketCase(boundaries) + " AS bucket, COUNT(*) FROM records WHERE " + where +
                " GROUP BY bucket",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getInt(0)
                if (index in result.indices) result[index] = cursor.getInt(1)
            }
        }
        return result
    }

    private fun bucketUsageMinutes(boundaries: LongArray, end: Long): LongArray {
        require(boundaries.isNotEmpty()) { "桶边界不能为空" }
        val result = LongArray(boundaries.size)
        val args = mutableListOf<String>()
        val where = whereRange(boundaries[0], end, RecordType.APP_USAGE, null, args)
        // SUM(整数 / 整数) 在 SQLite 里就是整数除法，与旧实现逐条截断同口径
        db.readableDatabase.rawQuery(
            "SELECT " + bucketCase(boundaries) + " AS bucket, SUM(duration / 60000) FROM records WHERE " +
                where + " GROUP BY bucket",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getInt(0)
                if (index in result.indices) result[index] = cursor.getLong(1)
            }
        }
        return result
    }

    /** CASE 桶号：从后往前比边界，保证落到最大的 i（= 所在自然日 / 小时的桶）。 */
    private fun bucketCase(boundaries: LongArray): String {
        if (boundaries.size == 1) return "0"
        val case = StringBuilder("CASE")
        for (i in boundaries.size - 1 downTo 1) {
            case.append(" WHEN timestamp >= ").append(boundaries[i]).append(" THEN ").append(i)
        }
        return case.append(" ELSE 0 END").toString()
    }

    private fun whereRange(
        start: Long,
        end: Long,
        type: RecordType?,
        action: String?,
        args: MutableList<String>,
    ): String {
        args += start.toString()
        args += end.toString()
        val where = StringBuilder("timestamp >= ? AND timestamp <= ?")
        if (type != null) {
            val names = rawNamesOf(type)
            where.append(" AND type IN (").append(names.joinToString(",") { "?" }).append(")")
            names.forEach { args += it }
        }
        if (action != null) {
            where.append(" AND action = ?")
            args += action
        }
        return where.toString()
    }

    /** 规范名 + 历史混淆名：与读路径 `fromStored` 对老数据的识别范围一致。 */
    private fun rawNamesOf(type: RecordType): List<String> =
        listOf(RecordType.toString(type)) +
            RecordType.LEGACY_ALIASES.filterValues { it == type }.keys
}
