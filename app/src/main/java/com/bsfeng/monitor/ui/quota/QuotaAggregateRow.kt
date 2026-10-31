package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaAggregateRow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/**
 * 聚合榜单的行内原子：位次徽标、榜单行、灰行、三档进度条。
 *
 * 与 `QuotaAggregateList.kt`（容器：结论条 / 排序说明 / 空态）分开，是为了两个文件
 * 都留在 300 行以内；行内原子也正好是这个页面最需要逐帧对齐原型的那部分。
 */

/** 位次徽标直径（原型 .s20-rank 22px）。 */
private val RANK_SIZE = 22.dp

/** 进度条高度（原型 .s20-bar 6px）。 */
private val BAR_H = 6.dp

/** 行内纵向间距（原型 `.s20-main{gap:6px}`）：名称行 / 进度条 / 副文案三段之间。 */
private val MAIN_GAP = 6.dp

/** 名称与 tag 之间（原型 `.s20-name{gap:6px}`）。 */
private val TAG_GAP = 6.dp

/**
 * 行内小标签（原型 `.s20-tag`：`旧数据` / `不参与聚合`）。
 *
 * 不复用公共 [Badge]：那是 11sp 等宽角标口径（配色、类型标签等「次要元信息」），
 * 而原型这里的标签是 14px/600 的行内状态词，视觉上要和 provider 名同级，
 * 用 11sp 会读成「可有可无的角标」，恰好抹掉「这是旧数据」这层最重要的提醒。
 */
@Composable
private fun AggregateTag(text: String, warn: Boolean) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (warn) t.warn.copy(alpha = t.warn.alpha * 0.16f) else t.fgFade(8))
            .padding(horizontal = TAG_PAD_X),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (warn) t.warnText else t.muted,
        )
    }
}

/** tag 左右内边距（原型 `.s20-tag{padding:0 6px}`）。 */
private val TAG_PAD_X = 6.dp

/** 榜单一行：22dp 位次徽标 + 名称/百分比 + 进度条 + 多窗口副文案 + chevron。 */
@Composable
internal fun AggregateRankRow(
    row: QuotaAggregateRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space4, vertical = t.space2),
    ) {
        Row(
            modifier = Modifier.testTag("row.agg"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RankBadge(rank = row.rank, top = row.rank == 1)
            Spacer(Modifier.width(t.space3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MAIN_GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = t.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // stale 只在「失败但有旧数据」时置位：数据照常排序，但必须让人看出这是旧读数
                    if (row.stale) {
                        Spacer(Modifier.width(TAG_GAP))
                        AggregateTag("旧数据", warn = true)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        percentOfFraction(row.primaryFraction),
                        style = percentStyle(t),
                        color = percentColor(t, row.primaryFraction),
                    )
                }
                QuotaAggregateBar(fraction = row.primaryFraction, stale = row.stale)
                Text(
                    aggregateSubText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(t.space2))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = t.muted.copy(alpha = t.muted.alpha * 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 末尾灰行：不参与聚合的家。
 *
 * 用虚线空心圈 + `—` 占位次位、名称降到 muted：原型刻意让它读起来「在这一屏里，
 * 但不在榜上」。不加 chevron，因为它没有可下钻的单家详情。
 */
@Composable
internal fun ExcludedRow(item: QuotaExcludedProvider, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(rank = 0, top = false)
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MAIN_GAP)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(TAG_GAP))
                AggregateTag("不参与聚合", warn = false)
                Spacer(Modifier.weight(1f))
                Text("—", style = percentStyle(t), color = t.muted)
            }
            Text(item.note, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}

/**
 * 位次徽标：22dp 圆、等宽数字。
 *
 * [rank] = 0 表示不参与排序（灰行）：改画虚线空心圈 + `—`，
 * 用同一位宽保证灰行与榜单行的名称列左对齐。
 */
@Composable
private fun RankBadge(rank: Int, top: Boolean) {
    val t = LocalDesignTokens.current
    val off = rank <= 0
    Box(
        modifier = Modifier
            .size(RANK_SIZE)
            .then(
                if (off) {
                    // 虚线圆圈用 drawBehind 画：Compose 的 border 只支持实线，虚线要自己下笔
                    Modifier.drawBehind {
                        drawCircle(
                            color = t.border,
                            radius = size.minDimension / 2f,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                            ),
                        )
                    }
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (top) {
                                t.danger.copy(alpha = t.danger.alpha * 0.16f)
                            } else {
                                t.fgFade(8)
                            },
                        )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (off) "—" else rank.toString(),
            style = monitorMonoMeta.copy(fontWeight = FontWeight.Bold),
            color = when {
                off -> t.muted
                top -> t.dangerText
                else -> t.fg2
            },
        )
    }
}

/**
 * 三档进度条：≥50% 序列色 / 40–49% 警告 / <40% 危险（原型 s20-bar-fill 的三档）。
 *
 * 与 `QuotaMeter` 的阈值不同（那边是 20 / 50）：聚合榜单关心的是「快见底」，
 * 40% 就该变色提醒；沿用 20% 的线会让 30% 的家看起来仍然安全。
 */
@Composable
private fun QuotaAggregateBar(fraction: Float, stale: Boolean) {
    val t = LocalDesignTokens.current
    val color = if (stale) {
        // 旧数据用降饱和的琥珀（原型 .s20-bar-fill.is-stale）：一眼看出不是刚查到的读数
        t.warn.copy(alpha = t.warn.alpha * 0.55f)
    } else {
        when {
            fraction < 0.40f -> t.danger
            fraction < 0.50f -> t.warn
            else -> t.chartSeq
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(BAR_H)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(10)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(t.radiusPill))
                .background(color),
        )
    }
}

/** 剩余百分比的三档文字色，与进度条同档（原型 .s20-pct .is-low / .is-warn）。 */
@Composable
internal fun percentColor(t: DesignTokens, fraction: Float): Color = when {
    fraction < 0.40f -> t.dangerText
    fraction < 0.50f -> t.warnText
    else -> t.fg
}

/** 行内百分比：等宽、21sp（--text-lg = 21px）、700。 */
@Composable
internal fun percentStyle(t: DesignTokens): TextStyle = monitorMonoMeta.copy(
    fontSize = t.textLg,
    fontWeight = FontWeight.Bold,
    lineHeight = t.textLg,
)
