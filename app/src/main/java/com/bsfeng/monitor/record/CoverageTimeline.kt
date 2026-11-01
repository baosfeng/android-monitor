package com.bsfeng.monitor.record

// 从 LOCATION 采样时间戳算出「采集覆盖时间线」：把一整段时间切成互不重叠的色块。
// 纯逻辑（不碰 Android / 数据库 / 时钟），因此断档判定能在纯 JVM 单测里逐条钉死。
//
// 为什么需要它：掉线原来只在 LocationStatus 里有瞬时状态，进程重启就归零，事后完全不可查。
// 有了这条时间线，「过去 24 小时断了哪几段、各多久、为什么」才成为可验收的数据 ——
// 它不是 P0-2 的附属品，而是 P0-2 做完之后唯一能拿来验收的东西。
//
// 两条边界（改算法前先读）：
// 1. **不补点**：这里只有时间，没有坐标、没有插值；断档就是断档，不猜中间发生了什么；
// 2. **不猜原因**：分段只按时间戳判，失败原因由调用方从 LocationStatus 取，本文件不认识它。

import kotlin.math.roundToInt

/**
 * 覆盖段类型。
 *
 * [COVERED] 有采样且间隔在容忍范围内；[DORMANT] **省电待机**（门禁睡着时按
 * [CoverageTimeline.DORMANT_CEILING_MS] 兜底节奏空着，是设计内行为、不是掉线）；
 * [GAP] 断档但不到 [CoverageTimeline.DEEP_GAP_MS]；[DEEP] 深断档；
 * [OFF] 功能关闭时段（当前无可靠依据判定，保留给后续接入）。
 */
enum class CoverageKind {
    COVERED,
    DORMANT,
    GAP,
    DEEP,
    OFF,
    ;

    /** 是否算作「断档」（GAP / DEEP）：断档次数、断档列表、徽标配色都按这个口径。 */
    val isBreak: Boolean get() = this == GAP || this == DEEP

    /** 是否算作「正常」（有采样或设计内待机）：覆盖率按这个口径算，待机不能拉低覆盖率。 */
    val isHealthy: Boolean get() = this == COVERED || this == DORMANT
}

/**
 * 一段覆盖。**首尾相接、无重叠、合起来正好盖满整个窗口**（见 [CoverageWindow]）。
 *
 * [startMs] 闭、[endMs] 开（半开区间）：上一段的 endMs 就是下一段的 startMs，
 * 「各段时长之和 == 窗口时长」因此是恒等式，不需要任何补 1ms 的修正项。
 */
