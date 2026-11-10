package com.bsfeng.monitor.totp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.domain.totp.TotpParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DB V1 → V2 迁移实测（Robolectric 跑真实 SQLite，不 mock）。
 *
 * 防复发要点：老用户升级 App 时数据库里已有账户行，迁移必须**只补列不重建**，
 * 旧数据一行不少，新列落默认值（otp_type='TOTP' / counter=0）。
 * 用 PRAGMA user_version 模拟真实升级路径（1 → 2），而不是直接调 onCreate。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpMigrationTest {

    private lateinit var context: Context

    /** V1 建表 SQL（从基线 commit 抄下来固化：迁移测试必须对着真实老结构跑，不能引用当前常量）。 */
    private val v1Table = """
        CREATE TABLE IF NOT EXISTS totp_accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            issuer TEXT NOT NULL DEFAULT '',
            account_name TEXT NOT NULL DEFAULT '',
            algorithm TEXT NOT NULL DEFAULT 'SHA1',
            digits INTEGER NOT NULL DEFAULT 6,
            period_sec INTEGER NOT NULL DEFAULT 30,
            sort_order INTEGER NOT NULL DEFAULT 0,
            icon_key TEXT NOT NULL DEFAULT '',
            variant TEXT NOT NULL DEFAULT 'STANDARD',
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0
        )
    """

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
    }

    /** V1 旧库升级到 V2：旧账户一行不丢，新列取默认值。 */
    @Test
    fun v1UpgradeKeepsRowsAndDefaultsNewColumns() {
        buildV1Database(
            listOf(
                V1Row("腾讯云", "ops@acme.cn", "SHA256", 8, 60, 0),
                V1Row("GitHub", "alice@acme.com", "SHA1", 6, 30, 1),
            ),
        )

        val helper = TotpDatabaseHelper(context)
        val db = helper.writableDatabase

        assertEquals(TotpDatabaseHelper.DATABASE_VERSION, db.version)
        assertEquals(2, db.version)
        assertTrue("otp_type 列必须补上", "otp_type" in TotpDatabaseHelper.tableColumns(db))
        assertTrue("counter 列必须补上", "counter" in TotpDatabaseHelper.tableColumns(db))

        val dao = TotpAccountDao(helper)
        val rows = dao.listAll()
        assertEquals(2, rows.size)
        assertEquals(listOf("腾讯云", "GitHub"), rows.map { it.issuer })
        assertEquals(listOf("ops@acme.cn", "alice@acme.com"), rows.map { it.accountName })
        assertEquals(listOf("SHA256", "SHA1"), rows.map { it.algorithm })
        assertEquals(listOf(8, 6), rows.map { it.digits })
        assertEquals(listOf(60, 30), rows.map { it.periodSec })
        assertEquals(listOf(0, 1), rows.map { it.sortOrder })
        assertEquals(listOf("TOTP", "TOTP"), rows.map { it.otpType })
        assertEquals(listOf(0L, 0L), rows.map { it.counter })
        helper.close()
    }

    /** 迁移后旧账户可读成 domain 模型：类型默认 TOTP、counter 默认 0。 */
    @Test
    fun migratedRowConvertsToDomainAccount() {
        buildV1Database(listOf(V1Row("Old", "old@acme.com", "SHA1", 6, 30, 0)))

        val helper = TotpDatabaseHelper(context)
        val row = TotpAccountDao(helper).listAll().single()
        val account = row.toAccount(TEST_SECRET)

        assertEquals("Old", account.issuer)
        assertEquals(TotpParams.TYPE_TOTP, account.params.type)
        assertEquals(0L, account.counter)
        helper.close()
    }

    /** 上次迁移被中断的半套库：只缺 counter，只补 counter，不报重复列。 */
    @Test
    fun halfMigratedDatabaseOnlyAddsMissingColumn() {
        buildV1Database(listOf(V1Row("半路", "half@acme.com", "SHA1", 6, 30, 0)))
        val path = context.getDatabasePath(TotpDatabaseHelper.DATABASE_NAME)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE totp_accounts ADD COLUMN otp_type TEXT NOT NULL DEFAULT 'TOTP'")
            db.version = 1
        }

        val helper = TotpDatabaseHelper(context)
        val db = helper.writableDatabase

        assertTrue("counter 应被补上", "counter" in TotpDatabaseHelper.tableColumns(db))
        assertEquals("旧数据不能被重建掉", 1, TotpAccountDao(helper).count())
        assertEquals("半路", TotpAccountDao(helper).listAll().single().issuer)
        helper.close()
    }

    /** 重复执行 onUpgrade 不抛异常、不重复加列、不丢数据。 */
    @Test
    fun repeatedUpgradeIsIdempotent() {
        buildV1Database(listOf(V1Row("keep-me", "keep@acme.com", "SHA1", 6, 30, 0)))

        val helper = TotpDatabaseHelper(context)
        val db = helper.writableDatabase
        helper.onUpgrade(db, 1, TotpDatabaseHelper.DATABASE_VERSION)
        helper.onUpgrade(db, 1, TotpDatabaseHelper.DATABASE_VERSION)
        helper.onUpgrade(db, 0, TotpDatabaseHelper.DATABASE_VERSION)

        assertEquals(1, TotpAccountDao(helper).count())
        assertEquals("keep-me", TotpAccountDao(helper).listAll().single().issuer)
        helper.close()
    }

    /** 全新安装走 onCreate，结构直接是 V2。 */
    @Test
    fun freshInstallCreatesV2Schema() {
        val helper = TotpDatabaseHelper(context)
        val db = helper.writableDatabase

        assertEquals(2, db.version)
        assertTrue("otp_type" in TotpDatabaseHelper.tableColumns(db))
        assertTrue("counter" in TotpDatabaseHelper.tableColumns(db))
        assertEquals("新库应是 0 条", 0, TotpAccountDao(helper).count())
        helper.close()
    }

    /** 升级后的库能写入 HOTP 账户，counter 持久化。 */
    @Test
    fun hotpRowPersistsAfterUpgrade() {
        buildV1Database(listOf(V1Row("keep-me", "keep@acme.com", "SHA1", 6, 30, 0)))

        val helper = TotpDatabaseHelper(context)
        val dao = TotpAccountDao(helper)
        val id = dao.insert(
            testRow(issuer = "OldCorp", accountName = "Legacy").copy(
                otpType = TotpParams.TYPE_HOTP,
                counter = 7L,
                sortOrder = 1,
            ),
        )

        val row = dao.getById(id)!!
        assertEquals(TotpParams.TYPE_HOTP, row.otpType)
        assertEquals(7L, row.counter)
        assertEquals(2, dao.count())
        helper.close()
    }

    private data class V1Row(
        val issuer: String,
        val accountName: String,
        val algorithm: String,
        val digits: Int,
        val periodSec: Int,
        val sortOrder: Int,
    )

    /** 造一个「老用户升级前」的库：手写 V1 DDL + 插旧行 + 把 user_version 钉在 1。 */
    private fun buildV1Database(rows: List<V1Row>) {
        val path = context.getDatabasePath(TotpDatabaseHelper.DATABASE_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v1Table)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_totp_accounts_sort ON totp_accounts(sort_order)")
        rows.forEach { row ->
            db.execSQL(
                "INSERT INTO totp_accounts (issuer, account_name, algorithm, digits, period_sec, " +
                    "sort_order, icon_key, variant, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    row.issuer, row.accountName, row.algorithm, row.digits, row.periodSec,
                    row.sortOrder, "", "STANDARD", 1_700_000_000_000L, 1_700_000_000_000L,
                ),
            )
        }
        // PRAGMA user_version 就是 SQLiteOpenHelper 判断「需要升级」的依据
        db.version = 1
        db.close()
    }
}
