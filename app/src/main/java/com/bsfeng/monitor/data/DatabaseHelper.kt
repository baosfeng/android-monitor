package com.bsfeng.monitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.bsfeng.monitor.domain.model.RecordType

/**
 * 数据库帮助类 — 仅负责表创建、升级和实例管理。
 *
 * 读写操作由 [com.bsfeng.monitor.record.RecordStore] 完成。
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "monitor.db"
        /** 公开给测试做「版本无关」断言（与 FinanceDatabaseHelper.DATABASE_VERSION 同口径）。 */
        const val DATABASE_VERSION = 6
        private const val TABLE_RECORDS = "records"

        /** 局域网互传任务历史表（v4 新增）。 */
        const val TABLE_TRANSFER_TASKS = "transfer_tasks"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
    }

    /** PRAGMA table_info 判断列是否存在（不依赖异常做流程控制）。 */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }

    override fun onCreate(db: SQLiteDatabase) {
        // 使用 IF NOT EXISTS：onUpgrade 可能对已存在 records 表的旧库调用 onCreate
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_RECORDS (
                id          TEXT PRIMARY KEY,
                type        TEXT NOT NULL,
                action      TEXT NOT NULL,
                timestamp   INTEGER NOT NULL,
                duration    INTEGER,
                label       TEXT NOT NULL,
                detail      TEXT,
                owner       TEXT DEFAULT ''
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_ts ON $TABLE_RECORDS(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_type ON $TABLE_RECORDS(type)")
        createTransferTaskTable(db)
    }

    /** 互传任务历史表（幂等创建，onCreate / onUpgrade 共用）。 */
    private fun createTransferTaskTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSFER_TASKS (
                session_id      TEXT NOT NULL,
                file_id         TEXT NOT NULL,
                direction       TEXT NOT NULL,
                peer_device_id  TEXT NOT NULL,
                peer_alias      TEXT NOT NULL,
                file_name       TEXT NOT NULL,
                file_size       INTEGER NOT NULL,
                sha256          TEXT DEFAULT '',
                mime            TEXT DEFAULT '',
                status          TEXT NOT NULL,
                received_bytes  INTEGER NOT NULL DEFAULT 0,
                uri             TEXT,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL,
                PRIMARY KEY (session_id, file_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_transfer_tasks_created ON $TABLE_TRANSFER_TASKS(created_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // 丢弃旧架构的 events 表；records 表幂等创建，已存在时不会崩溃
            db.execSQL("DROP TABLE IF EXISTS events")
            onCreate(db)
        }
        if (oldVersion < 4) {
            // v4：新增局域网互传任务历史表（幂等，老库直接补建）
            createTransferTaskTable(db)
        }
        if (oldVersion < 5) {
            // v5：把 R8 混淆产生的 records.type 重写回规范名（可重跑，失败不吞）
            migrateRecordTypes(db)
        }
        if (oldVersion < 6) {
            // v6：页面轨迹功能下线，清掉历史 PAGE 记录（其它类型原样保留）
            purgePageRecords(db)
        }
    }

    /**
     * v6 迁移：删除历史页面轨迹记录（`type = 'PAGE'`）。
     *
     * 页面轨迹功能已整体下线：留着历史行会让实时页继续出现「页面」事件、筛选里出现空分类。
     * - **可重跑**：第二次执行删除 0 行，无副作用；
     * - **只删 PAGE**：其他类型一行不动；
     * - **失败不吞**：记 E 日志后原样抛出，由 SQLiteOpenHelper 回滚事务、下次打开重试。
     */
    private fun purgePageRecords(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_RECORDS, "type")) {
            Log.w("DatabaseHelper", "v6 迁移跳过：$TABLE_RECORDS 没有 type 列，无需删除")
            return
        }
        try {
            val deleted = db.delete(TABLE_RECORDS, "type = ?", arrayOf("PAGE"))
            Log.i("DatabaseHelper", "v6 迁移：删除 PAGE 记录 $deleted 行")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "v6 迁移删除 PAGE 记录失败（整事务回滚）", e)
            throw e
        }
    }

    /**
     * v5 迁移：`records.type` 由 R8 混淆名（如 `qg1`/`ig1`）改写为规范名。
     *
     * - **可重跑**：所有 UPDATE 都以「当前值」为条件，第二次跑匹配 0 行，不产生副作用。
     * - **同事务**：不再嵌套 `beginTransaction` —— `getWritableDatabase` 本身就包着
     *   `beginTransaction … setTransactionSuccessful … endTransaction`，onUpgrade 抛错即整体
     *   回滚、版本号不推进、下次打开重试；嵌套一层反而会把内层失败标记污染到外层。
     * - **失败不吞**：异常记日志后原样抛出，并把「重写行数 / 推不出的组合数」带进日志。
     *
     * 两遍：先按 [RecordType.LEGACY_ALIASES] 精确改名（本机实测的那批），再对剩下的非规范名
     * 按 action 推断（换一个构建版本、短名变了也还能救）。仍推不出的保持原值，只记 W 日志。
     */
    private fun migrateRecordTypes(db: SQLiteDatabase) {
        // v3 及更早的极简库 / 外来库可能压根没有 type 列（例如只建了 records(id) 的测试库）。
        // 这种情况没有可改写的数据，跳过并记一条 W —— 迁移不该把启动拖崩。
        if (!hasColumn(db, TABLE_RECORDS, "type")) {
            Log.w("DatabaseHelper", "v5 迁移跳过：$TABLE_RECORDS 没有 type 列，无需改写")
            return
        }
        var rewritten = 0
        var unresolved = 0
        try {
            RecordType.LEGACY_ALIASES.forEach { (alias, type) ->
                rewritten += db.update(
                    TABLE_RECORDS,
                    ContentValues().apply { put("type", RecordType.Companion.toString(type)) },
                    "type = ?",
                    arrayOf(alias),
                )
            }

            // 非规范名：按 (type, action) 去重后逐组推断，避免逐行 UPDATE
            val placeholders = RecordType.CANONICAL_NAMES.joinToString(",") { "?" }
            val pairs = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT DISTINCT type, action FROM $TABLE_RECORDS WHERE type NOT IN ($placeholders)",
                RecordType.CANONICAL_NAMES.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) pairs.add(cursor.getString(0) to cursor.getString(1))
            }
            for ((raw, action) in pairs) {
                val inferred = RecordType.inferFromAction(action)
                if (inferred == null) {
                    unresolved++
                    continue
                }
                rewritten += db.update(
                    TABLE_RECORDS,
                    ContentValues().apply { put("type", RecordType.Companion.toString(inferred)) },
                    "type = ? AND action = ?",
                    arrayOf(raw, action),
                )
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "v5 迁移 records.type 失败（整事务回滚）：已重写 $rewritten 行", e)
            throw e
        }
        Log.i(
            "DatabaseHelper",
            "v5 迁移 records.type 完成：重写 $rewritten 行；$unresolved 个 type/action 组合无法推断（保留原值）",
        )
    }
}
