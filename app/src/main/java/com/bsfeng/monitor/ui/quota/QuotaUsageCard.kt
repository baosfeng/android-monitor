package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.fgFade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.ChartSlot
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/** Token 堆叠柱高度（原型 .s18-stack 76px，纯布局语义，不占间距 token）。 */
private val STACK_HEIGHT = 76.dp

/** 柱间缝隙（原型 gap 4px）。 */
private val STACK_GAP = 4.dp

/**
 * 用量卡：范围分段 → 指标行 → 请求次数面积图 → Token 堆叠柱 → 按模型 Top 3。
 *
 * 图表配色只用序列色（--chart-seq）：请求数曲线与 Token 输入同为强调色，
 * 输出用同色 40% 透明 —— 这层关系本身表达「两者相加等于总量」，不用再引入第二色。
 */
@Composable
fun UsageCard(
    usage: QuotaUsage,
    range: QuotaRange,
    costUnit: QuotaUnit,
    onRangeChange: (QuotaRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        // 范围切换压成标题右侧的小 chip：大分段要独占一行，一屏就放不下额度卡 + 用量卡
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用量", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.weight(1f))
            QuotaRangeChips(selected = range, onSelect = onRangeChange)
        }
        Spacer(Modifier.height(t.space2))
        val totals = usage.totals
        MetricRow(
            label = "请求 · 花费",
            value = buildString {
                append(QuotaFormat.count(totals.requestCount))
                totals.cost?.let { append(" · " + QuotaFormat.cost(it, costUnit)) }
            },
        )
        if (totals.inputTokens != null || totals.cachedInputTokens != null || totals.outputTokens != null) {
            Spacer(Modifier.height(t.space2))
            MetricRow(
                label = "输入 / 缓存 / 输出",
                value = listOf(
                    QuotaFormat.tokens(totals.inputTokens),
                    QuotaFormat.tokens(totals.cachedInputTokens),
                    QuotaFormat.tokens(totals.outputTokens),
                ).joinToString(" · "),
            )
        }
        val requestValues = usage.buckets.map { it.metrics.requestCount.toFloat() }
        if (requestValues.any { it > 0f }) {
            Spacer(Modifier.height(t.space3))
            ChartSlot(height = t.chartH) {
                AreaChart(values = requestValues, modifier = Modifier.fillMaxSize(), markPeak = false)
            }
            ChartAxisLabels(
                labels = QuotaFormat.sampleLabels(
                    usage.buckets.map { QuotaFormat.bucketLabel(it, usage.granularity) },
                ),
            )
        }
        val tokenBars = usage.buckets.any {
            (it.metrics.inputTokens ?: 0L) + (it.metrics.outputTokens ?: 0L) > 0L
        }
        if (tokenBars) {
            Spacer(Modifier.height(t.space3))
            TokenStackSection(usage)
        }
        if (usage.groups.isNotEmpty()) {
            Spacer(Modifier.height(t.space3))
            ModelRows(usage.groups, costUnit)
        }
    }
}

/** Token 堆叠柱区：标题 + 柱图 + 图例。 */
@Composable
private fun TokenStackSection(usage: QuotaUsage) {
    val t = LocalDesignTokens.current
    val input = usage.totals.inputTokens ?: 0L
    val output = usage.totals.outputTokens ?: 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Tokens · 近 ${usage.range.title}",
            style = MaterialTheme.typography.bodySmall,
            color = t.fg2,
            modifier = Modifier.weight(1f),
        )
        Text(QuotaFormat.tokens(input + output), style = monitorMonoMeta, color = t.fg)
    }
    Spacer(Modifier.height(t.space2))
    TokenStackChart(usage.buckets)
    Spacer(Modifier.height(t.space2))
    Row(horizontalArrangement = Arrangement.spacedBy(t.space4)) {
        LegendDot(t.chartSeq, "输入 " + QuotaFormat.tokens(usage.totals.inputTokens))
        LegendDot(t.chartSeq.copy(alpha = t.chartSeq.alpha * 0.4f), "输出 " + QuotaFormat.tokens(usage.totals.outputTokens))
    }
}

/** 自绘 Token 堆叠柱：输入在上（实色）、输出在下（同色 40%）。 */
@Composable
private fun TokenStackChart(buckets: List<QuotaBucket>) {
    val t = LocalDesignTokens.current
    val motion = rememberMotionEnabled()
    val bars = remember(buckets) {
        QuotaFormat.stackedBars(
            inputs = buckets.map { it.metrics.inputTokens },
            outputs = buckets.map { it.metrics.outputTokens },
        )
    }
    val inputColor = t.chartSeq
    val outputColor = t.chartSeq.copy(alpha = t.chartSeq.alpha * 0.4f)
    val anim = remember(bars) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(bars, motion) {
        if (motion) anim.animateTo(1f, tween(t.durChart, easing = t.easeOut)) else anim.snapTo(1f)
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(STACK_HEIGHT)
            // 只在绘制期读 anim.value：动画期间不触发重组，滚动时也不掉帧
            .drawBehind {
                if (bars.isEmpty()) return@drawBehind
                val gap = STACK_GAP.toPx()
                val barWidth = (size.width - gap * (bars.size - 1)) / bars.size
                val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                val progress = anim.value
                bars.forEachIndexed { index, bar ->
                    val totalHeight = size.height * bar.heightFraction * progress
                    if (totalHeight <= 0f) return@forEachIndexed
                    val inputHeight = totalHeight * bar.inputFraction
                    val left = index * (barWidth + gap)
                    drawRoundRect(
                        color = inputColor,
                        topLeft = Offset(left, size.height - totalHeight),
                        size = Size(barWidth, inputHeight),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        color = outputColor,
                        topLeft = Offset(left, size.height - totalHeight + inputHeight),
                        size = Size(barWidth, totalHeight - inputHeight),
                    )
                }
            },
    )
}

/** 按模型 Top 3 + 「其他」聚合行。 */
@Composable
private fun ModelRows(groups: List<QuotaGroup>, unit: QuotaUnit) {
    val t = LocalDesignTokens.current
    val rows = remember(groups) { QuotaFormat.topGroups(groups) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("按模型", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
        Badge("Top 3", tone = BadgeTone.Neutral)
    }
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.name,
                style = monitorMonoMeta,
                color = if (row.isOther) t.muted else t.fg,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                QuotaFormat.count(row.metrics.requestCount),
                style = monitorMonoMeta,
                color = t.muted,
            )
            row.metrics.cost?.let { cost ->
                Spacer(Modifier.width(t.space3))
                Text(
                    QuotaFormat.cost(cost, unit),
                    style = monitorMonoMeta,
                    color = t.fg,
                    modifier = Modifier.width(COST_COLUMN_WIDTH),
                )
            }
        }
    }
}

/** 花费列最小宽（原型 .s18-model-cost min-width 58px），让各行金额右缘对齐。 */
private val COST_COLUMN_WIDTH = 58.dp

/** 紧凑范围切换（24 小时 / 7 天 / 30 天），放在「用量」标题右侧。 */
@Composable
private fun QuotaRangeChips(selected: QuotaRange, onSelect: (QuotaRange) -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(t.space1)) {
        QuotaRange.entries.forEach { range ->
            val on = range == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .background(if (on) t.fgFade(10) else Color.Transparent)
                    .clickable {
                        haptic(HapticTone.LIGHT)
                        onSelect(range)
                    }
                    .padding(horizontal = t.space2, vertical = t.space1),
            ) {
                Text(
                    range.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) t.fg else t.muted,
                )
            }
        }
    }
}
