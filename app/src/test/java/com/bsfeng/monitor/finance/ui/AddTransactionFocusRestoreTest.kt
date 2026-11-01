package com.bsfeng.monitor.finance.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * N4 / P2① 回归（确认框关闭回焦、重试成功回焦都是**死代码**）。
 *
 * 缺陷模式（连续踩了两轮，batch1 P1-A 同型）：一次性回位写成「先复位自己的 key，再 withFrameNanos」。
 * 复位是快照写 → 下一次重组时 `LaunchedEffect(key)` 换实例 → 旧 job 被 forget（取消）；
 * 而 `withFrameNanos` 的续体恢复走 `AndroidUiDispatcher.dispatch`（`Handler.post`），
 * 排在本帧回调之后，同一帧 Recomposer 又先 `sendFrame` 再重组 → 取消先落地 →
 * 续体恢复时 job 已取消 → `requestFocus()` 永不执行（不崩，但焦点落空）。
 *
 * 这里用 [restoreFocusAfterFrames]（生产实现，composable 直接调用它）跑真实协程：
 * [FrameGate] 顶替 `withFrameNanos`，「forget」用 `Job.cancel()` 顶替，
 * 断言**执行顺序**与**请求次数** —— 判据不是源码长相，而是 requestFocus 到底跑没跑。
 */
class AddTransactionFocusRestoreTest {

    /** 与 `withFrameNanos` 同构的可控闸门：等一帧 = 排队一个 deferred，放行一帧 = 完成它。 */
    private class FrameGate {
        private val pending = ArrayDeque<CompletableDeferred<Unit>>()

        suspend fun awaitFrame() {
            val frame = CompletableDeferred<Unit>()
            pending.addLast(frame)
            frame.await()
        }

        fun sendFrame() {
            pending.removeFirstOrNull()?.complete(Unit)
        }
    }

    /**
     * N4（锚点 s05-del-dialog / s05-del-cancel · 移除后）：确认框关闭后必须真的请求一次
     * 「删除这笔交易」按钮的焦点，且请求发生在 key 复位（→ 协程被 forget）之前。
     */
    @Test
    fun `s05-del-dialog 关闭后回位：requestFocus 先于 key 复位执行，取消吞不掉它`() = runBlocking {
        val gate = FrameGate()
        val steps = mutableListOf<String>()
        var key = true
        var requests = 0
        var job: Job? = null
        job = launch {
            restoreFocusAfterFrames(
                frames = 1,
                awaitFrame = {
                    steps += "frame"
                    gate.awaitFrame()
                },
                requestFocus = {
                    requests++
                    steps += "request"
                    true
                },
                // 复位 = 快照写 → 下一次重组 forget 本协程。模拟最坏情况：复位当场就 cancel。
                reset = {
                    steps += "reset"
                    key = false
                    job?.cancel()
                },
            )
        }
        yield()
        assertEquals("等帧之前一次请求都不能发", listOf("frame"), steps)
        assertEquals(0, requests)
        gate.sendFrame()
        job.join()
        assertEquals("顺序必须是 等帧 → 请求 → 复位", listOf("frame", "request", "reset"), steps)
        assertEquals("requestFocus 必须真的执行（两轮返工都死在取消先落地）", 1, requests)
        assertFalse("一次性 key 必须复位，否则同一个信号下次不再触发 effect", key)
    }

    /** 缺陷模式复现（旧写法）：复位挪到挂起点之前 → 取消先落地 → requestFocus 是死代码。 */
    @Test
    fun `旧写法先复位再等帧：requestFocus 永不执行（本用例即该项的失败判据）`() = runBlocking {
        val gate = FrameGate()
        var key = true
        var requests = 0
        val job = launch {
            if (!key) return@launch
            key = false          // ← 复位在挂起点之前
            gate.awaitFrame()    // ← 续体被派发到本帧回调之后
            requests++           // ← 永不执行
        }
        yield()
        job.cancel()             // 重组 forget → 取消先落地
        gate.sendFrame()
        job.join()
        assertEquals("复位在挂起点之前 = requestFocus 永远到不了", 0, requests)
    }

    /**
     * P2①（锚点 s05-retry · 移除后）：重试成功后焦点回分类行 —— 表单随错态卡卸载后重挂，
     * 前几帧请求打空是常态，必须逐帧重试到最后一次成功，且复位不早于请求。
     */
    @Test
    fun `s05-retry 成功后回位：逐帧重试到成功，requestFocus 真的执行`() = runBlocking {
        val gate = FrameGate()
        val steps = mutableListOf<String>()
        var key = true
        var attempts = 0
        val job = launch {
            restoreFocusAfterFrames(
                frames = 3,
                awaitFrame = {
                    steps += "frame"
                    gate.awaitFrame()
                },
                requestFocus = {
                    attempts++
                    steps += "request"
                    attempts == 3 // 落点第三帧才进焦点树
                },
                reset = {
                    steps += "reset"
                    key = false
                },
            )
        }
        yield()
        repeat(3) {
            gate.sendFrame()
            yield()
        }
        job.join()
        assertEquals("每帧都要真的发请求（不是死代码）", 3, attempts)
        assertEquals(
            "顺序：每帧一次请求，全部请求之后才复位",
            listOf("frame", "request", "frame", "request", "frame", "request", "reset"),
            steps,
        )
        assertFalse(key)
    }

    /** 一次都没请求成功也必须复位：否则同一个信号再次置 true 时值没变化，effect 不重启 → 回位永久失效。 */
    @Test
    fun `全部帧都失败也会复位 key：同一信号下次才能再次触发回位`() = runBlocking {
        val gate = FrameGate()
        var key = true
        var attempts = 0
        val job = launch {
            restoreFocusAfterFrames(
                frames = 2,
                awaitFrame = { gate.awaitFrame() },
                requestFocus = {
                    attempts++
                    false
                },
                reset = { key = false },
            )
        }
        yield()
        repeat(2) {
            gate.sendFrame()
            yield()
        }
        job.join()
        assertEquals(2, attempts)
        assertFalse("即使一次都没成功，key 也必须复位", key)
    }
}
