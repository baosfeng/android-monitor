package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 账号列表的一行（原型 .acct-hd）：品牌块 + 名称/徽标 + 最多两列数字 + chevron。
 *
 * 行高按原型 71.6dp 复现：数位列每段文字都用固定行盒 + unbounded 绘制
 * （CJK 自然行盒 1.47 倍会把 13sp 的窗口名撑高 3dp，见对齐台账「行盒」一节）。
 */
@Composable
internal fun QuotaAccountRow(
    account: QuotaAccountUi,
    key: String?,
    expanded: Boolean,
    showWhen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val numbers = account.columns.joinToString("，") { column ->
        column.title + "剩余 " + column.valueText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 原型 .acct-hd{min-height:64px;padding:6px 12px}
            .heightIn(min = 64.dp)
            .then(
                if (key != null) Modifier.testTag(quotaAccountRowTag(key)) else Modifier,
            )
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onToggle()
            }
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "已展开" else "已折叠"
                contentDescription = if (expanded) {
                    "收起 " + account.headline + "，" + numbers
                } else {
                    "展开 " + account.headline + " 的用量，" + numbers
                }
            }
            // 原型 .acct-hd padding:6px 12px（6 不在间距 token 的档位上，按原型取值）
            .padding(horizontal = t.space3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuotaBrandMark(account.providerTypeId)
        Spacer(Modifier.width(t.space2))
        Column(Modifier.weight(1f)) {
            FixedLine(NAME_LINE, MaterialTheme.typography.bodyMedium, account.name, t.fg, bold = true)
            account.badge?.let { badge ->
                Spacer(Modifier.height(2.dp))
                QuotaAccountBadgePill(badge)
            }
        }
        Spacer(Modifier.width(t.space2))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2), verticalAlignment = Alignment.Top) {
            account.columns.forEach { column -> AccountColumn(column, showWhen) }
        }
        Spacer(Modifier.width(t.space2))
        AccountChevron(expanded)
    }
}

/** 原型 .acct-name 行盒：16 × 1.2 = 19.2。 */
private val NAME_LINE = 19.2.dp

/** 数位列宽度（原型 .ncol width:70px）。 */
private val NUMBER_COLUMN_WIDTH = 70.dp

/** 固定行盒 + unbounded 文本：布局按原型行盒、字形完整溢出（对齐台账的 CJK 撑高解法）。 */
@Composable
internal fun FixedLine(
    height: Dp,
    style: TextStyle,
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = 1,
) {
    Box(modifier.height(height)) {
        Text(
            text = text,
            style = if (bold) style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
        )
    }
}

/** 徽标（原型 .badge）：20dp 高、左右 6dp、pill、13/600。 */
@Composable
private fun QuotaAccountBadgePill(badge: QuotaAccountBadge) {
    val t = LocalDesignTokens.current
    val danger = badge.tone == QuotaBadgeTone.DANGER
    Box(
        Modifier
            .height(20.dp)
            .widthIn(min = 20.dp)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (danger) t.danger else t.fgFade(9))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            badge.text,
            style = quotaLabel13.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = if (danger) t.accentOn else t.fg2,
            maxLines = 1,
        )
    }
}

