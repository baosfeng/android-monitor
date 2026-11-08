package com.bsfeng.monitor.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份的库快照（R6）。
 *
 * 钉住三件事：复制**只在写事务内**进行、行数在同一个冻结窗口内统计（与快照严格对应）、
 * 拿不到写闸/复制失败时**不留半成品也不出包**——宁可这次备份失败，也不产出还原后少数据的包。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupDbSnapshotTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tables = listOf("records")

    /** 造一个只有 records 表的真库文件。 */
    private fun createDb(rows: Int): File {
        val file = File(temp.newFolder("src"), "monitor.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, action TEXT, timestamp INTEGER)")
        repeat(rows) { i ->
            db.execSQL("INSERT INTO records (id, action, timestamp) VALUES (?, ?, ?)", arrayOf<Any>("r$i", "APP", i))
        }
        db.close()
        return file
    }

    private fun open(dbFile: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)

    @Test
    fun `复制只在写事务内进行`() {
        val dbFile = createDb(rows = 3)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")
        var inTransaction: Boolean? = null
        var copyCount = 0

        val snapshotter = BackupDbSnapshotter(
            openDb = { open(dbFile) },
            tables = tables,
            copy = { db, main, _ ->
                inTransaction = db.inTransaction()
                copyCount++
                dbFile.copyTo(main, overwrite = true)
            },
        )

        val snapshot = snapshotter.snapshot(target, wal)

        assertTrue("复制必须发生在写事务内（否则就是旧的「不加锁直接读」）", inTransaction == true)
        assertEquals("只复制一次", 1, copyCount)
        assertTrue("快照文件应存在", snapshot.mainFile.isFile)
        assertNull("源没有 wal 时不该产生 wal", snapshot.walFile)
        assertEquals(3L, snapshot.counts.single().rows)
    }

    @Test
    fun `行数在冻结窗口内统计且与快照文件一致`() {
        val dbFile = createDb(rows = 5)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")

        val snapshot = BackupDbSnapshotter({ open(dbFile) }, tables).snapshot(target, wal)

        // 快照本身能独立打开，行数与清单一致 —— 还原后的自检就是照这个数字比对的
        val snapshotDb = open(snapshot.mainFile)
        val rows = snapshotDb.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        snapshotDb.close()
        assertEquals(snapshot.counts.single().rows, rows)
        assertEquals(5L, rows)
    }

    @Test
    fun `冻结期间不写入任何数据`() {
        val dbFile = createDb(rows = 2)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")
        val before = dbFile.length()

        BackupDbSnapshotter({ open(dbFile) }, tables).snapshot(target, wal)

        val db = open(dbFile)
        val rows = db.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        db.close()
        assertEquals("冻结只是读，不能改动原库", 2L, rows)
        assertEquals(before, dbFile.length())
    }

    @Test
    fun `拿不到写闸时备份失败且不留半成品`() {
        val dbFile = createDb(rows = 1)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")
        target.writeText("半成品")
        wal.writeText("半成品")

        val snapshotter = BackupDbSnapshotter(
            openDb = { open(dbFile) },
            tables = tables,
            maxFreezeAttempts = 0,
        )

        try {
            snapshotter.snapshot(target, wal)
            fail("拿不到写闸必须抛异常，而不是退回旧的「不加锁直接读」")
        } catch (e: IllegalStateException) {
            assertTrue("原因要能看懂：" + e.message, e.message!!.contains("写闸"))
        }
        assertTrue("半成品必须删掉", !target.exists())
        assertTrue("半成品必须删掉", !wal.exists())
    }

    @Test
    fun `复制中途失败时向上抛并清理半成品`() {
        val dbFile = createDb(rows = 1)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")
        val boom = java.io.IOException("磁盘满了")

        val snapshotter = BackupDbSnapshotter(
            openDb = { open(dbFile) },
            tables = tables,
            copy = { _, main, walTarget ->
                main.writeText("写了一半")
                walTarget.writeText("写了一半")
                throw boom
            },
        )

        val thrown = runCatching { snapshotter.snapshot(target, wal) }.exceptionOrNull()

        assertEquals(boom, thrown)
        assertTrue("半成品必须删掉", !target.exists())
        assertTrue("半成品必须删掉", !wal.exists())
    }

    @Test
    fun `wal 非空时随快照一起带上`() {
        val dbFile = createDb(rows = 1)
        val target = File(temp.newFolder("snap"), "monitor.db")
        val wal = File(target.parentFile, "monitor.db-wal")

        val snapshotter = BackupDbSnapshotter(
            openDb = { open(dbFile) },
            tables = tables,
            copy = { _, main, walTarget ->
                dbFile.copyTo(main, overwrite = true)
                walTarget.writeBytes(ByteArray(16) { 7 })
            },
        )

        val snapshot = snapshotter.snapshot(target, wal)

        assertNotNull("wal 有内容时必须一起进包，否则还原后丢最近若干条", snapshot.walFile)
        assertEquals(16L, snapshot.walFile!!.length())
    }
}
