package com.bsfeng.monitor.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份包里的定位坐标降精度（SEC-09）。
 *
 * 口径：本机库保留 6 位小数（≈0.11m，轨迹绘制需要），备份包降到 3 位（≈110m）。
 * 只动 records 表里 action = COORDINATE 的 detail，其它行原文不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCoordinatePrecisionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val coordinate = """{"lat":"31.230416","lng":"121.473701","provider":"gps","accuracy_m":"8"}"""

    // ---------------------------------------------------------------- 纯逻辑

    @Test
    fun `六位小数降到三位且其余字段原样保留`() {
        val degraded = BackupCoordinatePrecision.degradeDetail(coordinate)!!

        assertTrue("纬度降到 3 位：" + degraded, degraded.contains("\"lat\":\"31.230\""))
        assertTrue("经度降到 3 位：" + degraded, degraded.contains("\"lng\":\"121.474\""))
        assertTrue("provider 不能被改写", degraded.contains("\"provider\":\"gps\""))
        assertTrue("精度字段不能被改写", degraded.contains("\"accuracy_m\":\"8\""))
    }

    @Test
    fun `已经足够粗的坐标不改写`() {
        assertNull(BackupCoordinatePrecision.degradeDetail("""{"lat":"31.230","lng":"121.474"}"""))
    }

    @Test
    fun `没有坐标字段的行原样不动`() {
        assertNull(BackupCoordinatePrecision.degradeDetail("""{"app_name":"某某应用","package_name":"a.b"}"""))
        assertNull(BackupCoordinatePrecision.degradeDetail(null))
        assertNull(BackupCoordinatePrecision.degradeDetail(""))
    }

    @Test
    fun `坏 JSON 或非数字坐标不崩也不改写`() {
        assertNull(BackupCoordinatePrecision.degradeDetail("{不是 json"))
        assertNull(BackupCoordinatePrecision.degradeDetail("""{"lat":"不详","lng":""}"""))
    }

    @Test
    fun `只有一边有坐标时只降那一边`() {
        val degraded = BackupCoordinatePrecision.degradeDetail("""{"lat":"31.230416"}""")!!

        assertTrue(degraded.contains("\"lat\":\"31.230\""))
        assertTrue("没有 lng 就不该凭空造一个", !degraded.contains("\"lng\""))
    }

    // ---------------------------------------------------------------- 库级

    private fun createRecordsDb(): File {
        val file = File(temp.newFolder("db"), "monitor.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, action TEXT, detail TEXT)")
        db.execSQL(
            "INSERT INTO records (id, action, detail) VALUES (?, ?, ?)",
            arrayOf("c1", BackupCoordinatePrecision.COORDINATE_ACTION, coordinate),
        )
        db.execSQL(
            "INSERT INTO records (id, action, detail) VALUES (?, ?, ?)",
            arrayOf("e1", "APP_SWITCH", """{"app_name":"某某应用"}"""),
        )
        db.close()
        return file
    }

    private fun detailOf(dbFile: File, id: String): String? {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val detail = db.rawQuery("SELECT detail FROM records WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        db.close()
        return detail
    }

    @Test
    fun `快照里的定位点被降精度而其它记录不动`() {
        val dbFile = createRecordsDb()

        assertEquals(1, BackupCoordinatePrecision.sanitize(dbFile))

        val degraded = detailOf(dbFile, "c1")!!
        assertTrue("纬度 3 位：" + degraded, degraded.contains("\"lat\":\"31.230\""))
        assertEquals("非坐标记录一个字都不能改", """{"app_name":"某某应用"}""", detailOf(dbFile, "e1"))
    }

    @Test
    fun `重复降精度是幂等的`() {
        val dbFile = createRecordsDb()
        assertEquals(1, BackupCoordinatePrecision.sanitize(dbFile))

        assertEquals("第二次没有可改的行", 0, BackupCoordinatePrecision.sanitize(dbFile))
    }

    @Test
    fun `库文件打不开时返回失败标记而不是抛异常`() {
        assertEquals(-1, BackupCoordinatePrecision.sanitize(File(temp.root, "没有这个库.db")))
    }

    @Test
    fun `降精度结果在重新打开后仍然有效`() {
        val dbFile = createRecordsDb()

        BackupCoordinatePrecision.sanitize(dbFile)

        // 关掉连接再读：写进 -wal 的内容也必须能读到（收集端要么带上 wal，要么 wal 已被 checkpoint 清空）
        assertTrue(detailOf(dbFile, "c1")!!.contains("\"lat\":\"31.230\""))
        val rewrittenAgain = BackupCoordinatePrecision.sanitize(dbFile)
        assertEquals("第二遍不该再有可改的行", 0, rewrittenAgain)
    }
}
