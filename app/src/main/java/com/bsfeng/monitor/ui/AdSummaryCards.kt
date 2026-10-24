package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页顶部两张卡 —— 近 7 天累计统计（数字滚动 + 已确认 + 三个小统计 + 规则入口）
// 与近 7 天柱状趋势（含峰值说明）。以 LazyListScope 扩展提供，AdScreen 的 LazyColumn 一行接入。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.StatBox
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.util.Locale

/**
 * 列表顶部的两张卡：累计统计 + 近 7 天趋势。
 *
 * 参数都是 AdScreen 已经算好的派生值（平均耗时 / 规则命中数 / 累计节省），本扩展不重算。
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.adSummaryCards(
    totalCount: Int,
    confirmedCount: Int,
    dailyCounts7: List<Float>,
    labels: List<String>,
    avgCost: Long?,
    ruleHit: Int,
    savedTotal: Long?,
    toast: ToastHostState,
    onOpenRules: (() -> Unit)?,
) {
    item(key = "stat") {
        AdTotalCard(
            totalCount = totalCount,
            confirmedCount = confirmedCount,
            avgCost = avgCost,
            ruleHit = ruleHit,
            savedTotal = savedTotal,
            toast = toast,
            onOpenRules = onOpenRules,
        )
    }
    item(key = "trend") {
        AdTrendCard(dailyCounts7 = dailyCounts7, labels = labels)
    }
}

/** 近 7 天累计跳过：大数字 + 已确认次数 + 三个小统计 + 规则入口。 */
@Composable
private fun AdTotalCard(
    totalCount: Int,
    confirmedCount: Int,
    avgCost: Long?,
    ruleHit: Int,
    savedTotal: Long?,
    toast: ToastHostState,
    onOpenRules: (() -> Unit)?,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = Modifier.staggerEnterAt(0)) {
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
            onClick = {
                if (onOpenRules != null) onOpenRules() else toast.show("跳过规则入口：设置 → 跳过规则")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text("查看跳过规则", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 近 7 天跳过趋势：柱状图 + 轴标签 + 峰值说明。 */
@Composable
private fun AdTrendCard(dailyCounts7: List<Float>, labels: List<String>) {
    val t = LocalDesignTokens.current
    AppCard(modifier = Modifier.staggerEnterAt(1)) {
        CardHead("近 7 天跳过趋势", labels.first() + " – " + labels.last())
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
