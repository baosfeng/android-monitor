package com.bsfeng.monitor.ui.trip

// 时间轴显示顺序：纯函数，无 Compose / 无域层依赖。
// 域层 DayTimeline.segments 恒按 start 升序（导出 TripExport、s01 概览卡也消费它，顺序不能动）；
// 这一层只决定「怎么摊开给用户看」——倒序，最新段在最上。

import com.bsfeng.monitor.domain.trip.DaySegment

/**
 * 锚点 9 段锚点表，按**时间序**（0 = 当天最早一段）保存，与原型块的段身份一致：
 * s16-seg-home-pm 就是「家 15:00–24:00」那一块。
 *
 * 锚点名的唯一真源；[timelineAnchorFor] 把视觉位次换算回时间序位次再查这张表。
 * 第 10 段起没有锚点：锚点集合严格等于账本定义，不新增自增号（一经发布不改号、不新增）。
 */
private val SEGMENT_ANCHOR_BY_TIME_INDEX: List<String> = listOf(
    "s16-seg-home-am",
    "s16-seg-move-1",
    "s16-seg-cafe",
    "s16-seg-move-2",
    "s16-seg-office",
    "s16-seg-move-3",
    "s16-seg-home-noon",
    "s16-seg-gap-1",
    "s16-seg-home-pm",
)

/**
 * 视觉位次 → 锚点名（null = 该行不打锚点）。
 *
 * 时间轴倒序渲染后，视觉第 [visualIndex] 行（0 = 最新段）对应时间序第 segmentCount - 1 - visualIndex 段，
 * 锚点跟随**段的身份**而不是行号：9 段时最上一行（最晚）拿 s16-seg-home-pm，最下一行（最早）拿 s16-seg-home-am。
 * 段数超过 9 时，时间序第 10 段起没有锚点 —— 那一段不论排到第几行都不打。
 *
 * @param visualIndex 倒序渲染后的视觉下标，0 = 最新段
 * @param segmentCount 当天段数
 */
internal fun timelineAnchorFor(visualIndex: Int, segmentCount: Int): String? {
    if (visualIndex < 0 || visualIndex >= segmentCount) return null
    return SEGMENT_ANCHOR_BY_TIME_INDEX.getOrNull(segmentCount - 1 - visualIndex)
}

/**
 * 时间轴显示顺序：按 start 倒序，**最新段在最上**。
 *
 * 输入按 start 升序（域层保证），输出按 start 降序；start 相同的段保持输入的相对顺序（稳定排序）。
 * 只影响显示，不改变域层 timeline.segments 本身，因此导出与概览卡的顺序不受影响。
 */
internal fun timelineRowsNewestFirst(segments: List<DaySegment>): List<DaySegment> =
    segments.sortedWith(compareByDescending { it.start })
