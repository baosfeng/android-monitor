package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集覆盖时间线（纯逻辑）：把采样时间戳切成 COVERED / GAP / DEEP 段。
 *
 * 反向验证（改坏算法时应看到的红）：
 * - 去掉 [CoverageTimeline] 里的合并 → 「全部有采样」那条会断言出 2 段以上；
 * - 把深断档阈值改回 40 分钟 → 35 分钟断档那条从 DEEP 变 GAP（反向验证用）；
 * - 把末段裁剪到末点（不补到窗口终点）→ 恒等式那条段时长之和小于 totalMs。
 */
class CoverageTimelineTest {

    private val dayStart = 1_700_000_000_000L
    private val minute = 60_000L
    private val still = SampleMode.STILL

    /** 静止模式容忍上限：2 x 3 分钟 = 6 分钟。 */
    private val tolerance = LocationPace.STILL_INTERVAL_MS * CoverageTimeline.COVERED_FACTOR

    // ===== 正常路径 =====

    @Test
    fun `全部有采样时覆盖率为100且只有一段`() {
        // 24 小时内每 3 分钟一个点：全部正常
        val stamps = (0..480).map { dayStart + it * 3 * minute }
        val window = CoverageTimeline.build(stamps, dayStart, dayStart + 24 * 60 * minute, still)

        assertEquals(100, window.coveragePct)
        assertEquals("正常节奏下相邻同类型段必须合并成一段", 1, window.segments.size)
        assertEquals(CoverageKind.COVERED, window.segments.first().kind)
        assertEquals(0, window.breakCount)
        assertFalse(window.isEmpty)
    }

    @Test
    fun `覆盖率按时长比例算而不是按段数`() {
        // 12 小时正常 + 12 小时全断（每 3 分钟一个点 → 6 小时段 + 6 小时无采样）
        val stamps = (0..120).map { dayStart + it * 3 * minute }
        val end = dayStart + 12 * 60 * minute
        val window = CoverageTimeline.build(stamps, dayStart, end, still)

        // 最后一个点在 6 小时处，之后 6 小时没有采样 → 覆盖 50%
        assertEquals(50, window.coveragePct)
        assertEquals(1, window.breakCount)
    }

    // ===== 断档分档 =====

    @Test
    fun `中间一个25分钟断档切成三段且中间是GAP`() {
        // 前 1 小时正常采样，然后断 25 分钟（低于 30 分钟深断档阈值），再恢复 1 小时正常
        val before = (0..20).map { dayStart + it * 3 * minute }
        val resume = dayStart + 60 * minute + 25 * minute
        val after = (0..20).map { resume + it * 3 * minute }
        val end = resume + 60 * minute
        val window = CoverageTimeline.build(before + after, dayStart, end, still)

        assertEquals("前段 + 断档 + 后段", 3, window.segments.size)
        assertEquals(CoverageKind.COVERED, window.segments[0].kind)
        assertEquals(CoverageKind.GAP, window.segments[1].kind)
        assertEquals(CoverageKind.COVERED, window.segments[2].kind)
        assertEquals("25 分钟断档是中段时长", 25 * minute, window.segments[1].durationMs)
        assertEquals(1, window.breakCount)
        assertTrue("断档 < 30 分钟属 GAP（深断档阈值 30 分钟）", window.segments[1].kind != CoverageKind.DEEP)
    }

    @Test
    fun `中间一个47分钟断档判成DEEP`() {
        val before = (0..20).map { dayStart + it * 3 * minute }
        val resume = dayStart + 60 * minute + 47 * minute
        val after = (0..20).map { resume + it * 3 * minute }
        val end = resume + 60 * minute
        val window = CoverageTimeline.build(before + after, dayStart, end, still)

        assertEquals(3, window.segments.size)
        assertEquals(CoverageKind.DEEP, window.segments[1].kind)
        assertEquals(47 * minute, window.segments[1].durationMs)
        assertEquals(1, window.breakCount)
    }

