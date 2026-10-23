package com.bsfeng.monitor.ui.trip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 24 小时时间带（原型 s01 `.s01-band` / s16 同源配色）：停留 / 移动 / 未记录三色。
 *
 * 只画传进来的段，不补任何数据：段之外留下的底色就是「没有记录」，与 [Gap] 的斜纹区分开。
 * 配色全部由 token 合成（stay = chart-2 62% + surface，move = chart-1 60% + surface），
 * 本文件不含任何色值字面量。
 */
@Composable
fun TripBand(
    segments: List<DaySegment>,
    dayStart: Long,
    dayEnd: Long,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val stayColor = lerp(t.surface, t.chart2, STAY_TINT)
    val moveColor = lerp(t.surface, t.chart1, MOVE_TINT)
    val hatchColor = t.fgFade(22)
    val trackColor = t.fgFade(4)
    val hatchStep = with(LocalDensity.current) { t.space1.toPx() }
    val hatchStroke = with(LocalDensity.current) { 1.dp.toPx() }
    val shape = RoundedCornerShape(t.radiusSm)
    // 原型 .s01-band height:36px → 取 space-8(32) + space-1(4)，不引入新的裸尺寸
    val bandHeight = t.space8 + t.space1
    val span = (dayEnd - dayStart).coerceAtLeast(1L).toFloat()

    Box(
        modifier
            .fillMaxWidth()
            .height(bandHeight)
            .clip(shape)
            .background(trackColor)
            .border(1.dp, t.borderSoft, shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            segments.forEach { segment ->
                val left = ((segment.start - dayStart) / span).coerceIn(0f, 1f) * size.width
                val right = ((segment.end - dayStart) / span).coerceIn(0f, 1f) * size.width
                val width = (right - left).coerceAtLeast(0f)
                if (width <= 0f) return@forEach
                when (segment) {
                    is Stay -> drawRect(stayColor, Offset(left, 0f), Size(width, size.height))
                    is Move -> drawRect(moveColor, Offset(left, 0f), Size(width, size.height))
                    is Gap -> drawGapHatch(left, width, hatchColor, hatchStroke, hatchStep)
                }
            }
            drawRect(
                color = t.borderSoft,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

/** 未记录：135° 斜纹（与实色段区分，不暗示任何内容）。 */
private fun DrawScope.drawGapHatch(
    left: Float,
    width: Float,
    color: Color,
    strokePx: Float,
    stepPx: Float,
) {
    val step = stepPx.coerceAtLeast(1f)
    clipRect(left = left, top = 0f, right = left + width, bottom = size.height) {
        var x = left - size.height
        while (x < left + width + size.height) {
            drawLine(
                color = color,
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = strokePx,
            )
            x += step
        }
    }
}

/** 时间带下方刻度：00 / 06 / 12 / 18 / 24（原型 `.s01-band-axis`）。 */
@Composable
fun TripBandAxis(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BAND_AXIS_LABELS.forEach { label -> MetaText(label) }
    }
}

/** 时间带图例：停留 N 处 / 移动 N 段 / 未记录 X。 */
@Composable
fun TripBandLegend(timeline: DayTimeline, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val stayColor = lerp(t.surface, t.chart2, STAY_TINT)
    val moveColor = lerp(t.surface, t.chart1, MOVE_TINT)
    val gapColor = t.fgFade(22)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(stayColor, "停留 " + timeline.stays.size + " 处")
        LegendDot(moveColor, "移动 " + timeline.moves.size + " 段")
        LegendDot(gapColor, "未记录 " + formatSpanText(timeline.gaps.sumOf { it.durationMs }))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(t.space3)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(color),
        )
        Spacer(Modifier.width(t.space1))
        MetaText(label)
    }
}

private const val STAY_TINT = 0.62f
private const val MOVE_TINT = 0.60f
private val BAND_AXIS_LABELS = listOf("00", "06", "12", "18", "24")
