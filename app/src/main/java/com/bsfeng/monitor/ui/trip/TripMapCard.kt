package com.bsfeng.monitor.ui.trip

// 今日轨迹卡：把当天 LOCATION 采样画成一条折线（自绘 Canvas，无底图、无地图 SDK、无第三方）。
// 纯投影逻辑在 TripTrace.kt；本文件只做画布布局与绘制，所有几何与文字都在 drawScope 里落笔。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 轨迹画布高度：180dp 与其它图表卡同高（布局语义尺寸，留字面量并注明用途）。 */
private val MAP_HEIGHT = 180.dp

/** 折线宽度：设计口径 1.5dp，比分割线略粗，长轨迹在浅色底上也能看清。 */
private val TRACE_STROKE = 1.5.dp

/**
 * 今日轨迹卡。
 *
 * @param timeline 当天行程（停留锚点与 dayStart 口径都从这里取）
 * @param samples 当天定位采样（已去重、升序，见 [TripTrace.samples]）
 */
@Composable
internal fun TripMapCard(
    timeline: DayTimeline,
    samples: List<TraceSample>,
    modifier: Modifier = Modifier,
    isToday: Boolean = true,
    exporting: Boolean = false,
    onExport: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = t.fg2)

    AppCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isToday) "今日轨迹" else "当日轨迹",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
            )
            Spacer(Modifier.width(t.space2))
            MetaText(traceMeta(timeline, samples), Modifier.weight(1f))
            if (samples.isNotEmpty() && onExport != null) {
                Spacer(Modifier.width(t.space2))
                TripExportChip(exporting = exporting, onClick = onExport)
            }
        }
        Spacer(Modifier.height(t.space3))
        if (samples.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MAP_HEIGHT)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm)
                    .padding(t.space4),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isToday) {
                        "今天还没有定位样本，开启位置采集后才有轨迹"
                    } else {
                        "这一天没有定位样本，开启位置采集后才有轨迹"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val density = LocalDensity.current
            val paddingPx = with(density) { t.space3.toPx() }
            val charPx = with(density) { labelStyle.fontSize.toPx() }
            val labelHeightPx = with(density) { labelStyle.fontSize.toPx() } * 1.4f
            val densityScale = density.density
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(MAP_HEIGHT)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
            ) {
                val projection = TripTrace.project(
                    samples = samples,
                    stays = timeline.stays,
                    widthPx = size.width,
                    heightPx = size.height,
                    paddingPx = paddingPx,
                    charPx = charPx,
                    labelHeightPx = labelHeightPx,
                    density = densityScale,
                )
                if (!projection.hasTrace) return@Canvas

                // ① 停留圆垫在折线下面：先画范围，再让轨迹压上去（停留是「区域」，轨迹是「经过」）
                projection.stays.forEach { stay ->
                    val radius = stay.radiusDp.dp.toPx()
                    drawCircle(color = t.accent.copy(alpha = 0.16f), radius = radius, center = Offset(stay.x, stay.y))
                    drawCircle(
                        color = t.accent.copy(alpha = 0.45f),
                        radius = radius,
                        center = Offset(stay.x, stay.y),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                // ② 轨迹折线（单点时不画线）
                if (projection.points.size > 1) {
                    val path = Path()
                    projection.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(
                        path = path,
                        color = t.accent,
                        style = Stroke(width = TRACE_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // ③ 起终点：白色描边圈 + 语义色实心点（起点绿、终点红）
                val start = projection.points.first()
                val end = projection.points.last()
                drawEndpoint(start.x, start.y, t.success, t.surface)
                if (projection.points.size > 1) {
                    drawEndpoint(end.x, end.y, t.danger, t.surface)
                }

                // ④ 停留标签：纯逻辑已做重叠剔除（最多 6 个），这里只量文本
                projection.stays.forEach { stay ->
                    if (!stay.labelVisible) return@forEach
                    val layout = measurer.measure(text = stay.label, style = labelStyle)
                    val radius = stay.radiusDp.dp.toPx()
                    var left = stay.x - layout.size.width / 2f
                    left = left.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                    val top = (stay.y - radius - layout.size.height).coerceAtLeast(0f)
                    drawText(layout, topLeft = Offset(left, top))
                }
            }
        }
        if (samples.isNotEmpty()) {
            Spacer(Modifier.height(t.space2))
            TraceLegend()
        }
        Spacer(Modifier.height(t.space1))
        MetaText(traceNote(timeline, samples))
    }
}

/** 卡头右侧「导出」：把当天轨迹渲染成 PNG 存进系统相册。 */
@Composable
private fun TripExportChip(exporting: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(6))
            .clickable(enabled = !exporting) { onClick() }
            .padding(horizontal = t.space2, vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.FileDownload,
            contentDescription = null,
            tint = if (exporting) t.muted else t.accent,
            modifier = Modifier.size(t.space4),
        )
        Spacer(Modifier.width(t.space1))
        Text(
            text = if (exporting) "导出中…" else "导出",
            style = MaterialTheme.typography.labelSmall,
            color = if (exporting) t.muted else t.accent,
        )
    }
}

/** 起终点圆点：外圈用页面底色隔开浅色底，内圈语义色。 */
private fun DrawScope.drawEndpoint(x: Float, y: Float, color: Color, ring: Color) {
    drawCircle(color = ring, radius = 4.5.dp.toPx(), center = Offset(x, y))
    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(x, y))
}

/** 图例：起点 / 终点 / 停留。 */
@Composable
private fun TraceLegend() {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(t.success, "起点")
        LegendDot(t.danger, "终点")
        LegendDot(t.accent.copy(alpha = 0.45f), "停留")
        Spacer(Modifier.weight(1f))
        MetaText("自绘 · 无底图")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(t.space1).clip(CircleShape).background(color))
        Spacer(Modifier.size(t.space1))
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.muted)
    }
}

/** 卡头右侧：样本数与覆盖时间段。 */
private fun traceMeta(timeline: DayTimeline, samples: List<TraceSample>): String {
    if (samples.isEmpty()) return "暂无定位样本"
    val start = formatSpanClock(samples.first().timestamp, timeline.dayStart)
    val end = formatSpanClock(samples.last().timestamp, timeline.dayStart)
    return "样本 " + samples.size + " · " + start + "–" + end
}

/** 卡尾说明：没有坐标的停留不画圆，口径说清楚。 */
private fun traceNote(timeline: DayTimeline, samples: List<TraceSample>): String {
    if (samples.isEmpty()) return "轨迹只来自定位记录，不推断、不补点。"
    val located = timeline.stays.count { it.place.lat != null && it.place.lng != null }
    return if (located == 0) {
        "停留点无坐标，只画轨迹走向；圆点大小按停留时长。"
    } else {
        "停留圆按停留时长放大，名字超出画布或重叠时省略。"
    }
}
