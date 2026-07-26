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
        private const val DATABASE_VERSION = 3
        private const val TABLE_RECORDS = "records"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_RECORDS (
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
        db.execSQL("CREATE INDEX idx_records_ts ON $TABLE_RECORDS(timestamp)")
        db.execSQL("CREATE INDEX idx_records_type ON $TABLE_RECORDS(type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS events")
            onCreate(db)
        }
    }
}
