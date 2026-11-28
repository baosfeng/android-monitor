package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 账号展开区（原型 .detail）：全部窗口行 + 范围分段 + 96dp 柱图。
 *
 * 范围是**按账号**存的（ProviderState.range 本来就是实例级），切范围只重拉这一家的用量；
 * 没有用量接口 / 没配用量凭据时只给一行说明，不放一张空图和一条点了没反应的分段。
 */
@Composable
internal fun QuotaAccountDetail(
    account: QuotaAccountUi,
    key: String,
    state: QuotaRepository.ProviderState,
    supportsUsage: Boolean,
    nowEpochSecond: Long,
    onRangeChange: (QuotaRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val now = nowEpochSecond
    Column(
        modifier
            .fillMaxWidth()
            .testTag(quotaAccountDetailTag(key))
            .background(t.surfaceWarm)
            // 原型 .detail{padding:0 12px 12px}
            .padding(start = t.space3, end = t.space3, bottom = t.space3),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = t.space2, bottom = t.space1),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                account.name + " 的窗口",
                style = quotaLabel13.copy(fontWeight = FontWeight.Bold),
                color = t.fg,
            )
            Text(state.items.size.toString() + " 个窗口", style = quotaTextSmall, color = t.muted)
        }
        state.items.forEach { item -> DetailWindowRow(item, now) }
        if (!supportsUsage) {
            Spacer(Modifier.height(t.space2))
            Text("该账号不提供用量查询", style = quotaTextSmall, color = t.muted)
            return@Column
        }
        if (!state.usageConfigured) {
            Spacer(Modifier.height(t.space2))
            Text("未配置用量凭据 · 去额度设置里补一条", style = quotaTextSmall, color = t.muted)
            return@Column
        }
        Spacer(Modifier.height(t.space2))
        // 分段与主屏的重置策略同一套控件（原型 .ranges）：三段、44dp 触达
        SegmentedControl(
            options = QuotaRange.entries.map { it.title },
            selectedIndex = QuotaRange.entries.indexOf(state.range).coerceAtLeast(0),
            onSelect = { index -> QuotaRange.entries.getOrNull(index)?.let(onRangeChange) },
        )
        Spacer(Modifier.height(t.space2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("请求数", style = quotaTextSmall, color = t.muted)
            Text("近 " + state.range.title, style = quotaTextSmall, color = t.muted)
        }
        Spacer(Modifier.height(2.dp))
        val usage = state.usage
        if (usage == null || usage.buckets.isEmpty()) {
            Text("暂无用量数据", style = quotaTextSmall, color = t.muted)
        } else {
            val values = usage.buckets.map { it.metrics.requestCount.toFloat() }
            val peak = values.maxOrNull() ?: 0f
            QuotaUsageChart(
                values = values,
                modifier = Modifier.padding(top = 2.dp),
                chartTag = quotaAccountChartTag(key),
                description = "近 " + usage.range.title + "请求数柱图，共 " + values.size +
                    " 根，峰值 " + peak.toInt(),
            )
        }
    }
}

/**
 * 一个窗口行（原型 .wrow：76px 窗口名 + 1fr 6dp 条 + 56px 数值）。
 *
 * 数值列 56dp 右对齐：与原型同宽，保证两个窗口的百分号上下对齐。
 */
@Composable
private fun DetailWindowRow(item: QuotaItem, nowEpochSecond: Long) {
    val t = LocalDesignTokens.current
    val fraction = item.remainingFraction
    val barColor = when (columnStateOf(fraction ?: 1f)) {
        QuotaColumnState.DEAD -> t.danger
        QuotaColumnState.LOW -> t.warn
        QuotaColumnState.NORMAL -> t.fg2
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(76.dp)) {
            FixedLine(14.9.dp, quotaLabel13, item.title, t.fg2)
            FixedLine(
                12.65.dp,
                quotaTextSmall,
                quotaResetText(item.resetsAtEpochSecond ?: item.period?.endEpochSecond, nowEpochSecond) ?: "无周期窗口",
                t.muted,
            )
        }
        Spacer(Modifier.width(t.space2))
        QuotaMeterTrack(
            fraction = fraction,
            color = barColor,
            height = 6.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            text = fraction?.let { (it * 100f).toInt().toString() + "%" } ?: "—",
            style = quotaMonoRow,
            color = t.fg,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}
