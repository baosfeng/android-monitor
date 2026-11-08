package com.bsfeng.monitor.totp

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TotpClipboard] 单元测试（Robolectric，系统剪贴板可读写）。
 *
 * 覆盖：写入、30 秒自动清空、用户改写后不误清、比对一致才清、取消待清任务。
 * 说明：Robolectric 没有 Android 10+ 的后台读剪贴板限制，因此「后台读不到 → 不清理」
 * 这条路径只能用代码复核，无法在单测里复现。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpClipboardTest {

    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
    }

    @After
    fun tearDown() {
        clipboard.clearPrimaryClip()
    }

    private fun readClip(): String? =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()

    private fun newClipboard(scope: CoroutineScope?) = TotpClipboard(
        context = context,
        scope = scope ?: CoroutineScope(StandardTestDispatcher()),
        clearDelayMillis = 30_000L,
    )

    @Test
    fun `复制写入剪贴板`() {
        val cb = newClipboard(null)

        assertTrue(cb.copy("123456"))
        assertEquals("123456", readClip())
    }

    @Test
    fun `写入的剪贴板条目标记为敏感`() {
        val cb = newClipboard(null)

        assertTrue(cb.copy("123456"))

        // Android 13+ 靠这个 extra 把系统剪贴板预览遮成「敏感内容」，
        // 漏了它 6 位验证码会直接印在预览浮层上（安全审计 S-06）
        val extras = clipboard.primaryClip?.description?.extras
        assertNotNull("剪贴板条目缺少 description.extras", extras)
        assertTrue(
            "必须标记 EXTRA_IS_SENSITIVE",
            extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false),
        )
        // 标记敏感不能改变内容本身
        assertEquals("123456", readClip())
    }

    @Test
    fun `明文标签不写入账号信息`() {
        val cb = newClipboard(null)
        cb.copy("123456")

        val label = clipboard.primaryClip?.description?.label?.toString()
        // 标签会显示在部分 ROM 的剪贴板历史里：只允许「验证码」，不能带发行方 / 账号
        assertEquals(TotpClipboard.CLIP_LABEL, label)
    }

    @Test
    fun `30 秒后自动清空且不到点不清`() = runTest {
        val cb = newClipboard(CoroutineScope(StandardTestDispatcher(testScheduler)))
        cb.copy("123456")

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals("123456", readClip())

        advanceTimeBy(1)
        runCurrent()
        assertTrue(readClip().isNullOrEmpty())
    }

    @Test
    fun `用户后来复制了别的内容则不清空`() = runTest {
        val cb = newClipboard(CoroutineScope(StandardTestDispatcher(testScheduler)))
        cb.copy("111111")
        clipboard.setPrimaryClip(ClipData.newPlainText("用户", "别的文本"))

        advanceTimeBy(30_001)
        runCurrent()

        assertEquals("别的文本", readClip())
    }

    @Test
    fun `立即清理只在内容仍是自己写的时候生效`() {
        val cb = newClipboard(null)

        cb.copy("222222")
        assertTrue(cb.clearIfUnchanged("222222"))
        assertTrue(readClip().isNullOrEmpty())

        cb.copy("333333")
        clipboard.setPrimaryClip(ClipData.newPlainText("用户", "后来复制的"))
        assertFalse(cb.clearIfUnchanged("333333"))
        assertEquals("后来复制的", readClip())
    }

    @Test
    fun `取消待清任务后到期不清理`() = runTest {
        val cb = newClipboard(CoroutineScope(StandardTestDispatcher(testScheduler)))
        cb.copy("444444")
        cb.cancelPendingClear()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("444444", readClip())
    }
}