    @Test
    fun `断档正好卡在30分钟按DEEP处理`() {
        val before = (0..5).map { dayStart + it * 3 * minute }
        val resume = dayStart + 15 * minute + CoverageTimeline.DEEP_GAP_MS
        val after = listOf(resume, resume + 3 * minute)
        val window = CoverageTimeline.build(before + after, dayStart, resume + 6 * minute, still)

        val deep = window.segments.first { it.kind.isBreak }
        assertEquals(CoverageKind.DEEP, deep.kind)
        assertEquals(CoverageTimeline.DEEP_GAP_MS, deep.durationMs)
    }

    @Test
    fun `断档差1毫秒不到30分钟仍是GAP`() {
        val before = (0..5).map { dayStart + it * 3 * minute }
        val resume = dayStart + 15 * minute + CoverageTimeline.DEEP_GAP_MS - 1
        val after = listOf(resume, resume + 3 * minute)
        val window = CoverageTimeline.build(before + after, dayStart, resume + 6 * minute, still)

        assertEquals(CoverageKind.GAP, window.segments.first { it.kind.isBreak }.kind)
    }

    @Test
    fun `容忍上限内的一次漏拍不算断档`() {
        // 静止模式 3 分钟一个点，中间漏一次（6 分钟间隔）刚好卡在上限上
        val stamps = listOf(dayStart, dayStart + 6 * minute, dayStart + 9 * minute)
        val window = CoverageTimeline.build(stamps, dayStart, dayStart + 15 * minute, still)

        assertEquals(tolerance, 6 * minute)
        assertEquals("整段都算有采样", 1, window.segments.size)
        assertEquals(CoverageKind.COVERED, window.segments.single().kind)
        assertEquals(0, window.breakCount)
    }

    @Test
    fun `超过容忍上限1毫秒就算断档`() {
        // 窗口正好收在末点上：只有中间那 1ms 超限的间隔是断档，首尾两端都贴住采样点
        val second = dayStart + tolerance + 1
        val end = second + 3 * minute
        val window = CoverageTimeline.build(listOf(dayStart, second, end), dayStart, end, still)

        assertEquals(1, window.breakCount)
        assertEquals(tolerance + 1, window.breaks.single().durationMs)
    }

    @Test
    fun `移动模式容忍更紧 10分钟的间隔就是断档`() {
        val stamps = listOf(dayStart, dayStart + 10 * minute, dayStart + 20 * minute)
        val moving = CoverageTimeline.build(stamps, dayStart, dayStart + 30 * minute, SampleMode.MOVING)
        val fixed = CoverageTimeline.build(stamps, dayStart, dayStart + 30 * minute, still)

        assertEquals("移动模式 2x10s=20s，10 分钟就是断档", 1, moving.breakCount)
        assertEquals("静止模式 2x180s=6 分钟，10 分钟也是断档", 1, fixed.breakCount)
        // 10 分钟没到深断档阈值（30 分钟）：两种模式下都只是 GAP
        assertEquals(CoverageKind.GAP, moving.segments.first { it.kind.isBreak }.kind)
        assertEquals(CoverageKind.GAP, fixed.segments.first { it.kind.isBreak }.kind)
    }

    // ===== 边界 =====

    @Test
    fun `空采样返回空段且覆盖率为0`() {
        val window = CoverageTimeline.build(emptyList(), dayStart, dayStart + 24 * 60 * minute, still)
        assertTrue(window.isEmpty)
        assertEquals(0, window.segments.size)
        assertEquals(0, window.coveragePct)
        assertEquals(0, window.breakCount)
    }

