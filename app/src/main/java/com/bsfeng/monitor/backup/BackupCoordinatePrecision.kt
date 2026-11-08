package com.bsfeng.monitor.backup

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.bsfeng.monitor.record.LocationPolicy
import java.io.File
import org.json.JSONObject

/**
 * 备份包里的定位坐标降精度（3 位小数 ≈110m），本机库保持 6 位（≈0.11m）。
 *
 * 为什么两套精度：轨迹要能画出几十米的移动，落库必须 6 位（见 [LocationPolicy.quantize]）；
 * 而备份包是**一个可被整份拿走的文件**，里面存着全天精确到 0.11m 的行踪就是另一回事了。
 * 精度在包里降到 3 位后，还原回来的轨迹仍够看「去了哪一片」，但不再是可精确复现的定位记录。
 *
 * 只动 records 表里 action = COORDINATE 的 detail（写入点见 RecordCollector.onLocation），
 * 其它行原文不动 —— 备份包与库的差异必须小到可以一眼解释。
 */
internal object BackupCoordinatePrecision {

    /** records 表名（定义在 data/DatabaseHelper，那里没公开常量）。 */
    private const val RECORDS_TABLE = "records"

    /** 定位点的 action 值，与 RecordCollector.onLocation 写入的一致。 */
    const val COORDINATE_ACTION = "COORDINATE"

    private const val TAG = "BackupCoordinate"

    /**
     * 单行 detail 降精度。
     *
     * @return 改写后的 detail；没有 lat/lng、值已经够粗、或 JSON 坏掉时返回 null（这一行不动）
     */
    fun degradeDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val lat = obj.optString("lat", "")
        val lng = obj.optString("lng", "")
        if (lat.isEmpty() && lng.isEmpty()) return null
        val coarseLat = degradeValue(lat)
        val coarseLng = degradeValue(lng)
        if (coarseLat == null && coarseLng == null) return null
        // 用 put 覆盖同名键：JSONObject 保留原有键序，其余字段（provider / accuracy_m / address）原样带过
        coarseLat?.let { obj.put("lat", it) }
        coarseLng?.let { obj.put("lng", it) }
        return obj.toString()
    }

    private fun degradeValue(value: String): String? {
        val number = value.trim().toDoubleOrNull() ?: return null
        val coarse = LocationPolicy.quantizeCoarse(number)
        return if (coarse == value.trim()) null else coarse
    }

    /**
     * 就地降精度 [dbFile]（快照副本，可以随意改写）。
     *
     * 不碰 sidecar：改写若落在 `-wal` 里，非空的 wal 会照常随同一份快照进包（还原端
     * 两者一起落盘）；只有 checkpoint 成功时 wal 才会变成 0 字节，从而自然不进包。
     *
     * @return 改写行数；-1 = 打不开或读写失败（调用方决定降级还是中止）
     */
    fun sanitize(dbFile: File): Int {
        if (!dbFile.isFile || dbFile.length() == 0L) return -1
        return runCatching {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            val rewritten = try {
                val count = rewrite(db)
                // 改写可能落在 -wal 里：尽力 checkpoint 并入主库（只为把包压小）。
                // 失败也不要紧 —— 非空的 -wal 会照常随快照进包，还原端两者一起落盘。
                runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close() }
                count
            } finally {
                db.close()
            }
            rewritten
        }.getOrElse {
            Log.w(TAG, "备份包坐标降精度失败：${it.message ?: it.javaClass.simpleName}")
            -1
        }
    }

    private fun rewrite(db: SQLiteDatabase): Int {
        val pending = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT id, detail FROM $RECORDS_TABLE WHERE action = ? AND detail IS NOT NULL",
            arrayOf(COORDINATE_ACTION),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // records.id 是 TEXT 主键：按 getLong 读会把「c1」这类 id 压成 0，UPDATE 一行都命中不了
                val id = cursor.getString(0)
                val degraded = degradeDetail(cursor.getString(1)) ?: continue
                pending += id to degraded
            }
        }
        if (pending.isEmpty()) return 0
        // KTX 事务：块正常返回即 commit、抛异常自动 rollback（等价于手写 begin/setSuccessful/finally）
        db.transaction {
            pending.forEach { (id, detail) ->
                execSQL(
                    "UPDATE $RECORDS_TABLE SET detail = ? WHERE id = ?",
                    arrayOf<Any>(detail, id),
                )
            }
        }
        return pending.size
    }
}
