package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：摘要两格 —— 规则总数（含「较上周」）与累计命中（今日次数 + 近 7 天 sparkline）。
// 负责「这张页最上面的两个数字」，数据由调用方传入，本文件不读库。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.sparkHeight

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
        AppCard(Modifier.weight(1f)) {
            Text("规则总数", style = MaterialTheme.typography.labelSmall, color = t.meta)
            Spacer(Modifier.height(t.space1))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatRuleCount(shownRules),
                    style = MaterialTheme.typography.headlineMedium,
                    color = t.fg,
                )
                Spacer(Modifier.width(t.space1))
                Text("条", style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            Spacer(Modifier.height(t.space1))
            Text("较上周 +2", style = MaterialTheme.typography.labelMedium, color = t.successText)
        }
        AppCard(Modifier.weight(1f)) {
            Text("累计命中", style = MaterialTheme.typography.labelSmall, color = t.meta)
            Spacer(Modifier.height(t.space1))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatRuleCount(shownHits),
                    style = MaterialTheme.typography.headlineMedium,
                    color = t.fg,
                )
                Spacer(Modifier.width(t.space1))
                Text("次", style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            Spacer(Modifier.height(t.space1))
            Text(
                "今日 " + hits.today + " 次",
                style = MaterialTheme.typography.labelMedium,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space2))
            Sparkline(hits.daily7, Modifier.fillMaxWidth().height(t.sparkHeight))
        }
    }
}