    @Test
    fun `段时长之和恒等于窗口时长`() {
        // 乱序 + 重复 + 窗口外的时间戳一起喂进去，边界条件最脏的一组
        val inside = listOf(dayStart + 30 * minute, dayStart + 30 * minute, dayStart + 5 * 60 * minute, dayStart + 2 * 60 * minute)
        val outside = listOf(dayStart - 60 * minute, dayStart + 25 * 60 * minute)
        val end = dayStart + 24 * 60 * minute
        val window = CoverageTimeline.build(inside + outside, dayStart, end, still)

        assertEquals("各段时长之和必须等于窗口时长", window.totalMs, window.segments.sumOf { it.durationMs })
        assertEquals("首段必须从窗口起点开始", dayStart, window.segments.first().startMs)
        assertEquals("末段必须收口到窗口终点", end, window.segments.last().endMs)
        window.segments.zipWithNext().forEach { (a, b) ->
            assertEquals("段之间必须首尾相接、无重叠", a.endMs, b.startMs)
        }
    }

    @Test
    fun `窗口起止正好落在采样点上`() {
        // 窗口两端都是采样点，中间按 3 分钟一个点铺满
        val end = dayStart + 9 * minute
        val window = CoverageTimeline.build((0..3).map { dayStart + it * 3 * minute }, dayStart, end, still)

        assertEquals(window.totalMs, window.segments.sumOf { it.durationMs })
        assertEquals(dayStart, window.segments.first().startMs)
        assertEquals(end, window.segments.last().endMs)
        assertTrue("起止都在采样点上时整窗有采样", window.segments.all { it.kind == CoverageKind.COVERED })
        assertEquals(100, window.coveragePct)
    }

    @Test
    fun `非法窗口返回空结果不抛异常`() {
        val zero = CoverageTimeline.build(listOf(dayStart), dayStart, dayStart, still)
        assertTrue(zero.isEmpty)
        assertEquals(0, zero.coveragePct)

        val reversed = CoverageTimeline.build(listOf(dayStart), dayStart + 1000, dayStart, still)
        assertTrue(reversed.isEmpty)
    }

    @Test
    fun `窗口外的时间戳不参与分段`() {
        val end = dayStart + 60 * minute
        val window = CoverageTimeline.build(
            listOf(dayStart - 10 * minute, dayStart + 30 * minute, end + 10 * minute),
            dayStart,
            end,
            still,
        )
        assertEquals(window.totalMs, window.segments.sumOf { it.durationMs })
        assertTrue(window.segments.all { it.startMs >= dayStart && it.endMs <= end })
    }

    @Test
    fun `单个采样点也能铺满整个窗口`() {
        val end = dayStart + 60 * minute
        val window = CoverageTimeline.build(listOf(dayStart + 30 * minute), dayStart, end, still)
        assertEquals(end - dayStart, window.segments.sumOf { it.durationMs })
        // 前 30 分钟与后 30 分钟都超过容忍上限 → 两段断档，中间一个零长覆盖段被合并掉
        assertTrue(window.breakCount >= 1)
        assertTrue(window.coveragePct < 100)
    }

    // ===== 不补点 =====

    @Test
    fun `不做补点 输出里没有任何插值采样点`() {
        // 断档里没有采样，输出只能是时间区间，不该凭空多出段或点
        val stamps = listOf(dayStart, dayStart + 3 * minute, dayStart + 90 * minute)
        val window = CoverageTimeline.build(stamps, dayStart, dayStart + 120 * minute, still)

        val breakTotal = window.breaks.sumOf { it.durationMs }
        assertEquals(
            "断档时长 = 窗口内无采样的时间，不由采样点插值补齐",
            window.totalMs - window.coveredMs,
            breakTotal,
        )
        // 段的边界只可能落在「采样时间戳」或「窗口边界」上
        val allowed = (stamps + listOf(dayStart, dayStart + 120 * minute)).toSet()
        window.segments.forEach {
            assertTrue("段的起点必须是采样点或窗口起点", it.startMs in allowed)
            assertTrue("段的终点必须是采样点或窗口终点", it.endMs in allowed)
        }
    }

    // ===== 断档列表口径 =====

