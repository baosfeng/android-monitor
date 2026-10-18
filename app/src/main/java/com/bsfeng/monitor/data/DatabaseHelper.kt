package com.bsfeng.monitor.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
        private const val DATABASE_VERSION = 4
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
    }
}
