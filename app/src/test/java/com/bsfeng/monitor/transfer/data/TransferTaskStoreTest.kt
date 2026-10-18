package com.bsfeng.monitor.transfer.data

import android.content.Context
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 任务历史表 `transfer_tasks` 的读写、删除、裁剪与迁移测试（Robolectric + 真实 SQLite）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferTaskStoreTest {

    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var store: TransferTaskStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("monitor.db")
        helper = DatabaseHelper.getInstance(context)
        // 单例可能被其它用例打开过：确保表是干净的
        helper.writableDatabase.execSQL("DELETE FROM ${TransferTaskStore.TABLE}")
        store = TransferTaskStore(helper)
    }

    @After
    fun tearDown() {
        runCatching { helper.writableDatabase.execSQL("DELETE FROM ${TransferTaskStore.TABLE}") }
    }

    private fun task(
        sessionId: String = "s1",
        fileId: String = "f1",
        status: TransferStatus = TransferStatus.COMPLETED,
        received: Long = 100L,
        createdAt: Long = 1_000L,
        direction: TransferDirection = TransferDirection.RECV,
    ): TransferTask = TransferTask(
        sessionId = sessionId,
        peer = PeerDevice(
            deviceId = "mac-1",
            alias = "MacBook",
            deviceType = DeviceTypes.MAC,
            hostAddress = "192.168.1.9",
            port = 53319,
        ),
        direction = direction,
        file = TransferFile(fileId, "照片.jpg", 200L, "abc", "image/jpeg"),
        status = status,
        receivedBytes = received,
        updatedAtMs = 2_000L,
        createdAtMs = createdAt,
    )

    @Test
    fun `写入后可完整读回`() {
        store.upsert(TransferTaskStore.Entry(task(), "content://downloads/1"))

        val entries = store.loadRecent()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("s1", entry.task.sessionId)
        assertEquals(TransferDirection.RECV, entry.task.direction)
        assertEquals("mac-1", entry.task.peer.deviceId)
        assertEquals("MacBook", entry.task.peer.alias)
        assertEquals("照片.jpg", entry.task.file.name)
        assertEquals(200L, entry.task.file.size)
        assertEquals(TransferStatus.COMPLETED, entry.task.status)
        assertEquals(100L, entry.task.receivedBytes)
        assertEquals("content://downloads/1", entry.fileUri)
        assertEquals(1_000L, entry.task.createdAtMs)
    }

    @Test
    fun `同主键覆盖而不是新增一行`() {
        store.upsert(TransferTaskStore.Entry(task(status = TransferStatus.FAILED), null))
        store.upsert(
            TransferTaskStore.Entry(
                task(status = TransferStatus.COMPLETED, received = 200L),
                "content://downloads/2",
            ),
        )
        val entries = store.loadRecent()
        assertEquals(1, entries.size)
        assertEquals(TransferStatus.COMPLETED, entries.first().task.status)
        assertEquals("content://downloads/2", entries.first().fileUri)
    }

    @Test
    fun `按创建时间倒序返回`() {
        store.upsert(TransferTaskStore.Entry(task(fileId = "f1", createdAt = 1_000L), null))
        store.upsert(TransferTaskStore.Entry(task(fileId = "f2", createdAt = 3_000L), null))
        store.upsert(TransferTaskStore.Entry(task(fileId = "f3", createdAt = 2_000L), null))
        assertEquals(
            listOf("f2", "f3", "f1"),
            store.loadRecent().map { it.task.file.fileId },
        )
    }

    @Test
    fun `删除单条与清空接收方向`() {
        store.upsert(TransferTaskStore.Entry(task(fileId = "f1"), null))
        store.upsert(TransferTaskStore.Entry(task(fileId = "f2"), null))
        store.upsert(
            TransferTaskStore.Entry(
                task(fileId = "f3", direction = TransferDirection.SEND),
                null,
            ),
        )
        store.delete("s1", "f1")
        assertEquals(listOf("f3", "f2"), store.loadRecent().map { it.task.file.fileId })

        store.deleteReceived()
        assertEquals(listOf("f3"), store.loadRecent().map { it.task.file.fileId })
    }

    @Test
    fun `只保留最近 N 条`() {
        repeat(120) { index ->
            store.upsert(
                TransferTaskStore.Entry(
                    task(fileId = "f$index", createdAt = index.toLong() + 1L),
                    null,
                ),
            )
        }
        assertEquals(TransferTaskStore.MAX_PERSISTED_TASKS, store.count())
        val loaded = store.loadRecent()
        // 最新的 119..20 保留，最老的被裁掉
        assertTrue(loaded.none { it.task.file.fileId == "f0" })
        assertEquals("f119", loaded.first().task.file.fileId)
    }

    @Test
    fun `clear 清空历史`() {
        store.upsert(TransferTaskStore.Entry(task(), null))
        store.clear()
        assertEquals(0, store.count())
        assertTrue(store.loadRecent().isEmpty())
    }

    @Test
    fun `坏数据回落默认值而不是崩溃`() {
        helper.writableDatabase.execSQL(
            "INSERT INTO ${TransferTaskStore.TABLE} " +
                "(session_id, file_id, direction, peer_device_id, peer_alias, file_name, file_size, " +
                "sha256, mime, status, received_bytes, uri, created_at, updated_at) " +
                "VALUES ('s9','f9','BOGUS','','','x.bin',1,'','','NOPE',0,NULL,1,1)",
        )
        val entry = store.loadRecent().first()
        assertEquals(TransferDirection.RECV, entry.task.direction)
        assertEquals(TransferStatus.FAILED, entry.task.status)
        assertNull(entry.fileUri)
    }

    @Test
    fun `数据库版本为 4 且迁移会补建任务表`() {
        val path = context.getDatabasePath("monitor.db")
        helper.close()
        context.deleteDatabase("monitor.db")
        path.parentFile?.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        old.execSQL("CREATE TABLE IF NOT EXISTS records (id TEXT PRIMARY KEY)")
        old.version = 3
        old.close()

        val migrated = DatabaseHelper(context)
        val db = migrated.writableDatabase
        assertEquals(4, db.version)
        migrated.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(TransferTaskStore.TABLE),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migrated.close()
    }
}