    @Test
    fun `断档列表最多五条且按时间升序`() {
        // 造 7 条断档：每段正常 2 个点（3 分钟间隔）后接 60 分钟空窗
        val stamps = ArrayList<Long>()
        var cursor = dayStart
        repeat(7) {
            stamps.add(cursor)
            stamps.add(cursor + 3 * minute)
            cursor += 3 * minute + 60 * minute
        }
        stamps.add(cursor)
        stamps.add(cursor + 3 * minute)
        val end = cursor + 6 * minute
        val window = CoverageTimeline.build(stamps, dayStart, end, still)
        val gaps = window.gaps()

        assertEquals("最多 5 条", CoverageTimeline.MAX_GAPS, gaps.size)
        assertEquals("按时间升序", gaps.map { it.startMs }.sorted(), gaps.map { it.startMs })
        assertTrue(gaps.all { it.kind.isBreak })
    }

    @Test
    fun `断档原因按段回溯 归因不到就是null`() {
        val log = CoverageFailureLog()
        val before = listOf(dayStart, dayStart + 3 * minute)
        val resume = dayStart + 60 * minute
        val window = CoverageTimeline.build(
            before + listOf(resume, resume + 3 * minute),
            dayStart,
            resume + 6 * minute,
            still,
        )
        val gap = window.breaks.first()
        assertNull("没有记过失败时必须返回 null，由 UI 写「未知」", window.gaps { log.reasonFor(it.startMs, it.endMs) }.single().reason)

        log.record(gap.startMs + 10 * minute, LocationFailureReason.ACTIVE_FIX_TIMEOUT)
        val gaps = window.gaps { log.reasonFor(it.startMs, it.endMs) }
        assertEquals(LocationFailureReason.ACTIVE_FIX_TIMEOUT, gaps.single().reason)
    }

    @Test
    fun `失败日志不把节流和成功当断档原因`() {
        val log = CoverageFailureLog()
        log.record(dayStart, LocationFailureReason.NONE)
        log.record(dayStart + 1, LocationFailureReason.THROTTLED)
        log.record(dayStart + 2, null)

        assertTrue("NONE / THROTTLED / null 都不该成为断档原因", log.snapshot().isEmpty())
    }

    @Test
    fun `失败日志按容量淘汰最旧的`() {
        val log = CoverageFailureLog(capacity = 3)
        repeat(5) { i -> log.record(dayStart + i.toLong(), LocationFailureReason.EXCEPTION) }
        val kept = log.snapshot()
        assertEquals(3, kept.size)
        assertEquals(dayStart + 2, kept.first().atMs)
    }

    @Test
    fun `轮询观测同一原因十分钟内只记一条`() {
        val log = CoverageFailureLog()
        log.note(dayStart, LocationFailureReason.ACTIVE_FIX_TIMEOUT)
        log.note(dayStart + 60_000L, LocationFailureReason.ACTIVE_FIX_TIMEOUT)
        log.note(dayStart + 2 * minute, LocationFailureReason.ACTIVE_FIX_TIMEOUT)
        assertEquals("同一原因连续轮询不刷屏", 1, log.snapshot().size)

        log.note(dayStart + 3 * minute, LocationFailureReason.EXCEPTION)
        assertEquals("原因变了要记", 2, log.snapshot().size)

        log.note(dayStart + 3 * minute + CoverageFailureLog.DEDUPE_MS + 1, LocationFailureReason.EXCEPTION)
        assertEquals("同一原因隔够时间去重窗口要重新记", 3, log.snapshot().size)
    }

    @Test
    fun `失败日志裁剪掉窗口外的观测`() {
        val log = CoverageFailureLog()
        log.record(dayStart - 10 * minute, LocationFailureReason.EXCEPTION)
        log.record(dayStart + minute, LocationFailureReason.PERMISSION)

        log.pruneBefore(dayStart)
        assertEquals(1, log.snapshot().size)
        assertNull(log.reasonFor(dayStart - 10 * minute, dayStart - 5 * minute))
    }

