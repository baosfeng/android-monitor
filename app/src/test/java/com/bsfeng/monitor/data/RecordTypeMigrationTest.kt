package com.bsfeng.monitor.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.RecordStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v4 → v5 迁移：把 R8 混淆名（qg1 / ig1 / kg1 …）改写回规范名。
 *
 * 复现的 bug：release 包把 `RecordType` 的 data object 类名写进了 `records.type`，
 * 读路径不认识 → 广告页 `type='SCREEN' AND action='AD_SKIPPED'` 漏掉最新记录。
 * 这里用真实 SQLite（Robolectric）跑迁移，断言「只剩规范名 + SCREEN 查询能命中 + 可重跑」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordTypeMigrationTest {

    private lateinit var context: Context

    /** (type, action, timestamp) —— 前 7 条是本机实测的混淆名，后 3 条是边界。 */
    private val seedRows = listOf(
        Triple("qg1", "UNLOCKED", 1_000L),
        Triple("bg1", "OFF", 2_000L),
        Triple("qg1", "AD_SKIPPED", 3_000L),
        Triple("ig1", "USAGE", 4_000L),
        Triple("kg1", "CHANGED", 5_000L),
        Triple("lg1", "STARTED", 6_000L),
        Triple("rg1", "CONNECTED", 7_000L),
        Triple("zz9", "USAGE", 8_000L),
        Triple("zz9", "WHAT", 9_000L),
        Triple("SCREEN", "AD_SKIPPED", 10_000L),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    /** 造一个 v4 老库（records 表结构同 v4，user_version = 4）。 */
    private fun createLegacyDatabase() {
        val file = context.getDatabasePath(DATABASE_NAME)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS records (
                id          TEXT PRIMARY KEY,
                type        TEXT NOT NULL,
                action      TEXT NOT NULL,
                timestamp   INTEGER NOT NULL,
                duration    INTEGER,
                label       TEXT NOT NULL,
                detail      TEXT,
                owner       TEXT DEFAULT ''
            )
            """.trimIndent(),
        )
        seedRows.forEach { (type, action, ts) ->
            db.execSQL(
                "INSERT INTO records (id, type, action, timestamp, label, owner) VALUES (?, ?, ?, ?, ?, '')",
                arrayOf<Any>("id-$ts", type, action, ts, "label-$ts"),
            )
        }
        db.version = 4
        db.close()
    }

    private fun distinctTypes(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT DISTINCT type FROM records ORDER BY type", null).use { cursor ->
            buildList<String> { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun `升级到 v5 后 type 只剩规范名`() {
        createLegacyDatabase()
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase
        assertEquals(DatabaseHelper.DATABASE_VERSION, db.version)
        assertEquals(5, db.version)

        // zz9/WHAT 推不出来 → 保留原值；其余混淆名全部改名
        assertEquals(listOf("APP_USAGE", "BATTERY", "CHARGING", "SCREEN", "WIFI", "zz9"), distinctTypes(db))
        helper.close()
    }

    @Test
    fun `迁移后 SCREEN 查询能命中历史 AD_SKIPPED（广告页数据恢复）`() {
        createLegacyDatabase()
        val helper = DatabaseHelper(context)
        val store = RecordStore(helper)

        val skips = store.queryByTypeAndAction(RecordType.SCREEN, "AD_SKIPPED", 0L, Long.MAX_VALUE)
        assertEquals("两条 AD_SKIPPED 都应可查：混名一条 + 规范名一条", 2, skips.size)
        assertEquals(listOf(3_000L, 10_000L), skips.map { it.timestamp })
        assertEquals(RecordType.SCREEN, skips.first().type)
        helper.close()
    }

    @Test
    fun `迁移可重跑：把版本退回 4 再升一次，数据与计数不变`() {
        createLegacyDatabase()
        DatabaseHelper(context).use { it.writableDatabase }

        // 模拟「迁移已跑过、再跑一次」：版本退回 4（数据已是规范名）
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.version = 4
        raw.close()

        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase
        assertEquals(5, db.version)
        val total = db.rawQuery("SELECT COUNT(*) FROM records", null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("重跑不能丢数据", seedRows.size, total)
        assertEquals(listOf("APP_USAGE", "BATTERY", "CHARGING", "SCREEN", "WIFI", "zz9"), distinctTypes(db))
        assertEquals(
            2,
            db.rawQuery("SELECT COUNT(*) FROM records WHERE type='SCREEN' AND action='AD_SKIPPED'", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 },
        )
        helper.close()
    }

    @Test
    fun `推不出的 type 读路径不崩：按 action 也认不出才兜底 SCREEN`() {
        createLegacyDatabase()
        val helper = DatabaseHelper(context)
        val store = RecordStore(helper)

        val all = store.queryByDate(0L, Long.MAX_VALUE)
        assertEquals(seedRows.size, all.size)
        // zz9/USAGE 靠 action 救回 APP_USAGE
        val rescued = all.first { it.timestamp == 8_000L }
        assertEquals(RecordType.APP_USAGE, rescued.type)
        // zz9/WHAT 彻底认不出：兜底成 SCREEN（并记一次 W 日志），而不是让整页崩掉
        val fallback = all.first { it.timestamp == 9_000L }
        assertEquals(RecordType.SCREEN, fallback.type)
        assertNotNull(fallback.id)
        assertTrue(all.none { it.action.isBlank() })
        helper.close()
    }

    private companion object {
        const val DATABASE_NAME = "monitor.db"
    }
}
