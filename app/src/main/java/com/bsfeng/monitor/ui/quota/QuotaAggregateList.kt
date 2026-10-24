package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaAggregateRow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 聚合视图（原型 s20a）：结论条 → 排序说明 → 一家一行的榜单 → 尾部灰行 → 口径说明。
 *
 * 位次由 domain 聚合器给（1 = 最紧急），本文件只负责把它们画成原型那一套：
 * 行首 22dp 圆形位次徽标（第 1 名危险色淡底）、名称 + 剩余百分比 + 三档进度条、
 * 副文案并排多窗口。灰行（余额型）从 [QuotaAggregateSnapshot.excluded] 来，
 * 不占位次、不可点开。
 */
@Composable
fun QuotaAggregateBody(
    snapshot: QuotaAggregateSnapshot,
    onOpenProvider: (String) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = t.space4),
        verticalArrangement = Arrangement.spacedBy(t.space1),
    ) {
        if (!snapshot.hasRanked) {
            EmptyState(
                text = "只有能算出剩余百分比的 provider 参与排序，余额型不进聚合。",
                title = "还没有可聚合的额度",
                icon = Icons.Outlined.Storage,
                actionLabel = "去配置 provider",
                onAction = onConfigure,
                modifier = Modifier.staggerEnterAt(0),
            )
            return@Column
        }
        snapshot.leader?.let { leader ->
            AggregateHero(leader, Modifier.staggerEnterAt(0))
        }
        AggregateSortBar(Modifier.staggerEnterAt(1))
        AggregateRankList(
            rows = snapshot.rows,
            excluded = snapshot.excluded,
            onOpenProvider = onOpenProvider,
            modifier = Modifier.staggerEnterAt(2),
        )
        Text(
            "余额型不进聚合 · 失败的家保留旧数据、照常排序 · 只读本地缓存",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.padding(top = t.space2),
        )
    }
}

/**
 * 结论条：一句话回答「哪家最先见底」。
 *
 * 只取第 1 名：榜单已经把位次讲清楚了，结论条再列前三行就变成第二张榜单。
 * 内边距按原型 `.s20-hero{padding: space-2 space-3}` 走，比普通卡紧一档（少占一行高度）。
 */
@Composable
private fun AggregateHero(row: QuotaAggregateRow, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier, padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = t.space3, vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 30dp 是原型 .s20-hero .row-icon 的固定尺寸（比通用 36dp 矮一号），属于布局语义尺寸
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.danger.copy(alpha = t.danger.alpha * 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = t.dangerText,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(t.space2))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("最先见底", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.width(t.space1))
                Text(
                    leaderTitle(row),
                    style = MaterialTheme.typography.titleMedium,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(t.space2))
            Text(
                percentOfFraction(row.primaryFraction),
                style = percentStyle(t),
                color = t.dangerText,
            )
        }
    }
}

/** 排序说明行：左「按剩余由低到高」右「行首为位次」，两处都是 14sp muted（原型 group-label / s20-note）。 */
@Composable
private fun AggregateSortBar(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = t.space4, bottom = t.space2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("按剩余由低到高", style = MaterialTheme.typography.bodySmall, color = t.muted)
        Text("行首为位次", style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/** 榜单主体：一张 group 卡 + 行间 1px 细分隔线（与设置页 .group/.row 同一套节奏）。 */
@Composable
private fun AggregateRankList(
    rows: List<QuotaAggregateRow>,
    excluded: List<QuotaExcludedProvider>,
    onOpenProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 内边距留 0：原型 .s20-list 的上下 padding 为 0，行自己撑 padding；
    // 上下再各加 16 会让首末行被顶开，一屏就装不下了
    AppCard(modifier = modifier, padding = 0.dp) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = t.borderSoft,
                    modifier = Modifier.padding(horizontal = t.space4),
                )
            }
            AggregateRankRow(
                row = row,
                onClick = { onOpenProvider(row.providerId) },
                // 行内不再各自播入场：外层整块已经按 stagger 步骤一次起，逐行再 delay 会拖到 600ms+
                modifier = Modifier.staggerEnterAt(-1),
            )
        }
        excluded.forEach { item ->
            HorizontalDivider(
                thickness = 1.dp,
                color = t.borderSoft,
                modifier = Modifier.padding(horizontal = t.space4),
            )
            ExcludedRow(item)
        }
    }
}
