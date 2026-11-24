package com.bsfeng.monitor.record

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兜底锚点 [LocationGateState.lastFallbackAtMs] 跨进程丢失的复现与边界。
 *
 * 缺陷形态（真机 11-24 实测）：锚点是纯进程内状态，进程被杀 / 服务重启后归零。服务重启后
 * 第一拍看到 0 就「记起点、不采点」，于是**每一轮重启都白丢一整个兜底窗口**。
 * 22:34 进程被 SwipeUpClean 杀掉 → 心跳 22:46:05 采到最后一个真实点（服务死着，不写锚点）
 * → 23:13 用户解锁把服务拉起来 → 锚点记为 23:13 → 下一次兜底 23:48：62 分钟零定位。
 *
 * 这里只钉两件事：**丢锚点会把窗口整体后移**（i.e. 缺陷形状）与**回填后窗口不翻倍**（修复形状）。
 */
class LocationFallbackAnchorSeedTest {

    private val now = 1_700_000_000_000L

    /** 快照是进程内全局状态：每个用例后复位，避免污染同一 JVM 里的其它用例。 */
    @After
    fun resetSnapshot() {
        LocationGateState.resetForTest()
    }

    // ===== 缺陷形状：锚点丢了，窗口整体后移 =====

    @Test
    fun `锚点丢失时下一次兜底被推迟满一个窗口`() {
        // 进程重启：库里最后一个点在 27 分钟前，锚点归零
        val lastSuccess = now - 27 * 60_000L
        LocationGateState.seedFallbackAnchor(null, now)
        assertEquals("查不到记录就要保持 0（「还没记过起点」的既有语义）", 0L, LocationGateState.lastFallbackAtMs)

        // 服务重启后的第一拍（真机 23:13:01 那一拍）：0 → 只记起点、不采点
        LocationGateState.onFallbackAttempt(now)
        assertTrue(
            "重启后立刻判到期是错的：那一拍只记起点",
            !LocationTickerPolicy.sleepingFallbackDue(
                now,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
        // 充电档设计值 30 分钟：真实点已经 27 分钟前了，只该再等 3 分钟
        assertTrue(
            "缺陷形状：运行 27 分钟仍不到期（真机上要白等到 30 分钟整，即最后一个点之后 57 分钟）",
            !LocationTickerPolicy.sleepingFallbackDue(
                now + 27 * 60_000L,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
        assertTrue(
            "整整一个窗口之后才到期 —— 这 30 分钟就是白丢的那段",
            LocationTickerPolicy.sleepingFallbackDue(
                now + 30 * 60_000L,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `锚点丢失时启动首拍排 5 分钟 而不是按真实剩余时间`() {
        // 真机 23:13:01.402 的日志：「门禁 ticker：下一拍 300s（启动，SLEEPING）」
        assertEquals(
            LocationTickerPolicy.SLEEPING_MAX_TICK_MS,
            LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, 0L),
        )
    }

    // ===== 修复形状：回填后窗口不翻倍 =====

    @Test
    fun `回填后从真实最后一点起算 窗口不翻倍`() {
        // 同一个现场，只是这次能从库里回填
        val lastSuccess = now - 27 * 60_000L
        LocationGateState.seedFallbackAnchor(lastSuccess, now)
        assertEquals("锚点要落在库里最后一条 LOCATION 上", lastSuccess, LocationGateState.lastFallbackAtMs)

        // 服务重启后的第一拍不再「记起点」：锚点已经非 0，直接按剩余时间判
        assertTrue(
            "回填后 27 分钟就该到期（设计值 30 分钟，只该再等 3 分钟）",
            LocationTickerPolicy.sleepingFallbackDue(
                now + 3 * 60_000L,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
        assertTrue(
            "还差一点点就到点：不许提前采（第 29 分钟仍不到期）",
            !LocationTickerPolicy.sleepingFallbackDue(
                now + 2 * 60_000L,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `回填后首拍按真实剩余时间重排 不再等 5 分钟`() {
        val lastSuccess = now - 27 * 60_000L
        LocationGateState.seedFallbackAnchor(lastSuccess, now)
        // 距到期 3 分钟 < 拍子上界 5 分钟：拍子必须缩到 3 分钟，兜底才准点
        assertEquals(
            "回填后拍子按「距到期剩余」排，不是启动时的 5 分钟",
            3 * 60_000L,
            LocationTickerPolicy.nextDelayMs(
                MotionGate.State.SLEEPING,
                now,
                LocationGateState.lastFallbackAtMs,
            ),
        )
    }

    @Test
    fun `真实最后一个点已经超过一个窗口时 回填后立刻到期`() {
        // 进程死得久（两小时）：回填后不该再等一个窗口，那一拍就直接补
        val lastSuccess = now - 120 * 60_000L
        LocationGateState.seedFallbackAnchor(lastSuccess, now)
        assertTrue(
            LocationTickerPolicy.sleepingFallbackDue(
                now,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
        assertEquals(
            "已过期 → 下一拍 30 秒内就补，不拖",
            LocationTickerPolicy.MIN_TICK_MS,
            LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, LocationGateState.lastFallbackAtMs),
        )
    }

    // ===== 回填边界 =====

    @Test
    fun `查不到记录时保持 0 而不是塞一个假锚点`() {
        LocationGateState.seedFallbackAnchor(null, now)
        assertEquals(0L, LocationGateState.lastFallbackAtMs)
        LocationGateState.seedFallbackAnchor(0L, now)
        assertEquals("0 是「没记过起点」，不是「1970 年兜底过」", 0L, LocationGateState.lastFallbackAtMs)
        LocationGateState.seedFallbackAnchor(-1L, now)
        assertEquals(0L, LocationGateState.lastFallbackAtMs)
    }

    @Test
    fun `未来时刻不许回填 否则兜底被锁到时钟追平`() {
        // 时钟回拨 / 用户改设备时间：锚点落在未来时 sleepingFallbackDue 会一直返回 false
        LocationGateState.seedFallbackAnchor(now + 10 * 60_000L, now)
        assertEquals("未来值一律作废", 0L, LocationGateState.lastFallbackAtMs)
        assertEquals(0L, LocationTickerPolicy.anchorSeedMs(now + 1L, now))
        assertEquals(0L, LocationTickerPolicy.anchorSeedMs(null, now))
        assertEquals(0L, LocationTickerPolicy.anchorSeedMs(0L, now))
        assertEquals(now - 1L, LocationTickerPolicy.anchorSeedMs(now - 1L, now))
    }

    @Test
    fun `上一轮兜底失败时 更早的库值让重试提前而不是等满窗口`() {
        // 上一轮兜底没拿到点（库里的最后一点比那次发起更早）：取较早值 = 立刻到期重试。
        // 方向仍是安全的 —— 宁可多发一次几秒的探测，也不要让失败白等一整个窗口
        // （真机 11-15 04:11→06:55 空 163 分钟就是这么来的）。
        val failedAttempt = now - 60_000L
        LocationGateState.onFallbackAttempt(failedAttempt)
        LocationGateState.onFallbackResult(success = false)
        LocationGateState.seedFallbackAnchor(now - 40 * 60_000L, now)
        assertEquals("取较早值", now - 40 * 60_000L, LocationGateState.lastFallbackAtMs)
        assertTrue(
            "早已到期：立刻重试",
            LocationTickerPolicy.sleepingFallbackDue(
                now,
                LocationGateState.lastFallbackAtMs,
                LocationGateState.lastFallbackFailed,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `回填不碰兜底失败态`() {
        // 失败状态只对「刚失败的那一轮」有意义；重启后按成功算（先等满一个窗口）
        LocationGateState.seedFallbackAnchor(now - 60_000L, now)
        assertEquals(
            "回填后窗口是基础间隔，不是 5 分钟重试窗口",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.effectiveFallbackIntervalMs(
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
                LocationGateState.lastFallbackFailed,
            ),
        )
    }

    @Test
    fun `回填晚于启动首拍时 把假起点覆盖成真实最后一点`() {
        // 查库是 IO 异步：若首拍先跑（SLEEPING 首拍 5 分钟，正常不会，但不许靠时序守正确性），
        // 锚点会被写成一个「刚启动」的假起点，此时必须让库里的真实时刻覆盖它。
        val lastSuccess = now - 27 * 60_000L
        LocationGateState.onFallbackAttempt(now) // 首拍拍了一次假起点
        LocationGateState.seedFallbackAnchor(lastSuccess, now)
        assertEquals("取较早值：真实最后一点比假起点早，必须覆盖", lastSuccess, LocationGateState.lastFallbackAtMs)
        assertTrue(
            "覆盖后 3 分钟就到期，而不是从假起点起再等满 30 分钟",
            LocationTickerPolicy.sleepingFallbackDue(
                now + 3 * 60_000L,
                LocationGateState.lastFallbackAtMs,
                lastFallbackFailed = false,
                LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `库值比现有锚点更晚时不回拉`() {
        // 现有锚点已经是一次真实兜底发起（不是假起点）：更晚的库值只说明刚采到点，
        // 拿它当锚点等于把窗口从"上次发起"改成更晚的时刻，方向仍是安全的一侧，但没必要 ——
        // 保持较小的值（更早到期），代价最多是多一次几秒的探测。
        val realAttempt = now - 10 * 60_000L
        LocationGateState.onFallbackAttempt(realAttempt)
        LocationGateState.seedFallbackAnchor(now - 1 * 60_000L, now)
        assertEquals("取较早值：不许被更晚的库值推后", realAttempt, LocationGateState.lastFallbackAtMs)
    }
}
