package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页的三块自绘内容 —— 近 7 天统计卡、趋势卡、单条记录详情面板。
// 只接收渲染所需的值与回调，不持有状态。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.StatBox
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

import java.util.Locale

/** 近 7 天累计跳过 + 平均耗时 / 规则命中 / 累计节省 三块指标。 */
@Composable
internal fun AdStatsCard(
    totalCount: Int,
    confirmedCount: Int,
    avgCost: Long?,
    ruleHit: Int,
    savedTotal: Long?,
    onOpenRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText("近 7 天累计跳过")
            MetaText("日均 " + String.format(Locale.US, "%.1f", totalCount / 7.0) + " 次")
        }
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(
                value = totalCount,
                style = MaterialTheme.typography.headlineMedium,
                color = t.fg,
            )
            Spacer(Modifier.width(t.space1))
            Text("次", style = MaterialTheme.typography.labelMedium, color = t.muted)
        }
        if (confirmedCount > 0) {
            Spacer(Modifier.height(t.space1))
            MetaText("其中已确认 " + confirmedCount + " 次")
        }
        Spacer(Modifier.height(t.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            StatBox(
                label = "平均耗时",
                value = if (avgCost != null) avgCost.toString() + " ms" else PLACEHOLDER,
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "规则命中",
                value = ruleHit.toString() + " / " + totalCount,
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "累计节省",
                value = if (savedTotal != null) formatSaved(savedTotal) else PLACEHOLDER,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(t.space3))
        Button(
            onClick = onOpenRules,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text("查看跳过规则", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 近 7 天跳过趋势卡（柱图 + 轴标签 + 峰值说明）。 */
@Composable
internal fun AdTrendCard(
    dailyCounts7: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        CardHead("近 7 天跳过趋势", labels.firstOrNull().orEmpty() + " – " + labels.lastOrNull().orEmpty())
        Spacer(Modifier.height(t.space3))
        BarChart(
            values = dailyCounts7,
            modifier = Modifier
                .fillMaxWidth()
                .height(t.chartH),
        )
        ChartAxisLabels(labels)
        Spacer(Modifier.height(t.space2))
        MetaText(peakNote(dailyCounts7))
    }
}

/** 单条跳过记录的详情面板内容（放在 BottomSheet 里，遮罩由 BottomSheet 承担）。 */
@Composable
internal fun AdRecordDetailContent(
    record: SkipRecord,
    onClose: () -> Unit,
    onCopySelector: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = record.displayName + " · 跳过成功",
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Badge(record.method.label + "匹配")
    }
    Spacer(Modifier.height(t.space3))
    DetailRow("发生时间", formatClockSec(record.timestampMs), first = true)
    DetailRow("命中规则", record.selector.ifBlank { PLACEHOLDER })
    DetailRow("响应耗时", record.costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
    DetailRow(
        "结果",
        when (record.confirmed) {
            true -> "已进入正片 · 未误触"
            false -> "点击后广告仍在（未确认）"
            null -> "未复核"
        },
    )
    DetailRow("包名", record.packageName.ifBlank { PLACEHOLDER })
    Spacer(Modifier.height(t.space4))
    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(t.radiusPill),
        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        Button(
            onClick = onCopySelector,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(t.radiusPill),
        ) { Text("复制选择器", style = MaterialTheme.typography.labelLarge) }
    }
}
