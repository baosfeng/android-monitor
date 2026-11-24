package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：口径说明 —— TripRulesCard（卡片外壳）、RuleRow（一条规则）、
// TripLocationHint（卡内「开启定位后可显示距离与地址」提示行）。负责「这些数字是怎么算出来的」。
//
// 原型结构：`div.pad > div.card > (sec-head + 3 条 .row + button.s16-hint)` —— 提示行是**卡内最后一行**，
// 不是独立卡片；它左边 36dp .row-icon、右边 18dp .chev。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

// 原型行盒：.row-title 17px × 1.25 = 21.25、.row-sub 14px × 1.3 = 18.2、两者之间 .row-body 的 gap 2；
// 行内 padding 上下各 --space-2（8）。CJK 自然行盒（17sp → 25、14sp → 20.6）会把每行撑高 6.2dp，
// 所以整行按盒模型钉死，文字用 unbounded 溢出绘制（同 PageRows.kt 的 ListRow 解法）。
private val RULE_ROW_H = 55.5.dp
private val RULE_TITLE_LINE = 21.25.dp
private val RULE_SUB_LINE = 18.2.dp

/** 「开启定位后可显示距离与地址」提示行的文案（锚点 s16-goto-location）。 */
private const val LOCATION_HINT_TITLE = "开启定位后可显示距离与地址"
private const val LOCATION_HINT_TEXT = "现在按设备活动推算，地点名来自网络名与使用簇"

/** 原型 .s16-hint：min-height 44 + 上下 padding 8 + 顶部 1px 描边；内容 36dp 图标 / 两行文字 / 18dp 箭头。 */
private val HINT_ROW_H = 60.4.dp
private val HINT_ICON = 36.dp

@Composable
internal fun TripRulesCard(
    basis: Basis,
    modifier: Modifier = Modifier,
    onOpenLocationSettings: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    // 原型 .card 是 border-box（1px 描边占位 + padding 16）→ 内容宽 361 − 2×17 = 327
    AppCard(modifier = modifier, padding = t.space4 + 1.dp) {
        CardHead("口径说明", basisBadge(basis))
        Spacer(Modifier.height(t.space2))
        RuleRow("停留", "连续 10 分钟以上停在同一地点")
        RuleRow("移动", "两点间净位移超过 300 米，且定位覆盖不少于 60%")
        RuleRow("未记录", "采集有中断，期间内容不做推断")
        if (basis == Basis.DEVICE) {
            RuleRow("地点名", "来自当前网络名与 App 使用簇；开启定位后可显示距离与地址")
        }
        // 原型 #s16 .s16-hint：卡内最后一行，带 border-top（第一条 .row 的 border-top 被 :first-child 去掉）
        if (onOpenLocationSettings != null) {
            HorizontalDivider(color = t.borderSoft)
            TripLocationHint(onClick = onOpenLocationSettings)
        }
    }
}

@Composable
private fun RuleRow(title: String, desc: String) {
    val t = LocalDesignTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(RULE_ROW_H)
            .padding(vertical = t.space2),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
        Box(Modifier.height(RULE_TITLE_LINE)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.height(RULE_SUB_LINE)) {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            )
        }
    }
}

/**
 * 「开启定位后可显示距离与地址」提示行（锚点 s16-goto-location）：原型卡内最后一行 button.s16-hint。
 *
 * click：行底色 120ms 加深 + LIGHT，右侧 › 不动画 → 跳设置页定位权限项；
 * 未接线（onClick 无来源）时整行不渲染，不伪造跳转。
 *
 * 锚点盒 = 原型整行（宽 327，扣掉卡内左右 17 内边距之后）：内边距在**内层 Column**，
 * 锚点挂在 padding 之内的那一层节点上，否则会连左右各 16 一起量进去（x −17 / w +34）。
 */
@Composable
internal fun TripLocationHint(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else t.fgFade(0),
        animationSpec = tween(t.durState),
        label = "gotoLocationBg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .testTag("s16-goto-location")
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .height(HINT_ROW_H),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(HINT_ICON)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fgFade(12)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.space5),
            )
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Box(Modifier.height(RULE_TITLE_LINE)) {
                Text(
                    text = LOCATION_HINT_TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.height(RULE_SUB_LINE)) {
                Text(
                    text = LOCATION_HINT_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
        }
        Spacer(Modifier.width(t.space2))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = t.muted.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}
