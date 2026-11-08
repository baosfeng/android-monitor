package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UsageTracker（APP_USAGE 时长状态机）单元测试。
 *
 * 纯 JVM：时间戳一律由用例显式传入，不依赖真实时钟，也不需要 Robolectric。
 *
 * 回归重点是[断层]：同一 App 两次 tick 间隔 > 2s（息屏 8 小时 / 进程冻结 / 服务被杀）时，
 * 这段空档**不是**使用时长。修复前的 flush(timestamp) 把它整段计入，
 * 实测出现「单条 39,470,111ms（10.96 小时），而当天只过去 648 分钟」。
 */
class UsageTrackerTest {

    private companion object {
        const val PKG = "com.example.app"
        const val NAME = "示例应用"
        const val OTHER_PKG = "com.example.other"
        const val OTHER_NAME = "另一个应用"

        const val ONE_SEC = 1_000L
        /** 息屏 / 冻结 8 小时 */
        const val GAP_8H = 8 * 60 * 60 * 1000L
    }

    private val t0 = 1_700_000_000_000L
    private val records = mutableListOf<Record>()
    private val tracker = UsageTracker { record -> records += record }

    private fun tick(offsetMs: Long, pkg: String = PKG, name: String = NAME) =
        tracker.tick(pkg, name, t0 + offsetMs)

    private fun usageRecords(): List<Record> =
        records.filter { it.type == RecordType.APP_USAGE }

    // ==================== 正常路径 ====================

    @Test
    fun `连续 3 次 1 秒 tick 只记 2 秒`() {
        tick(0)
        tick(ONE_SEC)
        tick(2 * ONE_SEC)
        tracker.flush(t0 + 2 * ONE_SEC)

        val record = usageRecords().single()
        assertEquals("首跳只是起点，3 次 tick 真实跨过 2 段 1 秒", 2 * ONE_SEC, record.duration)
        assertEquals("记录时间戳取 flush 时刻", t0 + 2 * ONE_SEC, record.timestamp)
        assertEquals(NAME, record.label)
        assertEquals(PKG, record.detail?.get("package_name"))
    }

    @Test
    fun `切换 App 时结算上一段`() {
        tick(0)
        tick(ONE_SEC)
        tick(2 * ONE_SEC, OTHER_PKG, OTHER_NAME)
        tracker.flush(t0 + 2 * ONE_SEC)

        val first = usageRecords().single()
        assertEquals("切走时把已累计的 2 秒写掉", 2 * ONE_SEC, first.duration)
        assertEquals(PKG, first.detail?.get("package_name"))
    }

    // ==================== 断层：P0 的回归断言 ====================

    @Test
    fun `断层 8 小时只结算到断层起点 不把空档算成使用时长`() {
        tick(0)
        tick(ONE_SEC)
        tick(2 * ONE_SEC)
        // 中间缺失 8 小时（息屏 / 进程冻结）：修复前这里写入 8 小时
        tick(2 * ONE_SEC + GAP_8H)

        val record = usageRecords().single()
        assertEquals("断层那段必须被丢弃，只能记已经确认在用的 2 秒", 2 * ONE_SEC, record.duration)
        assertEquals("记录时间戳取断层起点（最后一跳），不是本次 tick", t0 + 2 * ONE_SEC, record.timestamp)
        assertTrue(
            "任何一条 APP_USAGE 都不得长于 8 小时：" + record.duration + "ms",
            (record.duration ?: 0L) < GAP_8H,
        )
    }

    @Test
    fun `断层后新 session 从第二次 tick 开始重新计时`() {
        tick(0)
        tick(ONE_SEC)
        tick(2 * ONE_SEC)
        val gapStart = 2 * ONE_SEC + GAP_8H

        // 断层这一跳本身只作为新 session 的起点，不贡献时长
        tick(gapStart)
        assertEquals("断层只写断层前那一条", 1, usageRecords().size)

        // 新 session：再走 2 段 1 秒 → 2 秒（不含断层那段）
        tick(gapStart + ONE_SEC)
        tick(gapStart + 2 * ONE_SEC)
        tracker.flush(t0 + gapStart + 2 * ONE_SEC)

        val second = usageRecords().last()
        assertEquals("断层之后重新开始计时，只有 2 秒", 2 * ONE_SEC, second.duration)
        assertEquals(2, usageRecords().size)
    }

    @Test
    fun `断层后立刻 flush 不写 0 时长记录`() {
        tick(0)
        tick(ONE_SEC)
        tick(2 * ONE_SEC)
        val gapStart = 2 * ONE_SEC + GAP_8H
        tick(gapStart)
        tracker.flush(t0 + gapStart)

        val record = usageRecords().single()
        assertEquals("空 session 不得补一条 0 时长记录，否则事件流被噪声填满", 2 * ONE_SEC, record.duration)
    }

    @Test
    fun `断层后累计为空时不写 0 时长记录`() {
        tick(0)
        // 首跳刚建立 session（累计 0）就断 8 小时：没有任何可结算的时长
        tick(GAP_8H)
        assertEquals(0, usageRecords().size)
    }

    @Test
    fun `间隔 2000ms 仍累加 超过阈值才判为断层`() {
        tick(0)
        tick(2_000L)          // delta = 2000，不超阈值 → 累加 2 秒
        tick(4_001L)          // delta = 2001，超阈值 → 断层，只结算到 4_001 之前已经累计的 2 秒

        val record = usageRecords().single()
        assertEquals("阈值内的间隔照常累加，超阈值那跳的 1ms 不得计入", 2_000L, record.duration)
        assertEquals("记录时间戳取断层起点", t0 + 2_000L, record.timestamp)
    }

    // ==================== 息屏路径：不能被一起改坏 ====================

    @Test
    fun `息屏 flush 仍累计最后一跳 断层的丢空档规则不影响它`() {
        tick(0)
        tick(ONE_SEC)
        // 息屏发生在 1.5 秒：RecordCollector.onScreenChanged("OFF") → usageTracker.flush(timestamp)
        tracker.flush(t0 + 1_500L)

        val record = usageRecords().single()
        assertEquals("息屏那一刻前最后一跳（500ms）仍要计入", 1_500L, record.duration)
        assertEquals(t0 + 1_500L, record.timestamp)
    }

    @Test
    fun `息屏 flush 后不残留 session`() {
        tick(0)
        tick(ONE_SEC)
        tracker.flush(t0 + ONE_SEC)
        tracker.flush(t0 + 5 * ONE_SEC)
        assertEquals("重复 flush 不得写第二条", 1, usageRecords().size)
        assertEquals("flush 后不得残留 session", null, tracker.getActiveSession())
    }
}
