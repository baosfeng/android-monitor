package com.bsfeng.monitor.finance

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试（V1 → V2 → V3）。
 *
 * V2 给 finance_transactions 加了 6 列（商户 / 商品说明 / 付款方式 / 订单号 / 平台分类 / 原始交易时间）；
 * V3 加了 to_account_name（转账的转入账户）。
 * 迁移必须做到：**老数据一行不丢、新列有默认值、迁移后可正常读写** —— 迁移错了就是用户账本损坏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceDatabaseMigrationTest {

    /** V1 的建表语句（不含 V2 的 6 列），用来造一个真实的旧库 */
    private val v1CreateTable = """
        CREATE TABLE finance_transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type INTEGER NOT NULL DEFAULT 0,
            category_name TEXT NOT NULL DEFAULT '',
            amount REAL NOT NULL DEFAULT 0.0,
            account_name TEXT NOT NULL DEFAULT '',
            remark TEXT NOT NULL DEFAULT '',
            tag TEXT NOT NULL DEFAULT '',
            date TEXT NOT NULL DEFAULT '',
            date_time TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            source TEXT NOT NULL DEFAULT 'manual',
            source_id TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()

    /** V2 的建表语句（含 V2 的 6 列，不含 V3 的 to_account_name） */
    private val v2CreateTable = v1CreateTable
        .replace("source_id TEXT NOT NULL DEFAULT ''", "source_id TEXT NOT NULL DEFAULT '',\n" +
            "            merchant TEXT NOT NULL DEFAULT '',\n" +
            "            product_desc TEXT NOT NULL DEFAULT '',\n" +
            "            pay_method TEXT NOT NULL DEFAULT '',\n" +
            "            order_id TEXT NOT NULL DEFAULT '',\n" +
            "            platform_category TEXT NOT NULL DEFAULT '',\n" +
            "            trade_time TEXT NOT NULL DEFAULT ''")

    @Test
    fun `V1 库升级到最新版后保留老数据并补齐新列`() {
        val context = RuntimeEnvironment.getApplication() as Context
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)

        // 1) 造一个 V1 库，写入两行老数据（模拟用户已有的账本）
        val old = context.openOrCreateDatabase(FinanceDatabaseHelper.DATABASE_NAME, Context.MODE_PRIVATE, null)
        old.execSQL(v1CreateTable)
        old.execSQL("INSERT INTO finance_transactions (amount, remark, date, date_time, created_at, updated_at) VALUES (12.5, '老数据一', '2026-10-01', '2026-10-01 10:00', 1, 1)")
        old.execSQL("INSERT INTO finance_transactions (amount, remark, date, date_time, created_at, updated_at) VALUES (33.0, '老数据二', '2026-10-02', '2026-10-02 11:00', 2, 2)")
        old.version = 1
        old.close()

        // 2) 用正式 helper 打开 → 触发 onUpgrade
        val db = FinanceDatabaseHelper.getInstance(context).writableDatabase
        assertEquals(
            "迁移后版本应为当前 DATABASE_VERSION",
            FinanceDatabaseHelper.DATABASE_VERSION,
            db.version,
        )

        // 3) 老数据一行不丢，且新列为空串
        db.rawQuery(
            "SELECT amount, remark, merchant, product_desc, pay_method, order_id, platform_category, " +
                "trade_time, to_account_name FROM finance_transactions ORDER BY id",
            null
        ).use { c ->
            assertEquals("老数据行数不变", 2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(12.5, c.getDouble(0), 0.001)
            assertEquals("老数据一", c.getString(1))
            for (i in 2..8) assertEquals("新列应为空串，不是 null", "", c.getString(i))
            assertTrue(c.moveToNext())
            assertEquals("老数据二", c.getString(1))
        }

        // 4) 迁移后新列可正常写入并读回
        db.execSQL(
            "INSERT INTO finance_transactions (amount, remark, merchant, product_desc, pay_method, order_id, " +
                "platform_category, trade_time, created_at, updated_at) VALUES (23.9, '淘宝闪购', '淘宝闪购', " +
                "'某某湘菜馆', '招商银行信用卡(8888)', 'CFP001', '餐饮美食', '2026-10-16 18:32:21', 3, 3)"
        )
        db.rawQuery("SELECT merchant, pay_method, trade_time FROM finance_transactions WHERE amount = 23.9", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("淘宝闪购", c.getString(0))
            assertEquals("招商银行信用卡(8888)", c.getString(1))
            assertEquals("2026-10-16 18:32:21", c.getString(2))
        }
    }

    @Test
    fun `V2 库升级到 V3 后补上转入账户列且老数据不丢`() {
        val context = RuntimeEnvironment.getApplication() as Context
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)

        // 1) 造一个 V2 库（有 V2 的 6 列、没有 V3 的 to_account_name），写入一行老数据
        val old = context.openOrCreateDatabase(FinanceDatabaseHelper.DATABASE_NAME, Context.MODE_PRIVATE, null)
        old.execSQL(v2CreateTable)
        old.execSQL(
            "INSERT INTO finance_transactions (amount, remark, merchant, date, date_time, created_at, updated_at) " +
                "VALUES (23.9, '淘宝闪购', '淘宝闪购', '2026-10-16', '2026-10-16 18:32:21', 1, 1)"
        )
        old.version = 2
        old.close()

        // 2) 打开 → 触发 V2 → V3
        val db = FinanceDatabaseHelper.getInstance(context).writableDatabase
        assertEquals(FinanceDatabaseHelper.DATABASE_VERSION, db.version)

        db.rawQuery("SELECT amount, merchant, to_account_name FROM finance_transactions", null).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(23.9, c.getDouble(0), 0.001)
            assertEquals("V2 的列必须原样保留", "淘宝闪购", c.getString(1))
            assertEquals("新增列默认空串", "", c.getString(2))
        }

        // 3) 转账可正常写入并读回（V3 这一列的唯一用途）
        db.execSQL(
            "INSERT INTO finance_transactions (type, amount, account_name, to_account_name, date, date_time, " +
                "created_at, updated_at) VALUES (2, 500.0, '支付宝', '现金钱包', '2026-10-16', " +
                "'2026-10-16 18:40:00', 2, 2)"
        )
        db.rawQuery("SELECT account_name, to_account_name FROM finance_transactions WHERE type = 2", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("支付宝", c.getString(0))
            assertEquals("现金钱包", c.getString(1))
        }
    }
}
