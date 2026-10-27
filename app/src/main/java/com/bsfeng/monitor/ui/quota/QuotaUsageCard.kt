package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
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
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/** Token 堆叠柱高度（原型 .s18-stack 76px，纯布局语义，不占间距 token）。 */
private val STACK_HEIGHT = 76.dp

/** 柱间缝隙（原型 gap 4px）。 */
private val STACK_GAP = 4.dp

/**
 * 用量卡的三套契约锚点：同一张卡在三个 provider 面板里各叫各的
 * （s18-*-usage / s18-*-range-* / s18-*-models-*）。
 *
 * 锚点值一律写成字面量 testTag —— 契约对账是 grep 源码的，拼字符串扫不到。
 */
enum class UsageCardVariant { OLLAMA_USD, OLLAMA_LEGACY, DEEPSEEK }

/** 卡片容器锚点。 */
private fun cardTagOf(variant: UsageCardVariant): Modifier = when (variant) {
    UsageCardVariant.OLLAMA_USD -> Modifier.testTag("s18-ollama-usage")
    UsageCardVariant.OLLAMA_LEGACY -> Modifier.testTag("s18-legacy-usage")
    UsageCardVariant.DEEPSEEK -> Modifier.testTag("s18-deepseek-usage")
}

/** 标题右侧范围文案锚点（只随范围切换改写，本身不可点）。 */
private fun rangeLabelTagOf(variant: UsageCardVariant): Modifier = when (variant) {
    UsageCardVariant.OLLAMA_USD -> Modifier.testTag("s18-usage-range-label")
    UsageCardVariant.OLLAMA_LEGACY -> Modifier.testTag("s18-legacy-range-label")
    UsageCardVariant.DEEPSEEK -> Modifier.testTag("s18-ds-range-label")
}

/** 三个范围项的锚点。 */
private fun rangeTagOf(variant: UsageCardVariant, range: QuotaRange): Modifier = when (variant) {
    UsageCardVariant.OLLAMA_USD -> when (range) {
        QuotaRange.LAST_24_HOURS -> Modifier.testTag("s18-range-24")
        QuotaRange.LAST_7_DAYS -> Modifier.testTag("s18-range-7")
        QuotaRange.LAST_30_DAYS -> Modifier.testTag("s18-range-30")
    }
    UsageCardVariant.OLLAMA_LEGACY -> when (range) {
        QuotaRange.LAST_24_HOURS -> Modifier.testTag("s18-legacy-range-24")
        QuotaRange.LAST_7_DAYS -> Modifier.testTag("s18-legacy-range-7")
        QuotaRange.LAST_30_DAYS -> Modifier.testTag("s18-legacy-range-30")
    }
    UsageCardVariant.DEEPSEEK -> when (range) {
        QuotaRange.LAST_24_HOURS -> Modifier.testTag("s18-ds-range-24")
        QuotaRange.LAST_7_DAYS -> Modifier.testTag("s18-ds-range-7")
        QuotaRange.LAST_30_DAYS -> Modifier.testTag("s18-ds-range-30")
    }
}

/** 按模型 Top 3 区块的锚点（旧版计划没有按模型明细）。 */
private fun modelsTagOf(variant: UsageCardVariant, range: QuotaRange): Modifier = when (variant) {
    UsageCardVariant.OLLAMA_USD -> when (range) {
        QuotaRange.LAST_24_HOURS -> Modifier.testTag("s18-models-24")
        QuotaRange.LAST_7_DAYS -> Modifier.testTag("s18-models-7")
        QuotaRange.LAST_30_DAYS -> Modifier.testTag("s18-models-30")
    }
    UsageCardVariant.DEEPSEEK -> when (range) {
        QuotaRange.LAST_24_HOURS -> Modifier.testTag("s18-ds-models-24")
        QuotaRange.LAST_7_DAYS -> Modifier.testTag("s18-ds-models-7")
        QuotaRange.LAST_30_DAYS -> Modifier.testTag("s18-ds-models-30")
    }
    UsageCardVariant.OLLAMA_LEGACY -> Modifier
}

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
    variant: UsageCardVariant,
    onRangeChange: (QuotaRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 卡头空白处点击不切范围：只有范围 chip 是热区
    AppCard(modifier = modifier.then(cardTagOf(variant))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用量", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.weight(1f))
            // 标题右侧的范围文案只随切换改写，本身不可点
            Text(
                "近 " + range.title,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                modifier = rangeLabelTagOf(variant),
            )
        }
        Spacer(Modifier.height(t.space2))
        // 范围切换压成一行小 chip：大分段要独占整行，一屏就放不下额度卡 + 用量卡
        QuotaRangeChips(variant = variant, selected = range, onSelect = onRangeChange)
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
            ModelRows(usage.groups, costUnit, variant, usage.range)
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
private fun ModelRows(
    groups: List<QuotaGroup>,
    unit: QuotaUnit,
    variant: UsageCardVariant,
    range: QuotaRange,
) {
    val t = LocalDesignTokens.current
    val rows = remember(groups) { QuotaFormat.topGroups(groups) }
    // 行不可点：按模型明细只是口径展示，不下钻
    Column(Modifier.fillMaxWidth().then(modelsTagOf(variant, range))) {
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
}

/** 花费列最小宽（原型 .s18-model-cost min-width 58px），让各行金额右缘对齐。 */
private val COST_COLUMN_WIDTH = 58.dp

/** 紧凑范围切换（24 小时 / 7 天 / 30 天）：选中项底 fg 10% + 文字转 fg（--dur-state）。 */
@Composable
private fun QuotaRangeChips(
    variant: UsageCardVariant,
    selected: QuotaRange,
    onSelect: (QuotaRange) -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(t.space1)) {
        QuotaRange.entries.forEach { range ->
            val on = range == selected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                targetValue = if (on) t.fgFade(10) else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "quotaRangeChipBg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .background(bg)
                    .pressFeedback(interaction)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                    ) {
                        // 已是该范围时不发请求、不重复震动
                        if (on) return@clickable
                        haptic(HapticTone.LIGHT)
                        onSelect(range)
                    }
                    .then(rangeTagOf(variant, range))
                    // 点击区 44dp：纵向内边距补到下限
                    .heightIn(min = t.tapMin)
                    .padding(horizontal = t.space2),
                contentAlignment = Alignment.Center,
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