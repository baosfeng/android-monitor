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
        const val DATABASE_VERSION = 7
        private const val TABLE_RECORDS = "records"

        /** 局域网互传任务历史表（v4 新增）。 */
        const val TABLE_TRANSFER_TASKS = "transfer_tasks"

        /**
         * application 实例 → helper（弱键 + 弱引用值）。
         *
         * 不用「全局单例 + 换 application 就 close 重建」：并发调用会互相劫持——测试里上一个用例
         * 残留的协程用旧 Application 调进来，会把当前用例的 helper 关掉，两次读写分裂到两个连接上
         * （实测 transfer 任务历史 count 偶发为 0 / SQLITE_BUSY）。也不做 LRU 淘汰：淘汰会把仍被
         * 业务对象持有的 helper 关掉，下一次再访问变成「同一路径两条连接」。
         *
         * 弱引用意味着：调用方（ServiceContainer / 引擎 / store）持有期间恒为同一个实例；
         * 用例结束后对象可被回收，helper 由 SQLiteClosable 的 finalizer 关连接，测试进程里
         * 不会无限堆积。生产进程 Application 唯一且长期被持引用，行为等价单例。
         */
        private val helpers =
            java.util.WeakHashMap<Context, java.lang.ref.WeakReference<DatabaseHelper>>()

        /**
         * 取当前 application 对应的 helper。
         *
         * 判据用 `applicationContext` 的**身份**：生产进程内 Application 唯一，永远命中同一项；
         * Robolectric 每个用例新建 Application，各自一份干净库，避免上一个用例排队中的异步落库
         * （transfer 的 persistTask 等）写进下一个用例的共享表。
         */
        fun getInstance(context: Context): DatabaseHelper {
            val app = context.applicationContext ?: context
            synchronized(this) {
                helpers[app]?.get()?.let { return it }
                return DatabaseHelper(app).also { helpers[app] = java.lang.ref.WeakReference(it) }
            }
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
                owner       TEXT DEFAULT '',
                uploaded    INTEGER NOT NULL DEFAULT 0
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
        if (oldVersion < 7) {
            // v7：给 records 增加 uploaded 标记，供 EventReporter 批量上报去重/断点续传
            addUploadedColumn(db)
        }
    }

    /**
     * 允许降级。
     *
     * 默认实现会抛 `SQLiteDowngradeFailedException`（库版本比代码新），而本项目侧载自用，
     * 用户完全可能装回旧版 APK。读取一律按**列名**取值（[RecordStore] 的游标逻辑），多出来的列
     * 自然被忽略，所以降级安全，不该让 App 直接起不来。
     *
     * 与 [com.bsfeng.monitor.finance.FinanceDatabaseHelper]、`TotpDatabaseHelper` 同一口径。
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 故意留空：保留数据，不做任何结构变更
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
     * v7 迁移：给 records 表增加 uploaded 标记列（幂等）。
     *
     * 默认 0 = 未上传；上报成功后 EventReporter 通过 RecordStore.markUploaded 置 1。
     * - 可重跑：列已存在时跳过；
     * - 失败不吞：异常记日志后原样抛出，由 SQLiteOpenHelper 回滚事务。
     */
    private fun addUploadedColumn(db: SQLiteDatabase) {
        if (hasColumn(db, TABLE_RECORDS, "uploaded")) {
            Log.i("DatabaseHelper", "v7 迁移跳过：uploaded 列已存在")
            return
        }
        try {
            db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
            Log.i("DatabaseHelper", "v7 迁移：records 增加 uploaded 列")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "v7 迁移增加 uploaded 列失败（整事务回滚）", e)
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
