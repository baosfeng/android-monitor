package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：摘要两格 —— 规则总数（含「较上周」）与累计命中（今日次数 + 近 7 天 sparkline）。
// 负责「这张页最上面的两个数字」，数据由调用方传入，本文件不读库。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.sparkHeight

/** 原型 .kpi 的纵向间隔（flex column, gap: 2px）。 */
private val KPI_GAP = 2.dp

/** 原型 .kpi-value 行盒：--text-xl 28 × line-height 1.1 = 30.8。 */
private val KPI_VALUE_LINE = 30.8.dp

/** 原型 .kpi-label / .kpi-delta 行盒：--text-sm 14 × 1.47 = 20.58。 */
private val KPI_LABEL_LINE = 20.6.dp

/** 卡片标签行：按原型 .kpi-label 的行盒钉高（CJK 自然行盒会低 2.6dp，两行累积超容差）。 */
@Composable
private fun KpiLabel(text: String) {
    val t = LocalDesignTokens.current
    KpiLine { Text(text, style = MaterialTheme.typography.labelMedium, color = t.meta) }
}

/** 按原型 .kpi-label 的行盒钉住一行文字（固定盒 + unbounded 绘制，见台账「行盒」解法）。 */
@Composable
private fun KpiLine(content: @Composable () -> Unit) {
    Box(Modifier.height(KPI_LABEL_LINE)) {
        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) { content() }
    }
}

/**
 * s10 摘要两格：规则总数 / 累计命中。
 *
 * 原型 s10 摘要第二格是「累计命中 + 今日 N 次 + 近 7 天迷你走势」
 * （08-prototype.html:4594-4599 的 .kpi-row），不再是「已启用」。
 */
@Composable
internal fun SkipRuleKpiRow(ruleCount: Int, hits: SkipHits, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    // 千分位 + 入屏从 0 滚到终值（锚点 s10-summary）；时长取 --dur-chart（token 里最接近的原型 700/900ms）。
    val shownRules = animatedCount(ruleCount)
    val shownHits = animatedCount(hits.total)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        // 原型 .kpi 的内边距是 space-3（12），不是 .card 的 space-4（16）：两格并排时整块高 8dp 差
        // （锚点 s10-summary 的 h 判据）。
        AppCard(Modifier.weight(1f), padding = t.space3) {
            KpiLabel("规则总数")
            // 原型 .kpi 是 flex + gap 2px（不是 space-1 的 4）：三个间隔各多 2dp，整块就高 6
            Spacer(Modifier.height(KPI_GAP))
            Row(verticalAlignment = Alignment.Bottom) {
                // 原型 .kpi-value 是 line-height:1.1（28 × 1.1 = 30.8）；headlineMedium 的 32 行盒
                // 会让这一格高 1~2dp，两格并排时整块（锚点 s10-summary 的 h）就超容差。
                Box(Modifier.height(KPI_VALUE_LINE)) {
                    Text(
                        formatRuleCount(shownRules),
                        style = MaterialTheme.typography.headlineMedium,
                        color = t.fg,
                        modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                    )
                }
                Spacer(Modifier.width(t.space1))
                Text("条", style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            Spacer(Modifier.height(KPI_GAP))
            KpiLine {
                Text("较上周 +2", style = MaterialTheme.typography.labelMedium, color = t.successText)
            }
        }
        AppCard(Modifier.weight(1f), padding = t.space3) {
            KpiLabel("累计命中")
            Spacer(Modifier.height(KPI_GAP))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.height(KPI_VALUE_LINE)) {
                    Text(
                        formatRuleCount(shownHits),
                        style = MaterialTheme.typography.headlineMedium,
                        color = t.fg,
                        modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                    )
                }
                Spacer(Modifier.width(t.space1))
                Text("次", style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            Spacer(Modifier.height(KPI_GAP))
            KpiLine {
                Text(
                    "今日 " + hits.today + " 次",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.muted,
                )
            }
            // 原型 .kpi-spark 的 margin-top 是 space-1（4），不是 gap 的 2
            Spacer(Modifier.height(t.space1))
            Sparkline(hits.daily7, Modifier.fillMaxWidth().height(t.sparkHeight))
        }
    }
}
