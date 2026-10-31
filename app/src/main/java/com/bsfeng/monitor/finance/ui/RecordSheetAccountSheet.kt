package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 AccountSheet；职责：选择账户抽屉（卡片分组列表，行内带余额）。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DividerDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 选择账户（列表带余额：一眼看出选的是哪个钱包） */
@Composable
internal fun BoxScope.AccountSheet(
    visible: Boolean,
    title: String,
    accounts: List<Account>,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    SheetShell(title = title, onDismiss = onDismiss) {
        // 锚点 s14a-acct-sheet（「选择账户」抽屉）：贴在面板内容根上，关闭即随抽屉卸载
        Box(Modifier.fillMaxWidth().testTag("s14a-acct-sheet")) {
        // 原型是 .group：整组一个卡片（surface 底 + 1px --border-soft + radius-lg + 左右 --space-4），
        // 不是裸列表 —— 裸列表会和下面的 sheet 底同色，三行看不出是一组
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 锚点 s14a-acct-list：账户行列表（行内对勾 / 按下态都在这里）
                .testTag("s14a-acct-list")
                .clip(shape)
                .background(t.surface)
                .border(1.dp, t.borderSoft, shape)
                .padding(horizontal = t.space4),
        ) {
            accounts.forEachIndexed { index, account ->
                AccountSheetRow(
                    account = account,
                    selected = account.name == selectedName,
                    onClick = { onPick(account.name) },
                )
                if (index != accounts.lastIndex) {
                    HorizontalDivider(
                        thickness = DividerDefaults.Thickness,
                        color = t.borderSoft,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AccountSheetRow(account: Account, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 契约 s14a-acct-list：本行按下背景 fg 5%（120ms），选中后对勾移到本行（单选不可清空）
    val bg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "acctRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .row：min-height --row-min-h(52px) + 上下 padding --space-2。
            // 必须用 defaultMinSize 而不是 heightIn —— CSS 的 min-height **含** padding，
            // Compose 的 heightIn 约束的是 padding **之内**的内容区（会多出一个 padding 的高度）。
            .defaultMinSize(minHeight = t.rowMinH)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(bg)
            .testTag("row.acct")
            .clickable(interactionSource = interaction, indication = null) {
                // 行选中属轻触级；已选中的行再点不取消、不重复震
                if (!selected) haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            // 原型 .row-body{gap:2px}：标题与副标题之间只有 2px
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                account.name,
                // 原型 .row-title 用 CSS line-height(l.25) 定行高；Compose 默认 includeFontPadding
                // 会给每个 Text 额外留几 dp，两行叠起来比原型高一截（实测 63dp vs 原型 58px）
                style = MaterialTheme.typography.titleSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                // 原型 .row-sub：--text-sm(14px) + --muted
                "余额 ¥" + account.displayBalance,
                style = MaterialTheme.typography.bodySmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.muted,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}
