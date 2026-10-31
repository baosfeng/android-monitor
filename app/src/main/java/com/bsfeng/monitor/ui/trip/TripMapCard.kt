package com.bsfeng.monitor.ui.trip

// 今日轨迹卡：把当天 LOCATION 采样画成一条折线。
//
// 两种画法，切换只影响「底图怎么来」，轨迹几何口径一致：
// - 默认（底图关）：纯本地自绘，画布自适应缩放，一个字节不出网（TripTrace.project）；
// - 底图开：叠免费瓦片（OpenStreetMap，免 key），轨迹走 Mercator 投影与瓦片严丝合缝（TripTrace.projectOnMap）。
//
// 纯投影逻辑在 TileMath.kt / TripTrace.kt；本文件只做画布布局与绘制。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.record.CoverageGap
import com.bsfeng.monitor.record.CoverageKind
import com.bsfeng.monitor.record.CoverageWindow
import com.bsfeng.monitor.record.coverageGapHeadline
import com.bsfeng.monitor.record.coverageGapNote
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlin.math.ceil
import kotlin.math.floor

/** 轨迹画布高度：180dp 与其它图表卡同高（布局语义尺寸，留字面量并注明用途）。 */
private val MAP_HEIGHT = 180.dp

/** 折线宽度：设计口径 1.5dp，比分割线略粗，长轨迹在浅色底上也看得清。 */
private val TRACE_STROKE = 1.5.dp

/**
 * 今日轨迹卡。
 *
 * @param timeline 当天行程（停留锚点与 dayStart 口径都从这里取）
 * @param samples 当天定位采样（已去重、升序，见 [TripTrace.samples]）
 * @param basemapEnabled 是否叠加底图（免费瓦片源，见 [TileSources]）
 * @param onToggleBasemap 切换底图；只在有采样时出现
 * @param coverage 当天窗口的采集覆盖（null = 不渲染覆盖条，例如数据还没到）
 * @param gaps 当天断档（升序、已截断），用于「今天断了 N 次」的副标题
 */
@Composable
internal fun TripMapCard(
    timeline: DayTimeline,
    samples: List<TraceSample>,
    modifier: Modifier = Modifier,
    isToday: Boolean = true,
    exporting: Boolean = false,
    onExport: (() -> Unit)? = null,
    basemapEnabled: Boolean = false,
    onToggleBasemap: () -> Unit = {},
    coverage: CoverageWindow? = null,
    gaps: List<CoverageGap> = emptyList(),
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
            if (samples.isNotEmpty()) {
                Spacer(Modifier.width(t.space2))
                BasemapChip(enabled = basemapEnabled, onClick = onToggleBasemap)
            }
            if (samples.isNotEmpty() && onExport != null) {
                Spacer(Modifier.width(t.space1))
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
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(MAP_HEIGHT)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
            ) {
                val density = LocalDensity.current
                val densityScale = density.density
                val widthDp = maxWidth.value
                val heightDp = maxHeight.value
                val paddingDp = t.space3.value
                val charPx = with(density) { labelStyle.fontSize.toPx() }
                val labelHeightPx = charPx * 1.4f

                val source = TileSources.DEFAULT
                val mapView = remember(samples, widthDp, heightDp, basemapEnabled) {
                    if (!basemapEnabled) {
                        null
                    } else {
                        TileMath.view(
                            samples = samples,
                            widthDp = widthDp.toDouble(),
                            heightDp = heightDp.toDouble(),
                            paddingDp = paddingDp.toDouble(),
                            maxZoom = source.maxZoom,
                        )
                    }
                }
                val tiles = rememberMapTiles(mapView, widthDp, heightDp, source)
                val projection = remember(samples, timeline.stays, widthDp, heightDp, mapView) {
                    val widthPx = widthDp * densityScale
                    val heightPx = heightDp * densityScale
                    if (mapView != null) {
                        TripTrace.projectOnMap(
                            samples = samples,
                            stays = timeline.stays,
                            view = mapView,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            density = densityScale,
                            charPx = charPx,
                            labelHeightPx = labelHeightPx,
                        )
                    } else {
                        TripTrace.project(
                            samples = samples,
                            stays = timeline.stays,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            paddingPx = paddingDp * densityScale,
                            charPx = charPx,
                            labelHeightPx = labelHeightPx,
                            density = densityScale,
                        )
                    }
                }

                Canvas(Modifier.fillMaxSize()) {
                    if (!projection.hasTrace) return@Canvas

                    // ⓪ 底图瓦片：相邻块取整到同一像素边界，避免 1px 细缝
                    tiles.forEach { tile ->
                        val left = tile.leftDp * densityScale
                        val top = tile.topDp * densityScale
                        val right = left + tile.sizeDp * densityScale
                        val bottom = top + tile.sizeDp * densityScale
                        val originX = floor(left).toInt()
                        val originY = floor(top).toInt()
                        drawImage(
                            image = tile.image,
                            dstOffset = IntOffset(originX, originY),
                            dstSize = IntSize(
                                ceil(right).toInt() - originX,
                                ceil(bottom).toInt() - originY,
                            ),
                        )
                    }

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
        }
        if (samples.isNotEmpty()) {
            Spacer(Modifier.height(t.space2))
            TraceLegend(basemapOn = basemapEnabled)
        }
        // 当日采集覆盖条（契约 trip-cov）：没有样本时不画 —— 空条会被读成「一整天都没采到」
        if (coverage != null && samples.isNotEmpty() && !coverage.isEmpty) {
            TripCoverageFooter(window = coverage, gaps = gaps, isToday = isToday)
        }
        Spacer(Modifier.height(t.space1))
        MetaText(traceNote(timeline, samples))
    }
}

