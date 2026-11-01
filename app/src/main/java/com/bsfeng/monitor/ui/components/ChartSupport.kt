package com.bsfeng.monitor.ui.components

// 从 ui/components/Charts.kt 拆出：图表家族共用的内部件 —— 数值归一化（min-max / 按最大值）、动画进度 chartAnim、网格虚线、数值直标几何。不含具体图表。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.rememberDrawCache
import com.bsfeng.monitor.ui.foundation.scaleDuration
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import kotlin.math.abs
import kotlin.math.round

/**
 * 图表基元（原 ui/components/Charts.kt 的文件级约定，随拆分保留）：
 * 全部自绘 Canvas，零第三方图表/动画库（交互口径 §6）；绘制时长走 --dur-chart / --dur-chart-fast，
 * 只动几何与 opacity。
 *
 * 热路径（J4）：动画值 `Animatable` 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；Path / dashPathEffect 这类
 * 每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。 *
 * 色彩三角色（交接文档 §2.1）：序列色 [DesignTokens.chartSeq] 用于柱状 / 折线 / 面积 /
 * sparkline（跟随主题强调色，同一图表内同色）；分类色板 [DesignTokens.chartRamp] 用于扇形
 * 图分片（跨主题固定）。两者不得混用。
 */

internal fun normalize(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    return values.map { (it - min) / span }
}

/**
 * 柱状图归一化：按**最大值**缩放（不是 min-max）。
 * 柱高必须与柱顶标注的数值同口径（原型 App.chart.bars：bh = (h-padB-padT) * val / maxOf(v)）。
 * min-max 拉伸是给折线 / 面积 / sparkline 用的（强调波动）；用在柱状图上会让最小值那根
 * 柱高为 0 却标着真实数值，自相矛盾。
 */
internal fun normalizeByMax(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.max().takeIf { it > 0f } ?: 1f
    return values.map { (it / max).coerceIn(0f, 1f) }
}

/**
 * 图表动画进度：返回 [Animatable] 而不是当帧 Float —— 调用点在 drawWithCache 的
 * build 块里读 progress.value，读取落在绘制作用域，动画期间不再逐帧重组（J4）。
 *
 * 时长按动效档位缩放（`ui/foundation/MotionTier.kt` 的 [MotionTier.scaleDuration]）：
 * FULL 全长、NORMAL ×0.75、REDUCED 由 [animated] = false 走「直接落终态」分支。
 */
@Composable
internal fun chartAnim(key: Any, animated: Boolean, durMs: Int): Animatable<Float, *> {
    val anim = remember(key) { Animatable(if (animated) 0f else 1f) }
    val tier = rememberMotionTier()
    val scaledDur = tier.scaleDuration(durMs)
    LaunchedEffect(key, animated, scaledDur) {
        if (animated) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(scaledDur))
        } else {
            anim.snapTo(1f)
        }
    }
    return anim
}

/** 网格虚线画笔：PathEffect 是常量对象，不再每帧新建（J4）。 */
private val GRID_DASH = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)

internal fun DrawScope.gridLines(color: Color, rows: Int) {
    for (i in 0..rows) {
        val y = size.height * i / rows
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = GRID_DASH)
    }
}

/* ---------- 图表数值直标（原型 .chart-value）的几何常量 ---------- */

/** 柱状图顶部留白：原型 bars 的 padT 由 14 加到 22，给柱顶数值让位。 */
internal val BAR_PAD_TOP = 22.dp

/** 面积图顶部留白：原型 area 的 padT 由 14 加到 24，给峰值标注让位。 */
internal val AREA_PAD_TOP = 24.dp

/** 面积 Path 在 [rememberDrawCache] 里的 key（同图例只持有一个实例）。 */
internal const val AREA_PATH_KEY = "area"

/** sparkline 折线 Path 的缓存 key（每帧重写同一实例，避免每帧 new Path）。 */
internal const val SPARK_PATH_KEY = "sparkline"

/** 柱顶数值距柱顶 6px（原型 bars：y = h - padB - bh - 6）。 */
internal val BAR_VALUE_OFFSET = 6.dp

/** 峰值标注距峰值点 9px（原型 area：y = Y(peak) - 9）。 */
internal val PEAK_LABEL_OFFSET = 9.dp

/** 扇形分片缝隙宽度 2px（原型 .chart-pie .pie-slice{stroke:var(--surface);stroke-width:2}）。 */
internal val PIE_SEAM = 2.dp

/**
 * 数值文本：整数去掉小数尾巴（原型把数值原样写进 SVG）。
 * 与 --font-mono / 11px / 600 对齐的样式见 [monitorMonoBadge]（Type.kt，避免屏内硬编码字号）。
 */
internal fun chartValueLabel(value: Float): String {
    val rounded = round(value)
    return if (abs(value - rounded) < 0.05f) {
        rounded.toLong().toString()
    } else {
        (round(value * 10f) / 10f).toString()
    }
}

/** 文本左上角：水平以 centerX 居中、垂直落在 topY，并夹在绘制区内避免溢出卡片。 */
internal fun TextLayoutResult.centeredAt(centerX: Float, topY: Float, width: Float) = Offset(
    (centerX - size.width / 2f).coerceIn(0f, (width - size.width).coerceAtLeast(0f)),
    topY.coerceAtLeast(0f),
)

/** 已测量的数值文本与其落点（[BarChart] 绘制期直接复用，不再测量）。 */
internal class MeasuredText(val layout: TextLayoutResult, val offset: Offset)
