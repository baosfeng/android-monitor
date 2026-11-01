package com.bsfeng.monitor.domain.trip

/**
 * s16 每日行程 / s01 今日行程卡共用的值对象。
 *
 * 三条边界（改动前先读）：
 * 1. **不猜内容**：无法判定的区间只产出 [Gap]，绝不补编地点或活动；
 * 2. **无定位不编坐标**：DEVICE 基准下 [Place.lat] / [Place.lng] 恒为 null。
 */

/** 一天的毫秒数。domain 不依赖 app 侧的 ui/live，口径与之相同（固定 86400000）。 */
const val TRIP_DAY_MS: Long = 86_400_000L

/** 行程的判定基准。[GPS] = 有定位点；[DEVICE] = 无定位，按网络名 / App 使用簇推算。 */
enum class Basis { GPS, DEVICE }

/**
 * 地点。
 *
 * [id] 在同一份 [DayTimeline] 内稳定（"p0" / "p1"…），用于「同一地点」聚合；
 * [source] 记录这个地点是怎么来的（[Basis.GPS] 有坐标，[Basis.DEVICE] 只有名字）。
 */
data class Place(
    val id: String,
    val label: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val source: Basis = Basis.GPS,
)

/** 一段 App 使用（已裁剪到所在停留段的区间内，[ms] 为裁剪后的时长）。 */
data class UsageSlice(
    val app: String,
    val pkg: String?,
    val start: Long,
    val end: Long,
    val ms: Long,
)

/**
 * 一天中的一个行程段。三段之外没有第四种：判定不了就是 [Gap]。
 *
 * [start] / [end] 恒落在 `[dayStart, dayEnd]` 内（跨界的输入记录按窗口裁剪）。
 */
sealed interface DaySegment {
    val start: Long
    val end: Long

    /** 段时长（毫秒）；跨界裁剪后恒 >= 0。 */
    val durationMs: Long get() = (end - start).coerceAtLeast(0L)
}

/** 停留：同一地点连续 ≥10 分钟。 */
data class Stay(
    override val start: Long,
    override val end: Long,
    val place: Place,
    /** 期间全部 App 使用（按时长降序，已裁剪到本段区间；抽屉列前 8 条 + 其他汇总）。 */
    val topApps: List<UsageSlice> = emptyList(),
) : DaySegment

/** 移动：相邻停留之间净位移 >300m 且定位覆盖 ≥60%。 */
data class Move(
    override val start: Long,
    override val end: Long,
    val from: Place?,
    val to: Place?,
    /** 沿轨迹逐点 haversine 累加的距离（米）。 */
    val distanceMeters: Double,
) : DaySegment

/**
 * 未记录的成因。区分它才能回答「是没采集，还是采集了判不出」：
 * - [INTERRUPTED]：区间内**一条定位采样都没有**（采集中断 / 窗口外的空档）；
 * - [UNRESOLVED]：区间内**有定位采样**，但仍判不出停留或移动（漂移、断档、覆盖不足）。
 *
 * DEVICE 基准下没有定位采样，故该路径的 Gap 恒为 [INTERRUPTED]。
 */
enum class GapReason { INTERRUPTED, UNRESOLVED }

/** 未记录：采集有中断 / 无法判定。**不推断期间内容**。 */
data class Gap(
    override val start: Long,
    override val end: Long,
    /** 默认值必须保留：app 侧存在 Gap(start, end) 位置参数构造点。 */
    val reason: GapReason = GapReason.INTERRUPTED,
) : DaySegment

/** 当天行程时间轴。[coveragePct] = 已判定（停留 + 移动）时长占窗口的百分比，Gap 不计入。 */
data class DayTimeline(
    val dayStart: Long,
    val segments: List<DaySegment> = emptyList(),
    val basis: Basis = Basis.DEVICE,
    val coveragePct: Int = 0,
) {
    val isEmpty: Boolean get() = segments.isEmpty()
    val stays: List<Stay> get() = segments.filterIsInstance<Stay>()
    val moves: List<Move> get() = segments.filterIsInstance<Move>()
    val gaps: List<Gap> get() = segments.filterIsInstance<Gap>()

    /** 结论区用的聚合值（纯计算，不含文案）。 */
    val summary: DaySummary
        get() {
            val stays = stays
            val stayMs = stays.sumOf { it.durationMs }
            val moveMs = moves.sumOf { it.durationMs }
            val gapMs = gaps.sumOf { it.durationMs }
            val longest = stays.maxByOrNull { it.durationMs }
            val perPlace = LinkedHashMap<String, Pair<String, Long>>()
            stays.forEach { stay ->
                val prev = perPlace[stay.place.id]
                perPlace[stay.place.id] = (prev?.first ?: stay.place.label) to
                    ((prev?.second ?: 0L) + stay.durationMs)
            }
            val topPlace = perPlace.maxByOrNull { it.value.second }
            val topPlaceMs = topPlace?.value?.second ?: 0L
            return DaySummary(
                stayCount = stays.size,
                moveCount = moves.size,
                stayMs = stayMs,
                moveMs = moveMs,
                gapMs = gapMs,
                longestStayMs = longest?.durationMs ?: 0L,
                longestStayLabel = longest?.place?.label ?: "",
                topPlaceLabel = topPlace?.value?.first ?: "",
                topPlaceMs = topPlaceMs,
                // 在外 = 移动 + 非主停留处的停留
                awayMs = moveMs + (stayMs - topPlaceMs).coerceAtLeast(0L),
                distanceMeters = moves.sumOf { it.distanceMeters },
                coveragePct = coveragePct,
            )
        }
}

/**
 * 行程结论的数值部分。文案在 UI 层拼，domain 只给数。
 *
 * **互斥性**（改这里先看）：
 * - [topPlaceMs] 是「同一地点多段停留之和」——靠 [Place.id] 稳定才能成立，
 *   序号式 id 会把同一地点的第二段算成别的地点；
 * - [awayMs] = 移动 + 非主地点停留；
 * - 恒等式：`awayMs + topPlaceMs == activeMs`（= 停留 + 移动 = 已判定时长），
 *   且 `stayMs + moveMs + gapMs == 时间轴窗口长度`（三段互斥、无重复计入）。
 */
data class DaySummary(
    val stayCount: Int,
    val moveCount: Int,
    val stayMs: Long,
    val moveMs: Long,
    val gapMs: Long,
    val longestStayMs: Long,
    val longestStayLabel: String,
    val topPlaceLabel: String,
    val topPlaceMs: Long,
    val awayMs: Long,
    val distanceMeters: Double,
    val coveragePct: Int,
) {
    /** 已判定时长 = 停留 + 移动（Gap 之外）。 */
    val activeMs: Long get() = stayMs + moveMs
}