/** 一列数字：20sp 等宽 → 5dp 条 → 13sp 窗口名 → 11sp 重置时刻。 */
@Composable
private fun AccountColumn(column: QuotaAccountColumn, showWhen: Boolean) {
    val t = LocalDesignTokens.current
    val barColor = when (column.state) {
        QuotaColumnState.DEAD -> t.danger
        QuotaColumnState.LOW -> t.warn
        QuotaColumnState.NORMAL -> t.fg2
    }
    Column(Modifier.width(NUMBER_COLUMN_WIDTH)) {
        // 数值不截断：¥110.00 在 70dp 列里比百分比宽（原型 .n 是 nowrap、溢出可见），
        // 截成「¥110....」会让人以为余额就是 110 元后面的小数没了
        Box(Modifier.height(21.dp)) {
            Text(
                column.valueText,
                style = quotaMonoRow,
                color = t.fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.fgFade(12)),
        ) {
            val fraction = column.fraction
            if (fraction != null && fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(t.radiusPill))
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        FixedLine(14.9.dp, quotaLabel13, column.title, t.fg2)
        if (showWhen && column.resetText != null) {
            Spacer(Modifier.height(2.dp))
            FixedLine(12.65.dp, quotaTextSmall, column.resetText, t.muted)
        }
    }
}

/** chevron（原型 .chev 18×18，stroke 1.8；展开时转 90°）。 */
@Composable
private fun AccountChevron(expanded: Boolean) {
    val t = LocalDesignTokens.current
    Canvas(
        Modifier
            .size(18.dp)
            .rotate(if (expanded) 90f else 0f),
    ) {
        val s = size.minDimension / 24f
        val stroke = 1.8f * s
        val color = t.muted
        drawLine(color, Offset(9f * s, 5.5f * s), Offset(15.5f * s, 12f * s), stroke, StrokeCap.Round)
        drawLine(color, Offset(15.5f * s, 12f * s), Offset(9f * s, 18.5f * s), stroke, StrokeCap.Round)
    }
}

/** 卡片在列表里的位置：决定哪几条边要圆角。 */
internal enum class QuotaCardPosition { FIRST, MIDDLE, LAST, ONLY }

/** 行在列表里的位置（单行列表 = ONLY）。 */
internal fun quotaCardPosition(index: Int, count: Int): QuotaCardPosition = when {
    count <= 1 -> QuotaCardPosition.ONLY
    index == 0 -> QuotaCardPosition.FIRST
    index == count - 1 -> QuotaCardPosition.LAST
    else -> QuotaCardPosition.MIDDLE
}

/** 卡片圆角（原型 .list radius-lg 18px / .acct 首尾 16px，取 16）。 */
private val CARD_RADIUS = 16.dp

/**
 * 账号卡的外框：底色 + 左右 / 底边各 1dp 描边，首行补顶边与上圆角、末行下圆角。
 *
 * 为什么不用 Modifier.border：Compose 的 border 四边都画，相邻两行会在分隔处叠成 2dp；
 * 这里按「每行画自己的底边」画线，行与行之间正好 1dp，和原型 .acct+.acct{border-top} 同宽。
 * Path 在 drawWithCache 的 build 块里建（门禁：绘制 lambda 内不得分配 Path）。
 */
internal fun Modifier.quotaCardChrome(
    position: QuotaCardPosition,
    surface: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
): Modifier = drawWithCache {
    val radius = CARD_RADIUS.toPx()
    val top = if (position == QuotaCardPosition.FIRST || position == QuotaCardPosition.ONLY) radius else 0f
    val bottom = if (position == QuotaCardPosition.LAST || position == QuotaCardPosition.ONLY) radius else 0f
    val hair = 1.dp.toPx()
    val half = hair / 2f
    val fill = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(top),
                topRightCornerRadius = CornerRadius(top),
                bottomRightCornerRadius = CornerRadius(bottom),
                bottomLeftCornerRadius = CornerRadius(bottom),
            ),
        )
    }
    onDrawBehind {
        drawPath(fill, surface)
        drawLine(border, Offset(half, 0f), Offset(half, size.height), hair)
        drawLine(border, Offset(size.width - half, 0f), Offset(size.width - half, size.height), hair)
        drawLine(border, Offset(hair, size.height - half), Offset(size.width - hair, size.height - half), hair)
        if (top > 0f) {
            drawLine(border, Offset(top, half), Offset(size.width - top, half), hair)
            val arc = Size(2f * (top - half), 2f * (top - half))
            drawArc(border, 180f, 90f, false, Offset(half, half), arc, style = Stroke(hair))
            drawArc(border, 270f, 90f, false, Offset(size.width - half - arc.width, half), arc, style = Stroke(hair))
        }
        if (bottom > 0f) {
            val arc = Size(2f * (bottom - half), 2f * (bottom - half))
            drawArc(border, 0f, 90f, false, Offset(size.width - half - arc.width, size.height - half - arc.height), arc, style = Stroke(hair))
            drawArc(border, 90f, 90f, false, Offset(half, size.height - half - arc.height), arc, style = Stroke(hair))
        }
    }
}
