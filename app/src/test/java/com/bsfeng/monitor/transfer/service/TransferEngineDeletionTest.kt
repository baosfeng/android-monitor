package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.domain.transfer.TransferStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * 任务删除的引擎接线（Robolectric，真实会话 + 真实任务列表）。
 *
 * 纯规则见 `TransferTaskDeletionTest`；本用例验证：进行中任务被引擎拒绝、已终结任务删除后
 * 列表移除并经 `deletedTasks` 通知 UI、撤销恢复 / 提交后不可再撤销。接收文件的删除动作
 * 由 `TransferTaskDeletionTest` 的回调用例覆盖（Robolectric 的 MediaStore 不落真实文件）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineDeletionTest {

    private lateinit var context: Context
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        TransferEngine.resetForTesting()
        engine = TransferEngine.getInstance(context)
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
        TransferEngine.resetForTesting()
    }

    private fun offerBody(senderDeviceId: String): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "bsfeng 的 Mac")
        put(
            "files",
            JSONArray().put(
                JSONObject().apply {
                    put("fileId", "f1")
                    put("name", "a.bin")
                    put("size", 10L)
                    put("sha256", "sha-$senderDeviceId")
                    put("mime", "application/octet-stream")
                },
            ),
        )
    }

    /** 发起一次未配对 offer（任务进入 AWAITING_CONFIRM），返回该任务。 */
    private fun pendingTask(deviceId: String) = runBlocking {
        val response = engine.offer(offerBody(deviceId), null)
        assertEquals(0, response.code)
        requireNotNull(engine.tasks.value.firstOrNull { it.peer.deviceId == deviceId })
    }

    @Test
    fun `删除撤销窗口为 5 秒`() {
        assertEquals(5_000L, TransferEngine.UNDO_WINDOW_MS)
    }

    @Test
    fun `进行中的任务不可删除且列表保持不变`() {
        val task = pendingTask("mac-active")

        val deleted = engine.deleteTaskNow(task.sessionId, task.file.fileId)

        assertNull("AWAITING_CONFIRM 不应允许删除", deleted)
        assertEquals(1, engine.tasks.value.size)
        assertEquals(task, engine.tasks.value.single())
    }

    @Test
    fun `已终结任务删除后移除记录并通知撤销事件`() = runBlocking {
        val task = pendingTask("mac-terminal")
        engine.denyIncoming(task.sessionId)
        val terminated = engine.tasks.value.single()
        assertEquals(TransferStatus.DENIED, terminated.status)

        // SharedFlow 无 replay：UNDISPATCHED 立即进入 collect，确保订阅先于删除事件（UI 侧由 LaunchedEffect 常驻订阅）
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(3_000) { engine.deletedTasks.first() }
        }
        engine.deleteTask(task.sessionId, task.file.fileId)
        val deleted = received.await()

        assertEquals(task.sessionId, deleted.task.sessionId)
        assertEquals("对方拒绝的接收任务没有落盘文件", false, deleted.fileDeleted)
        assertEquals(0, engine.tasks.value.size)

        val restored = engine.undoDelete(deleted.deletionId)
        assertEquals(terminated, restored?.task)
        assertEquals(1, engine.tasks.value.size)
        assertNull("同一快照不能撤销两次", engine.undoDelete(deleted.deletionId))
    }

    @Test
    fun `撤销窗口结束后记录不再恢复`() {
        val task = pendingTask("mac-commit")
        engine.denyIncoming(task.sessionId)

        val deleted = requireNotNull(engine.deleteTaskNow(task.sessionId, task.file.fileId))
        engine.commitDelete(deleted.deletionId)

        assertNull(engine.undoDelete(deleted.deletionId))
        assertEquals(0, engine.tasks.value.size)
    }
}
