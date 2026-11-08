package com.bsfeng.monitor.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认令牌的两套口径（安全审计 S-01 修订后）。
 *
 * 这条通道是 exported 的（`adb shell am broadcast` 要能直接打），广播不传发送方 uid，
 * 所以「谁发的」判不了 —— 能判的只有「调用方读没读过本次 unlock 回执」。两种消耗方式各有用处：
 *
 * - **TTL 内复用**（读数据 / 驱动界面）：一次取证要连发几十条 ui / db 命令，每条都重 unlock 会让
 *   debug-cli 实际不可用；
 * - **消费即废**（clear / seed）：不可撤销的写库，连点第二下必须失败。
 */
class DebugConfirmTokenTest {

    @After
    fun tearDown() {
        DebugConfirmToken.reset()
    }

    // ───────────────────── TTL 内复用 ─────────────────────

    @Test
    fun `有效期内可反复校验通过`() {
        val token = DebugConfirmToken.issue(nowMs = 1_000L)

        assertTrue(DebugConfirmToken.isValid(token, 1_001L))
        assertTrue("多次命令要能复用同一令牌", DebugConfirmToken.isValid(token, 60_000L))
        assertTrue(DebugConfirmToken.isValid(token, 1_000L + DebugConfirmToken.TTL_MS))
    }

    @Test
    fun `TTL 之后失效`() {
        val token = DebugConfirmToken.issue(nowMs = 1_000L)

        assertFalse(DebugConfirmToken.isValid(token, 1_000L + DebugConfirmToken.TTL_MS + 1))
    }

    // ───────────────────── 消费即废 ─────────────────────

    @Test
    fun `消费型校验只能成功一次`() {
        val token = DebugConfirmToken.issue(nowMs = 1_000L)

        assertTrue(DebugConfirmToken.consume(token, 1_002L))
        assertFalse("用过即废：清表连点第二下必须被拒", DebugConfirmToken.consume(token, 1_003L))
        assertFalse("消费后连复用校验也不该通过", DebugConfirmToken.isValid(token, 1_004L))
    }

    @Test
    fun `过期令牌被消费后也作废`() {
        val token = DebugConfirmToken.issue(nowMs = 0L)

        assertFalse(DebugConfirmToken.consume(token, DebugConfirmToken.TTL_MS + 1))
        assertFalse("第一次失败也要把令牌作废，避免反复试", DebugConfirmToken.isValid(token, 1L))
    }

    // ───────────────────── 重签发 / 异常输入 ─────────────────────

    @Test
    fun `重新签发会作废旧令牌`() {
        val first = DebugConfirmToken.issue(nowMs = 1_000L)
        val second = DebugConfirmToken.issue(nowMs = 1_100L)

        assertNotEquals(first, second)
        assertFalse("旧令牌立刻失效", DebugConfirmToken.isValid(first, 1_101L))
        assertTrue("新令牌可用", DebugConfirmToken.isValid(second, 1_102L))
    }

    @Test
    fun `空值与乱码一律拒绝`() {
        DebugConfirmToken.issue(nowMs = 1_000L)

        assertFalse(DebugConfirmToken.isValid(null, 1_001L))
        assertFalse(DebugConfirmToken.isValid("", 1_001L))
        assertFalse(DebugConfirmToken.isValid("  ", 1_001L))
        assertFalse(DebugConfirmToken.isValid("cf-deadbeef", 1_001L))
        assertFalse(DebugConfirmToken.consume(null, 1_001L))
    }

    @Test
    fun `没有签发过时任何令牌都不可用`() {
        DebugConfirmToken.reset()

        assertFalse(DebugConfirmToken.isValid("cf-anything", 1_000L))
        assertFalse(DebugConfirmToken.consume("cf-anything", 1_000L))
    }

    @Test
    fun `令牌是不可猜的随机串`() {
        val token = DebugConfirmToken.issue(nowMs = 1L)

        // 形如 cf-<24 位十六进制>：固定前缀便于人工识别，后面是随机字节
        assertTrue("格式不对：" + token, Regex("cf-[0-9a-f]{24}").matches(token))
        // 同一时刻连签两次也必须不同：值来自 SecureRandom，不是时间戳或计数器
        assertNotEquals(token, DebugConfirmToken.issue(nowMs = 1L))
    }

    @Test
    fun `TTL 取值在可取证又有界的区间`() {
        // 太短：一次取证跑不完几十条命令；太长：把「刚 unlock」的时效拖成半天
        assertTrue("TTL 至少要盖住一次取证会话", DebugConfirmToken.TTL_MS >= 5 * 60 * 1000L)
        assertTrue("TTL 不该超过半小时", DebugConfirmToken.TTL_MS <= 30 * 60 * 1000L)
        assertEquals(10 * 60 * 1000L, DebugConfirmToken.TTL_MS)
    }
}
