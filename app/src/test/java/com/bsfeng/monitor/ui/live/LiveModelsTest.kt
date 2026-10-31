package com.bsfeng.monitor.ui.live

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 概览页「最近 24 小时活跃」的口径：**滚动窗口**（此刻往回 24 小时），不是自然日。
 *
 * 反向验证（改坏算法时应看到的红）：
 * - 把 [rollingHourIntensityOf] 换回按自然日小时分桶 → 「跨午夜」那条会把昨天的点丢光；
 * - 把刻度写死成 0..23 时 → 「刻度是真实时刻」那条立刻红；
 * - 把桶下标算成 now 减反方向 → 「新数据在右侧」那条红。
 */
class LiveModelsTest {

    /** 固定时刻，避免用 System.currentTimeMillis 让断言随运行时间漂移。 */
    private val now = 1_800_000_000_000L

    private fun recordAt(ts: Long, id: String = "r" + ts) = Record(
        id = id,
        type = RecordType.SCREEN,
        action = "PAGE",
        timestamp = ts,
        label = "测试",
    )

    @Test
    fun `滚动窗口把事件按小时分桶 越新越靠右`() {
        val events = listOf(
            recordAt(now - 1 * HOUR_MS),      // 最后一小时 → 下标 23
            recordAt(now - 3 * HOUR_MS),      // 3 小时前 → 下标 21
            recordAt(now - DAY_MS + 1),       // 窗口刚起点 → 下标 0
        )
        val buckets = rollingHourIntensityOf(events, now)

        assertEquals(HOURS_PER_DAY, buckets.size)
        assertEquals(1f, buckets[23])
        assertEquals(1f, buckets[21])
        assertEquals(1f, buckets[0])
        assertEquals("其余桶应为 0", 21, buckets.count { it == 0f })
    }

    @Test
    fun `跨午夜时昨天的点也算进最近24小时`() {
        // 凌晨 00:12 打开：昨天 23:30 的点距现在 40 分钟，必须落在窗口内
        val yesterdayLate = now - 42 * 60_000L
        val buckets = rollingHourIntensityOf(listOf(recordAt(yesterdayLate)), now)

        assertEquals(1f, buckets.sum())
        assertEquals("应该落在最后一格里", 1f, buckets[23])
    }

    @Test
    fun `窗口外的事件不计入`() {
        val events = listOf(
            recordAt(now - DAY_MS - 1),  // 早于窗口 1ms
            recordAt(now + 1),           // 未来
        )
        val buckets = rollingHourIntensityOf(events, now)
        assertEquals(0f, buckets.sum())
    }

    @Test
    fun `桶起点时刻与刻度都是真实时间不是自然日小时`() {
        val first = rollingHourStartAt(now, 0)
        val last = rollingHourStartAt(now, HOURS_PER_DAY - 1)

        assertEquals("第 0 桶 = 窗口起点", now - DAY_MS, first)
        assertEquals("第 23 桶起点 = 窗口起点 + 23 小时", now - HOUR_MS, last)
        assertEquals("越界下标要夹住", now - HOUR_MS, rollingHourStartAt(now, 999))

        val axis = rollingHourAxisLabels(now)
        assertEquals("起点 / 中点 / 终点三档", 3, axis.size)
        assertEquals(formatClock(now - DAY_MS), axis[0])
        assertEquals(formatClock(now - DAY_MS / 2), axis[1])
        assertEquals(formatClock(now), axis[2])
    }

    @Test
    fun `峰值落在真实桶上`() {
        val events = listOf(recordAt(now - 2 * HOUR_MS), recordAt(now - 2 * HOUR_MS + 1), recordAt(now - 5 * HOUR_MS))
        val buckets = rollingHourIntensityOf(events, now)
        val peak = peakOf(buckets)

        assertEquals(22, peak.first)
        assertEquals(2f, peak.second)
        assertEquals(now - 2 * HOUR_MS, rollingHourStartAt(now, peak.first))
    }

    @Test
    fun `自然日分桶的老函数保持不变`() {
        // 老函数仍按 0..23 自然日小时分桶（实时页与历史用法依赖它）
        val buckets = hourlyIntensityOf(listOf(recordAt(now)))
        assertEquals(HOURS_PER_DAY, buckets.size)
        assertTrue("至少有一个桶被点亮", buckets.any { it > 0f })
        assertEquals("now 所在的那一自然日小时桶被点亮", 1f, buckets[hourOfTimestamp(now)])
    }
}