data class CoverageSegment(
    val startMs: Long,
    val endMs: Long,
    val kind: CoverageKind,
) {
    /** 段时长；半开区间下不会为负，这里仍夹一次兜底非法入参。 */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 一个时间窗的覆盖结果。
 *
 * 三条硬约束（单测逐条钉住）：
 * 1. 段连续覆盖整个窗口、无重叠、首尾与窗口边界对齐（各段时长之和 == [totalMs]）；
 * 2. 相邻同类型段已合并，因此「全都是正常采样」时 [segments] 只有 1 段；
 * 3. 空采样时 [segments] 为空、[coveragePct] 为 0 —— UI 据此走空态，不画空条。
 */
data class CoverageWindow(
    val startMs: Long,
    val endMs: Long,
    val segments: List<CoverageSegment> = emptyList(),
) {
    val totalMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** 有采样的时长合计。 */
    val coveredMs: Long get() = segments.filter { it.kind == CoverageKind.COVERED }.sumOf { it.durationMs }

    /** 省电待机时长合计（门禁睡着，不该被读成掉线）。 */
    val dormantMs: Long get() = segments.filter { it.kind == CoverageKind.DORMANT }.sumOf { it.durationMs }

    /**
     * 覆盖率（0..100 整数，四舍五入）= (有采样 + 省电待机) / 窗口；窗口非法或没有采样时为 0。
     *
     * 为什么待机也算「有覆盖」：门禁 SLEEPING 时本来就不采点（用户要的省电），
     * 把这段算成掉线会让覆盖率在静止的一天里掉到个位数、徽标常年变红 ——
     * 那是口径错误，不是设备故障。真掉线仍在 [breaks] 里如实分段。
     */
    val coveragePct: Int
        get() {
            val total = totalMs
            if (total <= 0L) return 0
            val healthy = segments.filter { it.kind.isHealthy }.sumOf { it.durationMs }
            return ((healthy.toDouble() / total) * 100.0).roundToInt().coerceIn(0, 100)
        }

    /** 断档段（GAP + DEEP），时间升序。 */
    val breaks: List<CoverageSegment> get() = segments.filter { it.kind.isBreak }

    /** 断档次数：GAP 与 DEEP 段的总数（合并后的一段断档只算一次）。 */
    val breakCount: Int get() = breaks.size

    /** 是否一条采样都没有。 */
    val isEmpty: Boolean get() = segments.isEmpty()
}

/**
 * 覆盖时间线的**全部规则**：采样时间戳 + 窗口 + 模式 → 分段覆盖。
 *
 * 分段口径（按间隔从短到长四档，先判短档）：
 * - <= [COVERED_FACTOR] 倍模式间隔 → COVERED；
 * - <= [DORMANT_CEILING_MS] → **DORMANT（省电待机）**：门禁 SLEEPING 时的兜底节奏；
 * - < [DEEP_GAP_MS] → GAP；
 * - 其余 → DEEP；
 * - 首个采样之前、最后一个采样之后同样按此规则判（两端断档不能被裁掉）；
 * - 相邻同类型段合并（否则「全程正常」会被相邻采样切成几十段，覆盖条无法阅读）。
 *
 * 为什么覆盖阈值取「2 倍模式间隔」而自愈阈值取 1.5 倍（见 [LocationGapHealing]）：
 * 自愈阈值是**动作阈值**（过了它就强制补采），覆盖条是**呈现阈值**（用户看到「有采样」）。
 * 静止 3 分钟一个点时，1.5 倍 = 4.5 分钟，偶发的一次漏拍就会把覆盖条画成断档；
 * 2 倍给一次漏拍的余量，用户看到的断档才都是真断档。
 *
 * 为什么必须有 DORMANT 这一档（真机数据推翻过旧口径）：
 * 功耗门禁上线后，静止时 [com.bsfeng.monitor.record.MotionGate] 停在 SLEEPING，
 * 只按 30 分钟兜底采一个点。旧口径按「2 倍 STILL 间隔 = 6 分钟」判覆盖，
 * 于是每两个兜底点之间（约 30 分钟）都被画成断档 —— 真机 24 小时实测出 40+ 段，
 * 用户看到的就是「一直在断档」。那些洞是**省电设计的正常静默**，与掉线是两件事，
 * 必须分开呈现：待机不动、断档报警。
 */
object CoverageTimeline {

    /** 覆盖判定余量：相邻采样间隔 <= 2 倍模式间隔即算「有采样」。 */
    const val COVERED_FACTOR = 2

    /** 断档列表最多列几条：再多也读不完，第 6 条起不渲染也不给「更多」入口。 */
    const val MAX_GAPS = 5

    /**
     * 省电待机上限：35 分钟。
     *
     * 取值为服务层兜底节奏 [LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS]（30 分钟）+ 5 分钟调度余量：
     * 兜底由门禁 ticker 触发（SLEEPING 最长 5 分钟一拍，到期前会按剩余时间排最后一拍），实测落在 30:00~30:30；
     * 不留余量会把每次兜底晚到 20 秒判成断档，比不修还吵。
     *
     * 边界是「动作阈值 vs 呈现阈值」的分工：超过它就是真没采到点（服务被杀、权限被撤、
     * 心跳停摆），才配叫断档。
     */
    const val DORMANT_CEILING_MS = 35 * 60_000L

    /**
     * 深断档阈值：2 小时。
     *
     * 与 [LocationGapHealing.DEEP_GAP_MS]（30 分钟）**不同值也不同语义**，别合并：
     * 那边是动作阈值（过了就重建订阅），这边是呈现阈值（GAP / DEEP 分色）。
     * 曾经取同一个 30 分钟，待机档引入后两者必然打架 —— 35 分钟的待机上限已经越过 30，
     * 深断档若还留在 30 分钟，GAP 区间会被压空、图例里「断档」永远不出现。
     */
    const val DEEP_GAP_MS = 2 * 60 * 60_000L

    /**
     * 算一个窗口的覆盖分段。
     *
     * 输入异常一律按「空」处理，不抛异常：窗口非法（end <= start）返回空窗口；
     * 采样时间戳落在窗口外、重复、乱序都被归一到窗口内且去重排序 —— 调用方不必先清洗。
     *
     * @param sampleTimestamps LOCATION 记录的时间戳（毫秒），顺序与去重不限
     * @param startMs 窗口起点（闭）
     * @param endMs 窗口终点（开）
     * @param mode 当前采样模式（决定「多密算正常」）
     */
    fun build(
        sampleTimestamps: List<Long>,
        startMs: Long,
        endMs: Long,
        mode: SampleMode = SampleMode.STILL,
    ): CoverageWindow {
        if (endMs <= startMs) return CoverageWindow(startMs, endMs, emptyList())
        // 裁剪到窗口内 + 去重 + 升序：窗口外的点不参与分段（否则会把段撑破窗口）。
        // 右端取闭区间：采样点正好落在窗口终点时，它仍属于这个窗口（否则「窗口起止正好
        // 落在采样点上」会少算一段覆盖）；落在终点上的点产生的尾部零长段在下面统一丢弃。
        val stamps = sampleTimestamps
            .filter { it >= startMs && it <= endMs }
            .distinct()
            .sorted()
        if (stamps.isEmpty()) return CoverageWindow(startMs, endMs, emptyList())

        val tolerance = toleranceOf(mode)
        val raw = ArrayList<CoverageSegment>(stamps.size + 2)

        // 首点之前：从窗口起点到第一个采样（没有「上一次采样」可比，按间隔本身定性）
        raw.add(CoverageSegment(startMs, stamps.first(), kindOf(stamps.first() - startMs, tolerance)))
        // 相邻采样之间
        stamps.zipWithNext().forEach { (prev, next) ->
            raw.add(CoverageSegment(prev, next, kindOf(next - prev, tolerance)))
        }
        // 末点之后：到窗口终点（末尾断档同样要能看见）
        raw.add(CoverageSegment(stamps.last(), endMs, kindOf(endMs - stamps.last(), tolerance)))

        // 零长段（采样正好压在窗口边界上时会有）直接丢掉：它既画不出色块，也会把段数算多
        return CoverageWindow(startMs, endMs, merge(raw.filter { it.durationMs > 0L }))
    }

    /** 该模式的「正常间隔」容忍上限：2 倍模式间隔。 */
    fun toleranceOf(mode: SampleMode): Long = LocationPace.intervalOf(mode) * COVERED_FACTOR

    /** 一个间隔的定性：容忍内 COVERED；兜底节奏内 DORMANT；不到深断档 GAP；否则 DEEP。 */
    private fun kindOf(gapMs: Long, tolerance: Long): CoverageKind = when {
        gapMs <= tolerance -> CoverageKind.COVERED
        gapMs <= DORMANT_CEILING_MS -> CoverageKind.DORMANT
        gapMs < DEEP_GAP_MS -> CoverageKind.GAP
        else -> CoverageKind.DEEP
    }

    /** 合并相邻同类型段：段类型不变时只延长上一段的 endMs，段数与色块数保持可读。 */
    private fun merge(segments: List<CoverageSegment>): List<CoverageSegment> {
        val out = ArrayList<CoverageSegment>(segments.size)
        for (segment in segments) {
            val last = out.lastOrNull()
            if (last != null && last.kind == segment.kind && last.endMs == segment.startMs) {
                out[out.size - 1] = last.copy(endMs = segment.endMs)
            } else {
                out.add(segment)
            }
        }
        return out
    }
}

/**
 * 覆盖率分档：决定徽标配色（设置页采集覆盖卡）。
 *
 * 阈值放在 record 层而不是 UI 里：它是「覆盖率多少算健康」的数据口径，
 * 行程页与设置页要共用同一套判断，不该各写一行 when。
 */
enum class CoverageLevel {
    /** >= 90%：正常，success 色。 */
    GOOD,

    /** 60% ~ 89%：警告，warn 色。 */
    WARN,

    /** < 60%：危险，danger 色。 */
    LOW,
    ;

    companion object {
        /** GOOD 下限（含）。 */
        const val GOOD_PCT = 90

        /** WARN 下限（含）；低于它是 LOW。 */
        const val WARN_PCT = 60

        fun of(pct: Int): CoverageLevel = when {
            pct >= GOOD_PCT -> GOOD
            pct >= WARN_PCT -> WARN
            else -> LOW
        }
    }
}

/**
 * 断档列表的一行（展示前的纯数据）。
 *
 * [reason] 为 null 表示无法归因 —— UI 写「未知」，**不编原因**。
 */
data class CoverageGap(
    val startMs: Long,
    val durationMs: Long,
    val kind: CoverageKind,
    val reason: LocationFailureReason? = null,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * 断档列表口径：**按时间倒序（最新的在前）**、最多 [MAX_GAPS] 条、只取 GAP / DEEP。
 *
 * 为什么是倒序而不是升序（旧口径写反过）：这个列表回答的是「最近断在那儿」，
 * 用户点开设置页第一眼要看的正是刚刚那段 —— 升序 + take(5) 会把最新的断档
 * 全部截在屏幕外（真机 24 小时有 40+ 段，永远只看到凌晨最早那 5 段）。
 * 时间线方向仍由覆盖条承担（左旧右新），列表本身按「最值得看」排序。
 *
 * 为什么截断在最新的几条而不是最长的几条：挑最长的会打乱时间顺序，
 * 读起来像随机采样；最新的几条至少还在「刚才发生了什么」的上下文里。
 */
fun CoverageWindow.gaps(
    limit: Int = CoverageTimeline.MAX_GAPS,
    reasonOf: (CoverageSegment) -> LocationFailureReason? = { null },
): List<CoverageGap> = breaks
    .asReversed()
    .take(limit.coerceAtLeast(0))
    .map { CoverageGap(it.startMs, it.durationMs, it.kind, reasonOf(it)) }

/**
 * 断档归因：把「某段断档期间最后一次失败原因」记录进进程内的小日志，并按段回溯。
 *
 * 为什么要自己记：`LocationFailureReason` 原本只有**当前值**（[LocationStatus.lastFailure]），
 * 一秒钟之后就被下一条覆盖，落库的记录里也没有失败原因。不记这一笔，历史断档只能写「未知」。
 *
 * 边界：日志是进程内的，进程被杀就归零 —— 重启后回看历史断档一律「未知」。
 * 这是当前数据源的硬限制（失败原因不落库），不在这里假装能查到。
 * 容量固定 [CAPACITY] 条，只保留窗口内最近的失败，避免设置页长开时无界增长。
 */
class CoverageFailureLog(private val capacity: Int = CAPACITY) {

    private val entries = ArrayDeque<CoverageFailure>()

    /**
     * 记一次观测。
     *
     * 只收**真失败**：NONE 表示上次成功，THROTTLED 按 [LocationFailureReason] 的注释本来
     * 「不是故障，只是这次不发令」。把这两种写成断档原因，会让一条 40 分钟的黑屏
     * 被归因成「节流中」，比留空写「未知」更误导。
     */
    @Synchronized
    fun record(atMs: Long, reason: LocationFailureReason?) {
        if (reason == null || reason == LocationFailureReason.NONE || reason == LocationFailureReason.THROTTLED) {
            return
        }
        entries.addLast(CoverageFailure(atMs, reason))
        while (entries.size > capacity) entries.removeFirst()
    }

    /**
     * 轮询式观测入口：**同一原因在 [DEDUPE_MS] 内只记一次**。
     *
     * 采集页每分钟读一次当前状态，不去重的话，一段持续失败的断档会刷进几十条
     * 同原因记录，把容量挤满、把更早的其它原因挤掉。
     */
    @Synchronized
    fun note(atMs: Long, reason: LocationFailureReason?) {
        if (reason == null || reason == LocationFailureReason.NONE || reason == LocationFailureReason.THROTTLED) {
            return
        }
        val last = entries.lastOrNull()
        val duplicate = last != null && last.reason == reason && (atMs - last.atMs) in 0..DEDUPE_MS
        if (duplicate) return
        entries.addLast(CoverageFailure(atMs, reason))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** 只留 [sinceMs] 之后的观测（窗口左边界变了就丢掉窗口外的，避免把老原因安到新断档上）。 */
    @Synchronized
    fun pruneBefore(sinceMs: Long) {
        while (entries.isNotEmpty() && entries.first().atMs < sinceMs) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<CoverageFailure> = entries.toList()

    /** 段内（含段尾）最后一次失败；没有就 null（UI 写「未知」）。 */
    @Synchronized
    fun reasonFor(startMs: Long, endMs: Long): LocationFailureReason? =
        entries.lastOrNull { it.atMs >= startMs && it.atMs <= endMs }?.reason

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** 日志容量：覆盖条最多列 5 条断档，留足余量即可。 */
        const val CAPACITY = 64

        /** 轮询去重窗口：同一原因 10 分钟内重复观测只记一次。 */
        const val DEDUPE_MS = 10 * 60_000L

        /**
         * 全局共享实例：采集器写入、覆盖卡读取。
         *
         * 为什么放在 record 层用单例而不是走 DI 容器：写的一方是 [LocationCollector]（后台服务里
         * 也在跑），读的一方是设置页 Composable，两者之间没有现成的注入通道；而这份日志本来就
         * 是纯进程内的诊断数据，没有外部状态可污染。
         */
        val shared: CoverageFailureLog by lazy { CoverageFailureLog() }
    }
}

/** 一次失败观测：什么时候、因为什么。 */
data class CoverageFailure(val atMs: Long, val reason: LocationFailureReason)


// ===== 覆盖文案（纯函数，设置页与行程页共用一份） =====
//
// 为什么放在 record 层：同一句「断了 N 次」「47 分钟」在两处必须逐字一致，而且文案错了
// 用户会直接读错事实（把「没有采样」写成「断档 0 次」正是这种错），必须能被单测钉住。

/** 汇总行最多列几条断档：再多一行放不下，超出折成「等 N 处」。 */
const val COVERAGE_GAP_PREVIEW: Int = 3

/**
 * 时长文案：18 小时 32 分 / 47 分钟 / 不到 1 分钟。
 *
 * 与行程页 formatSpanText 措辞略有差别（这里分钟写「分钟」）：覆盖时长是统计值，
 * 行程页的是一段行程，别混用同一套字眼。
 */
fun coverageDurationText(ms: Long): String {
    if (ms <= 0L) return "0 分钟"
    val totalMin = ms / 60_000L
    if (totalMin <= 0L) return "不到 1 分钟"
    val hours = totalMin / 60L
    val minutes = totalMin % 60L
    return when {
        hours <= 0L -> minutes.toString() + " 分钟"
        minutes == 0L -> hours.toString() + " 小时"
        else -> hours.toString() + " 小时 " + minutes + " 分"
    }
}

/** 断档时长：不足 1 分钟按 1 分钟算（「断了 0 分钟」读起来像没断过）。 */
fun formatCoverageGapMinutes(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(1L)
    if (totalMin < 60L) return totalMin.toString() + " 分钟"
    val hours = totalMin / 60L
    val minutes = totalMin % 60L
    return if (minutes == 0L) hours.toString() + " 小时" else hours.toString() + " 小时 " + minutes + " 分"
}

/** 断档汇总主句：今天断了 3 次 / 这一天没有断档。 */
fun coverageGapHeadline(breakCount: Int, isToday: Boolean): String {
    val day = if (isToday) "今天" else "这一天"
    return if (breakCount <= 0) day + "没有断档" else day + "断了 " + breakCount + " 次"
}

/**
 * 断档汇总副标题：三段以内逐条列「03:12 起 35 分钟」（**最新的在前**，与断档列表同向），
 * 超过 3 条折成「等 N 处」。
 *
 * [totalBreaks] 是真实断档次数（窗口里的 GAP + DEEP 段数），不能用 [gaps] 长度代替：
 * 列表本身按上限截断，拿它计数会把 7 次断档写成 5 次。
 * 没有断档时返回空串（调用方据此整段不渲染，避免留一行空白）。
 *
 * @param clock 时间戳格式化（注入是为了让本文件不依赖 UI 层的 java.time 格式化器）
 */
fun coverageGapNote(
    gaps: List<CoverageGap>,
    totalBreaks: Int = gaps.size,
    clock: (Long) -> String,
): String {
    if (totalBreaks <= 0) return ""
    if (gaps.isEmpty()) return "共 " + totalBreaks + " 段断档"
    val shown = gaps.take(COVERAGE_GAP_PREVIEW).joinToString(" · ") { gap ->
        clock(gap.startMs) + " 起 " + formatCoverageGapMinutes(gap.durationMs)
    }
    return if (totalBreaks > COVERAGE_GAP_PREVIEW) shown + " 等 " + totalBreaks + " 处" else shown
}

/**
 * 采集覆盖卡副标题：过去 24 小时 · 断档 N 次（冻结文案，N 可为 0）。
 *
 * 空窗口同样走这一句（N=0）：卡内空态文案「过去 24 小时没有采样记录」负责说明
 * 「一条都没有」，副标题只负责格式统一 —— 两处各说一件事，不互相改写。
 */
fun coverageSubtitle(window: CoverageWindow): String =
    "过去 24 小时 · 断档 " + window.breakCount + " 次"