/** 卡头「底图」：切换免费瓦片底图；开着时用强调色描出状态。 */
@Composable
private fun BasemapChip(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            // 点击区 ≥44dp（口径 §二）：视觉仍是胶囊，高度由最小点击区托底
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (enabled) t.accent.copy(alpha = 0.12f) else t.fgFade(6))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Map,
            contentDescription = null,
            tint = if (enabled) t.accent else t.muted,
            modifier = Modifier.size(t.space4),
        )
        Spacer(Modifier.width(t.space1))
        Text(
            text = if (enabled) "底图 开" else "底图",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) t.accent else t.muted,
        )
    }
}

/** 卡头右侧「导出」：把当天轨迹渲染成 PNG 存进系统相册。 */
@Composable
private fun TripExportChip(exporting: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction, enabled = !exporting)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(6))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = !exporting,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space2),
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

/** 图例：起点 / 终点 / 停留；右侧是底图来源的署名（开着底图时必须保留）。 */
@Composable
private fun TraceLegend(basemapOn: Boolean) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(t.success, "起点")
        LegendDot(t.danger, "终点")
        LegendDot(t.accent.copy(alpha = 0.45f), "停留")
        // 署名右对齐、单行省略：底图开着时这里是图源条款要求的署名，换行会把图例挤散
        Text(
            text = if (basemapOn) TileSources.DEFAULT.attribution else "自绘 · 无底图",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
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
// ===== 当日覆盖条（契约锚点 trip-cov）=====
//
// 轨迹卡底部的 14dp 细覆盖条 + 「今天断了 N 次」行：与设置页「采集覆盖」卡同一套分段算法
// （CoverageTimeline）与同一组配色占比，只把粗条压细、图例省掉 —— 行程页主角是轨迹，
// 覆盖条只做背景说明。只在当天有定位样本时渲染（无样本由本卡既有空态承担，不画空条）。

/** 细覆盖条高度：原型行程页 .cov-bar 的 14px。 */
private val TRIP_BAR_HEIGHT = 14.dp

/** 汇总行副标题最多列几条断档：再多一行放不下，超出折成「等 N 处」。 */
private const val TRIP_GAP_PREVIEW = 3

/**
 * 轨迹卡底部覆盖条 + 断档汇总行（契约 trip-cov）。
 *
 * @param window 当天窗口的覆盖分段
 * @param gaps 当天断档（已按时间升序、已截断）
 * @param isToday 今天写「今天断了 N 次」，历史日期写「这一天断了 N 次」
 */
@Composable
internal fun TripCoverageFooter(
    window: CoverageWindow,
    gaps: List<CoverageGap>,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = t.space3)
            .testTag("trip-cov"),
    ) {
        CoverageThinBar(window)
        Row(
            Modifier.fillMaxWidth().padding(top = t.space1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 刻度标真实时刻而不是写死 0:00 / 12:00 / 24:00：今天这一天的窗口截到「此刻」，
            // 右端写 24:00 而条其实只画到 14:20，刻度就指向了不存在的覆盖
            listOf(window.startMs, window.startMs + window.totalMs / 2, window.endMs).forEach { at ->
                Text(formatClock(at), style = monitorMonoMeta, color = t.muted)
            }
        }
        // 汇总行：主句说清「断了几次」，副标题列出具体是哪几段（超出 3 条折成等 N 处）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = t.space2),
        ) {
            Text(
                // 主句按真实断档次数（window.breakCount），不按 gaps 列表长度：
                // 列表最多只有 5 条，用它数次数会漏报
                text = coverageGapHeadline(window.breakCount, isToday),
                style = MaterialTheme.typography.bodySmall,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = coverageGapNote(gaps, window.breakCount, ::formatClock)
            if (note.isNotEmpty()) MetaText(note)
        }
    }
}

/** 细覆盖条：14dp、圆角 4dp、段间 1px 缝（与设置页粗条同一套颜色占比）。 */
@Composable
private fun CoverageThinBar(window: CoverageWindow) {
    val t = LocalDesignTokens.current
    val segments = window.segments
    val total = window.totalMs.coerceAtLeast(1L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TRIP_BAR_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(t.fgFade(6)),
    ) {
        if (segments.isEmpty()) return@Canvas
        val gapPx = 1.dp.toPx()
        var cursor = 0f
        segments.forEachIndexed { index, segment ->
            val width = size.width * (segment.durationMs.toFloat() / total)
            val left = cursor
            val right = if (index == segments.lastIndex) size.width else left + width - gapPx
            if (right > left) {
                drawRect(
                    color = tripSegmentColor(t, segment.kind),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
            }
            cursor = left + width
        }
    }
}

/** 段色：与设置页「采集覆盖」逐字同源，两处不得各写一套。 */
private fun tripSegmentColor(t: DesignTokens, kind: CoverageKind): Color = when (kind) {
    CoverageKind.COVERED -> t.success.copy(alpha = 0.62f)
    CoverageKind.GAP -> t.warn.copy(alpha = 0.58f)
    CoverageKind.DEEP -> t.danger.copy(alpha = 0.55f)
    CoverageKind.OFF -> t.fg.copy(alpha = 0.14f)
}