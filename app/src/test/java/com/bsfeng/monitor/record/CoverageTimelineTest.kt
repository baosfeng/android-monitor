package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集覆盖时间线（纯逻辑）：把采样时间戳切成 COVERED / DORMANT / GAP / DEEP 段。
 *
 * 反向验证（改坏算法时应看到的红）：
 * - 去掉 [CoverageTimeline] 里的合并 → 「全部有采样」那条会断言出 2 段以上；
 * - 把待机上限改回容忍上限（6 分钟）→ 「静止兜底节奏不算断档」那条冒出几十段断档；
 * - 把档位判断顺序调成先判断档再判待机 → 待机上限边界那条会变成 GAP；
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
    fun `中间一个25分钟空洞是省电待机而不是断档`() {
        // 前 1 小时正常采样，然后空 25 分钟（落在兜底节奏 35 分钟以内），再恢复 1 小时正常
        val before = (0..20).map { dayStart + it * 3 * minute }
        val resume = dayStart + 60 * minute + 25 * minute
        val after = (0..20).map { resume + it * 3 * minute }
        val end = resume + 60 * minute
        val window = CoverageTimeline.build(before + after, dayStart, end, still)

        assertEquals("前段 + 待机 + 后段", 3, window.segments.size)
        assertEquals(CoverageKind.COVERED, window.segments[0].kind)
        assertEquals(CoverageKind.DORMANT, window.segments[1].kind)
        assertEquals(CoverageKind.COVERED, window.segments[2].kind)
        assertEquals("25 分钟待机是中段时长", 25 * minute, window.segments[1].durationMs)
        assertEquals("待机不算断档", 0, window.breakCount)
        assertEquals("待机算正常覆盖", 100, window.coveragePct)
    }

    @Test
    fun `断档列表整条排除待机段`() {
        val before = (0..20).map { dayStart + it * 3 * minute }
        val resume = dayStart + 60 * minute + CoverageTimeline.DORMANT_CEILING_MS
        val after = (0..20).map { resume + it * 3 * minute }
        val window = CoverageTimeline.build(before + after, dayStart, resume + 60 * minute, still)

        assertEquals("待机上限整点仍是待机", CoverageKind.DORMANT, window.segments[1].kind)
        assertTrue("断档列表里不能出现待机段", window.gaps().isEmpty())
    }

    @Test
    fun `中间一个47分钟空洞是GAP而不是待机`() {
        // 47 分钟已经越过兜底节奏（35 分钟）：兜底没跑，这是真断档
        val before = (0..20).map { dayStart + it * 3 * minute }
        val resume = dayStart + 60 * minute + 47 * minute
        val after = (0..20).map { resume + it * 3 * minute }
        val end = resume + 60 * minute
        val window = CoverageTimeline.build(before + after, dayStart, end, still)

        assertEquals(3, window.segments.size)
        assertEquals(CoverageKind.GAP, window.segments[1].kind)
        assertEquals(47 * minute, window.segments[1].durationMs)
        assertEquals(1, window.breakCount)
    }

    @Test
    fun `断档正好卡在2小时按DEEP处理`() {
        val before = (0..5).map { dayStart + it * 3 * minute }
        val resume = dayStart + 15 * minute + CoverageTimeline.DEEP_GAP_MS
        val after = listOf(resume, resume + 3 * minute)
        val window = CoverageTimeline.build(before + after, dayStart, resume + 6 * minute, still)

        val deep = window.segments.first { it.kind.isBreak }
        assertEquals(CoverageKind.DEEP, deep.kind)
        assertEquals(CoverageTimeline.DEEP_GAP_MS, deep.durationMs)
    }

    @Test
    fun `断档差1毫秒不到2小时仍是GAP`() {
        val before = (0..5).map { dayStart + it * 3 * minute }
        val resume = dayStart + 15 * minute + CoverageTimeline.DEEP_GAP_MS - 1
        val after = listOf(resume, resume + 3 * minute)
        val window = CoverageTimeline.build(before + after, dayStart, resume + 6 * minute, still)

        assertEquals(CoverageKind.GAP, window.segments.first { it.kind.isBreak }.kind)
    }

    @Test
    fun `待机上限差1毫秒是待机 超过1毫秒就是断档`() {
        // 边界两测：35 分钟整仍是兜底允许的待机；越过 1ms 才叫断档
        val atCeiling = dayStart + CoverageTimeline.DORMANT_CEILING_MS
        val inWindow = CoverageTimeline.build(
            listOf(dayStart, atCeiling, atCeiling + 3 * minute),
            dayStart,
            atCeiling + 6 * minute,
            still,
        )
        assertEquals("35 分钟整仍是待机", CoverageKind.DORMANT, inWindow.segments[0].kind)
        assertEquals("待机不算断档", 0, inWindow.breakCount)

        val justOver = dayStart + CoverageTimeline.DORMANT_CEILING_MS + 1
        val over = CoverageTimeline.build(
            listOf(dayStart, justOver, justOver + 3 * minute),
            dayStart,
            justOver + 6 * minute,
            still,
        )
        assertEquals("越过待机上限 1ms 就是断档", 1, over.breakCount)
        assertEquals(CoverageKind.GAP, over.segments.first { it.kind.isBreak }.kind)
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
    fun `超过容忍上限但仍在兜底节奏内算待机`() {
        // 窗口正好收在末点上：中间那 1ms 超限的间隔是待机，不算断档
        val second = dayStart + tolerance + 1
        val end = second + 3 * minute
        val window = CoverageTimeline.build(listOf(dayStart, second, end), dayStart, end, still)

        assertEquals("待机不算断档", 0, window.breakCount)
        assertEquals("超限的那一段是待机", CoverageKind.DORMANT, window.segments[0].kind)
        assertEquals(tolerance + 1, window.segments[0].durationMs)
    }

    @Test
    fun `待机档与模式无关 两种模式下同一空洞同一档`() {
        // 待机是「门禁睡着」的呈现档，不跟当前采样模式走：
        // 若按模式收紧，用户在移动中打开设置页时，24 小时窗口里的静止期会被整片画成断档。
        val stamps = listOf(dayStart, dayStart + 10 * minute, dayStart + 20 * minute)
        val moving = CoverageTimeline.build(stamps, dayStart, dayStart + 30 * minute, SampleMode.MOVING)
        val fixed = CoverageTimeline.build(stamps, dayStart, dayStart + 30 * minute, still)

        assertEquals("10 分钟在兜底节奏内，两模式都是待机", 0, moving.breakCount)
        assertEquals(0, fixed.breakCount)
        assertTrue("整窗都是待机（同为一种颜色会合并成一段）", moving.segments.all { it.kind == CoverageKind.DORMANT })
        assertTrue(fixed.segments.all { it.kind == CoverageKind.DORMANT })
    }

    @Test
    fun `移动模式下超过兜底节奏照样报断档`() {
        val stamps = listOf(dayStart, dayStart + 60 * minute, dayStart + 63 * minute)
        val moving = CoverageTimeline.build(stamps, dayStart, dayStart + 90 * minute, SampleMode.MOVING)

        assertEquals("移动中 1 小时没点必须报断档", 1, moving.breakCount)
        assertEquals(CoverageKind.GAP, moving.segments.first { it.kind.isBreak }.kind)
    }

    // ===== 回归：真机静止兜底节奏 =====

    @Test
    fun `静止期只按30分钟兜底采样时不报任何断档`() {
        // 真机实测形态：门禁 SLEEPING 时每 30~30.5 分钟兜底一个点（服务层 STILL_FALLBACK_MS）
        val stamps = (0..48).map { dayStart + it * 30 * minute + (it % 2) * 30_000L }
        val end = dayStart + 24 * 60 * minute
        val window = CoverageTimeline.build(stamps, dayStart, end, still)

        assertEquals("兜底节奏内的空洞全是待机，不能报断档", 0, window.breakCount)
        assertTrue("待机算正常覆盖，覆盖率必须接近 100%", window.coveragePct >= 95)
        assertTrue("待机段要能画出来", window.dormantMs > 6 * 60 * minute)
    }

    @Test
    fun `兜底停了才是断档 2小时没点判DEEP`() {
        val stamps = listOf(dayStart, dayStart + 3 * minute) +
            listOf(dayStart + 3 * minute + 150 * minute, dayStart + 156 * minute)
        val window = CoverageTimeline.build(stamps, dayStart, dayStart + 240 * minute, still)

        assertEquals(2, window.breakCount)
        assertTrue("150 分钟没点越过 2 小时深断档阈值", window.breaks.any { it.kind == CoverageKind.DEEP })
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
        // 前后各 30 分钟都在兜底节奏（35 分钟）以内 → 两段待机，不算断档
        assertEquals(0, window.breakCount)
        assertEquals(100, window.coveragePct)
        assertEquals(CoverageKind.DORMANT, window.segments.first().kind)
        assertEquals(CoverageKind.DORMANT, window.segments.last().kind)
    }

    // ===== 不补点 =====

    @Test
    fun `不做补点 输出里没有任何插值采样点`() {
        // 断档里没有采样，输出只能是时间区间，不该凭空多出段或点
        val stamps = listOf(dayStart, dayStart + 3 * minute, dayStart + 90 * minute)
        val window = CoverageTimeline.build(stamps, dayStart, dayStart + 120 * minute, still)

        val breakTotal = window.breaks.sumOf { it.durationMs }
        assertEquals(
            "断档时长 = 窗口内既没采样、也不算待机的时长，不由采样点插值补齐",
            window.totalMs - window.coveredMs - window.dormantMs,
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
    fun `断档列表最多五条且按时间倒序取最新`() {
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
        assertEquals("按时间倒序：最近的断档排第一", gaps.map { it.startMs }.sortedDescending(), gaps.map { it.startMs })
        assertTrue(gaps.all { it.kind.isBreak })
        assertEquals("取的是最新的 5 段而不是最早的 5 段", window.breaks.takeLast(5).reversed().map { it.startMs }, gaps.map { it.startMs })
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
            CoverageGap(dayStart, 150 * minute, CoverageKind.DEEP),
        )
        assertEquals("03:12 起 35 分钟 · 03:12 起 2 小时 30 分", coverageGapNote(two, 2, clock))

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

        // 空 57 分钟越过兜底节奏 → 真断档
        val some = CoverageTimeline.build(listOf(dayStart, dayStart + 3 * minute), dayStart, dayStart + 60 * minute, still)
        assertEquals("过去 24 小时 · 断档 1 次", coverageSubtitle(some))
    }
}
