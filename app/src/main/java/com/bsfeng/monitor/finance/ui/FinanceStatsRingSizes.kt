package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的圆环与柱图尺寸常量；职责：统计页分类环与近 7 天柱图的几何取值为一处。

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统计页分类环直径：原型 `.s07-ringwrap` 实测 150×150（00-shell:2045）。
 * s04 / s06 的环是 104（FinanceRingGauge 的 FINANCE_RING_DIAMETER），三者不可共用。
 */
internal val STATS_RING_DIAMETER: Dp = 150.dp

/**
 * 统计页分类环的描边宽度。
 *
 * 原型 `#s07-ring` 的扇片是 `path.pie-slice`，路径 `M64 64 L64 1 A63 63 0 0 1 126.03 75.01 Z`
 * —— 起点落在圆心、内径 0，即**实心饼**，外径 147.7px（viewBox 128 渲染到 150px）。
 * 取 36dp（Lead 拍板）：环宽占半径 48%、孔径 150−72 = 78dp，中心文案约 53×44dp 的对角线
 * ≈75dp 仍在孔内，色块占比由 18dp 时的约 44% 提到约 75%，观感接近原型的厚环 + 中心白圆。
 */
internal val STATS_RING_STROKE: Dp = 36.dp

/** 环心「¥总额 / 总支出」的行间距（原型 .s07-ringctr 的 row-gap:2px）。 */
internal val STATS_RING_CENTER_GAP: Dp = 2.dp

/** 近 7 天柱图左侧留白：容纳 Y 轴刻度文字（原型 bars 的 padL = 30，00-shell:6453）。 */
internal val WEEK_AXIS_PAD_LEFT: Dp = 30.dp

/** 近 7 天柱图右侧留白（原型 bars 的 padR = 12）。 */
internal val WEEK_AXIS_PAD_RIGHT: Dp = 12.dp
