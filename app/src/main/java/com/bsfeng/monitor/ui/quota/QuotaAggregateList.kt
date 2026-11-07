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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaAggregateRow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 聚合视图（原型 s20a）：结论条 → 排序说明 → 一家一行的榜单 → 尾部灰行 → 口径说明。
 *
 * 位次由 domain 聚合器给（1 = 最紧急），本文件只负责把它们画成原型那一套：
 * 行首 22dp 圆形位次徽标（第 1 名危险色淡底）、名称 + 剩余百分比 + 三档进度条、
 * 副文案并排多窗口。灰行（余额型）从 [QuotaAggregateSnapshot.excluded] 来，
 * 不占位次、不可点开。
 *
 * 状态判定全在 [aggregateStatusOf]，这里只按它的结果分别画：
 * 「没数据」有三种完全不同的原因（还在查 / 一家没配 / 配了全失败），
 * 一律画空态会让人误以为配置丢了，跑去重填本来正确的凭据。
 */
@Composable
fun QuotaAggregateBody(
    snapshot: QuotaAggregateSnapshot,
    onOpenProvider: (String) -> Unit,
    onConfigure: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(
        // 锚点 s18-agg：聚合态榜单区块；单家态整块不渲染
        modifier = modifier.fillMaxWidth().testTag("s18-agg").padding(horizontal = t.space4),
        verticalArrangement = Arrangement.spacedBy(t.space1),
    ) {
        when (snapshot.status) {
            // 首次查询中：骨架与真实榜单同形，数据回来时不跳高度
            QuotaAggregateStatus.Loading -> {
                QuotaLoading(Modifier.staggerEnterAt(0))
                return@Column
            }
            // 一家都没配：只有这一种情况的正确动作是「去配置」
            QuotaAggregateStatus.NotConfigured -> {
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
            // 凭据配对了、请求也通了，只是返回的额度算不出百分比：喊「去配置」同样指错方向
            QuotaAggregateStatus.NoRankable -> {
                EmptyState(
                    text = "已配置 ${snapshot.configuredCount} 家，但它们返回的额度都算不出剩余百分比（余额型不进聚合）。",
                    title = "没有可参与排序的额度",
                    icon = Icons.Outlined.Storage,
                    actionLabel = "查看 provider 设置",
                    onAction = onConfigure,
                    modifier = Modifier.staggerEnterAt(0),
                )
                return@Column
            }
            // 配了但全部失败：错在接口/凭据有效性，不是没配置，所以给原因 + 重试
            QuotaAggregateStatus.AllFailed -> {
                ErrorState(
                    text = snapshot.failureMessage.orEmpty(),
                    title = "查询失败",
                    onRetry = onRetry,
                    modifier = Modifier.staggerEnterAt(0),
                )
                return@Column
            }
            // 有数据：部分失败的家带 stale 照常上榜（行上标「旧数据」），不在这里另作状态
            QuotaAggregateStatus.Content -> Unit
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
            modifier = Modifier.padding(top = t.space2).testTag("s18-agg-spec"),
        )
    }
}

/** 榜单行的位次锚点：口径只给前 3 名各一条（顺序即位次，不按 provider 名钉）。 */
private fun Modifier.rankedRowTagOf(index: Int): Modifier = when (index) {
    0 -> testTag("s18-agg-row-1")
    1 -> testTag("s18-agg-row-2")
    2 -> testTag("s18-agg-row-3")
    else -> this
}

/** 灰行（不参与聚合）的锚点落在末行：锚点 row-4 就是那条「deepseek —」。 */
private fun Modifier.excludedRowTagOf(index: Int): Modifier =
    if (index == 0) testTag("s18-agg-row-4") else this

/**
 * 结论条：一句话回答「哪家最先见底」。
 *
 * 只取第 1 名：榜单已经把位次讲清楚了，结论条再列前三行就变成第二张榜单。
 * 内边距按原型 `.s20-hero{padding: space-2 space-3}` 走，比普通卡紧一档（少占一行高度）。
 */
@Composable
private fun AggregateHero(row: QuotaAggregateRow, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier.testTag("s18-agg-hero"), padding = 0.dp) {
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
    AppCard(modifier = modifier.testTag("s18-agg-list"), padding = 0.dp) {
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
                modifier = Modifier.staggerEnterAt(-1).then(Modifier.rankedRowTagOf(index)),
            )
        }
        excluded.forEachIndexed { index, item ->
            HorizontalDivider(
                thickness = 1.dp,
                color = t.borderSoft,
                modifier = Modifier.padding(horizontal = t.space4),
            )
            ExcludedRow(item, Modifier.excludedRowTagOf(index))
        }
    }
}