    // ===== 覆盖率分档（徽标配色口径） =====

    @Test
    fun `覆盖率分档阈值`() {
        assertEquals(CoverageLevel.GOOD, CoverageLevel.of(100))
        assertEquals(CoverageLevel.GOOD, CoverageLevel.of(90))
        assertEquals(CoverageLevel.WARN, CoverageLevel.of(89))
        assertEquals(CoverageLevel.WARN, CoverageLevel.of(60))
        assertEquals(CoverageLevel.LOW, CoverageLevel.of(59))
        assertEquals(CoverageLevel.LOW, CoverageLevel.of(0))
    }

    // ===== 覆盖文案（设置页与行程页共用，措辞错了用户会读错事实） =====

    @Test
    fun `时长文案分档`() {
        assertEquals("0 分钟", coverageDurationText(0))
        assertEquals("不到 1 分钟", coverageDurationText(59_000L))
        assertEquals("1 分钟", coverageDurationText(60_000L))
        assertEquals("47 分钟", coverageDurationText(47 * minute))
        assertEquals("1 小时", coverageDurationText(60 * minute))
        assertEquals("18 小时 32 分", coverageDurationText(18 * 60 * minute + 32 * minute))
    }

    @Test
    fun `断档时长不足一分钟按一分钟算`() {
        assertEquals("1 分钟", formatCoverageGapMinutes(1_000L))
        assertEquals("35 分钟", formatCoverageGapMinutes(35 * minute))
        assertEquals("1 小时 2 分", formatCoverageGapMinutes(62 * minute))
    }

    @Test
    fun `断档汇总主句今天与历史日期分开写`() {
        assertEquals("今天没有断档", coverageGapHeadline(0, isToday = true))
        assertEquals("今天断了 2 次", coverageGapHeadline(2, isToday = true))
        assertEquals("这一天没有断档", coverageGapHeadline(0, isToday = false))
        assertEquals("这一天断了 3 次", coverageGapHeadline(3, isToday = false))
    }

    @Test
    fun `断档汇总副标题三段以内逐条列 超出折成等N处`() {
        val clock: (Long) -> String = { ms -> if (ms == dayStart) "03:12" else "14:20" }
        val two = listOf(
            CoverageGap(dayStart, 35 * minute, CoverageKind.GAP),
            CoverageGap(dayStart, 47 * minute, CoverageKind.DEEP),
        )
        assertEquals("03:12 起 35 分钟 · 03:12 起 47 分钟", coverageGapNote(two, 2, clock))

        val five = (1..5).map { CoverageGap(dayStart, it * minute, CoverageKind.GAP) }
        val note = coverageGapNote(five, 5, clock)
        assertTrue("超过 3 条要折成等 N 处", note.endsWith("等 5 处"))
        assertEquals("没有断档时整段不渲染", "", coverageGapNote(emptyList(), 0, clock))
    }

    @Test
    fun `汇总副标题按真实断档次数而不是列表长度`() {
        // 列表被截到 3 条，但真实断了 7 次：不能写成「等 3 处」
        val shown = (1..3).map { CoverageGap(dayStart, it * minute, CoverageKind.GAP) }
        val note = coverageGapNote(shown, totalBreaks = 7) { "03:12" }
        assertTrue(note.endsWith("等 7 处"))
    }

    @Test
    fun `副标题永远是过去24小时断档N次`() {
        val empty = CoverageTimeline.build(emptyList(), dayStart, dayStart + 24 * 60 * minute, still)
        assertEquals("空窗口 N 写 0，「一条都没有」由卡内空态说明", "过去 24 小时 · 断档 0 次", coverageSubtitle(empty))

        val some = CoverageTimeline.build(listOf(dayStart, dayStart + 3 * minute), dayStart, dayStart + 10 * minute, still)
        assertEquals("过去 24 小时 · 断档 1 次", coverageSubtitle(some))
    }
}
