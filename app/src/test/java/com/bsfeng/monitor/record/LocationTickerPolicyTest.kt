package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 ticker 拍子策略：SLEEPING 从「固定 30 秒空转」改为「最长 5 分钟 + 兜底准点」，
 * MOVING / PROBING 的 30 秒一拍一秒都不许变（探测与静默补采靠它）。
 *
 * 旧行为：一整天 2880 次唤醒，其中 2870+ 次只做「评估一次、比一次时间」。
 */
class LocationTickerPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `刚进 SLEEPING 拍子 5 分钟`() {
        assertEquals(300_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, 0L))
    }

    @Test
    fun `距兜底还有 40 秒时拍子就是 40 秒 让兜底准点`() {
        val lastFallback = now - (LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS - 40_000L)
        assertEquals(40_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, lastFallback))
    }

    @Test
    fun `兜底已过期 下一拍 30 秒内就补`() {
        val overdue = now - LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS - 60_000L
        assertEquals(30_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, overdue))
    }

    @Test
    fun `距兜底远超 5 分钟时拍子封顶 5 分钟`() {
        val lastFallback = now - 60_000L
        assertEquals(300_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, lastFallback))
    }

    @Test
    fun `剩余不足 30 秒时拍子取下限 30 秒`() {
        val lastFallback = now - (LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS - 10_000L)
        assertEquals(30_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, lastFallback))
    }

    @Test
    fun `时钟回拨 30 秒兜底`() {
        val future = now + 10 * 60_000L
        assertEquals(30_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, now, future))
    }

    @Test
    fun `PROBING 与 MOVING 都是 30 秒`() {
        assertEquals(30_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.PROBING, now, 0L))
        assertEquals(30_000L, LocationTickerPolicy.nextDelayMs(MotionGate.State.MOVING, now, 0L))
        assertEquals(LocationPace.MOVING_INTERVAL_MS, LocationTickerPolicy.ACTIVE_TICK_MS)
    }

    @Test
    fun `SLEEPING 24 小时唤醒次数从 2880 降到 288`() {
        val day = 24 * 60 * 60_000L
        // 旧策略：固定 30 秒一拍
        var baseline = 0
        var t = LocationTickerPolicy.ACTIVE_TICK_MS
        while (t <= day) {
            baseline++
            t += LocationTickerPolicy.ACTIVE_TICK_MS
        }
        assertEquals(2880, baseline)

        // 新策略：按服务真实时序模拟（拍子 → 到点评估 → 到期就补并重置兜底时刻）
        var ticks = 0
        var time = 0L
        var lastFallbackAtMs = 0L
        while (true) {
            time += LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, time, lastFallbackAtMs)
            if (time > day) break
            ticks++
            if (lastFallbackAtMs == 0L || time - lastFallbackAtMs >= LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS) {
                lastFallbackAtMs = time
            }
        }
        assertEquals("SLEEPING 24 小时只该醒 288 次", 288, ticks)
    }

    // ===== 网络静止证据：兜底从 30 分钟放宽到 2 小时 =====

    @Test
    fun `兜底间隔随静止证据在 30 分钟与 2 小时之间切换`() {
        val origin = now
        assertEquals(
            "没有静止证据：仍是 30 分钟",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(0L, origin),
        )
        assertEquals(
            "证据不满一轮：先按旧节奏验",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(origin, origin + 29 * 60_000L),
        )
        assertEquals(
            LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(origin, origin + 30 * 60_000L),
        )
        assertEquals(
            "时钟倒退不放宽",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(origin, origin - 60_000L),
        )
    }

    @Test
    fun `放大后的间隔只改兜底 不改 ticker 上限`() {
        val lastFallback = now - 10 * 60_000L
        assertEquals(
            "SLEEPING 拍子最长仍是 5 分钟",
            LocationTickerPolicy.SLEEPING_MAX_TICK_MS,
            LocationTickerPolicy.nextDelayMs(
                MotionGate.State.SLEEPING,
                now,
                lastFallback,
                LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            ),
        )
        assertEquals(
            "距到期 40 秒时按 40 秒排，60 分钟兜底也准点",
            40_000L,
            LocationTickerPolicy.nextDelayMs(
                MotionGate.State.SLEEPING,
                now,
                now - (LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS - 40_000L),
                LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `充电时兜底回到 30 分钟`() {
        val origin = now
        assertEquals(
            "断电：静止证据满一轮后放宽到 60 分钟",
            LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(origin, origin + 40 * 60_000L),
        )
        assertEquals(
            "插着电不必省电：轨迹密一点更有价值",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationTickerPolicy.sleepingFallbackIntervalMs(origin, origin + 40 * 60_000L, charging = true),
        )
    }

    @Test
    fun `网络环境未变时 SLEEPING 一天兜底次数减半`() {
        val day = 24 * 60 * 60_000L
        val origin = now
        var time = origin
        var lastFallbackAtMs = 0L
        val fallbackAt = mutableListOf<Long>()
        while (true) {
            val interval = LocationTickerPolicy.sleepingFallbackIntervalMs(origin, time)
            time += LocationTickerPolicy.nextDelayMs(
                MotionGate.State.SLEEPING,
                time,
                lastFallbackAtMs,
                interval,
            )
            if (time > origin + day) break
            if (lastFallbackAtMs == 0L) {
                lastFallbackAtMs = time
            } else if (time - lastFallbackAtMs >=
                LocationTickerPolicy.sleepingFallbackIntervalMs(origin, time)
            ) {
                lastFallbackAtMs = time
                fallbackAt += time
            }
        }
        // 从 0 点起算：30 分钟兜底在证据满一轮后才放宽到 60 分钟，之后每小时一个。
        // 旧口径（30 分钟一次）一天 47 次；60 分钟口径落在 24 次上下（首轮 30 分钟 + 5 分钟拍子余量）
        assertTrue(
            "静止一天的兜底次数应落在 20~26 次（旧口径 47 次）：${fallbackAt.size}",
            fallbackAt.size in 20..26,
        )
        fallbackAt.zipWithNext().forEach { (previous, next) ->
            assertTrue(
                "兜底间隔不该超过 2 小时 + 一拍",
                next - previous <= LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS +
                    LocationTickerPolicy.SLEEPING_MAX_TICK_MS,
            )
        }
    }

    @Test
    fun `兜底不会比旧策略晚超过 30 秒`() {
        // 逐拍模拟 3 小时，兜底间隔的实际落点必须落在 [30min, 30min+30s]
        var time = 0L
        var lastFallbackAtMs = 0L
        val fallbackAt = mutableListOf<Long>()
        val end = 3 * 60 * 60_000L
        while (true) {
            time += LocationTickerPolicy.nextDelayMs(MotionGate.State.SLEEPING, time, lastFallbackAtMs)
            if (time > end) break
            if (lastFallbackAtMs > 0L) {
                val dueGap = time - lastFallbackAtMs
                if (dueGap >= LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS) {
                    fallbackAt += time
                    lastFallbackAtMs = time
                }
            } else {
                lastFallbackAtMs = time
            }
        }
        assertEquals("3 小时应有 5 次兜底", 5, fallbackAt.size)
        for (i in 1 until fallbackAt.size) {
            val gap = fallbackAt[i] - fallbackAt[i - 1]
            assertEquals("兜底间隔必须正好 30 分钟", LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS, gap)
        }
    }
}
