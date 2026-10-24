package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 AccountSheet；职责：选择账户抽屉（卡片分组列表，行内带余额）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

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
        // 原型是 .group：整组一个卡片（surface 底 + 1px --border-soft + radius-lg + 左右 --space-4），
        // 不是裸列表 —— 裸列表会和下面的 sheet 底同色，三行看不出是一组
        Column(
            modifier = Modifier
                .fillMaxWidth()
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

@Composable
private fun AccountSheetRow(account: Account, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .row：min-height --row-min-h(52px) + 上下 padding --space-2。
            // 必须用 defaultMinSize 而不是 heightIn —— CSS 的 min-height **含** padding，
            // Compose 的 heightIn 约束的是 padding **之内**的内容区（会多出一个 padding 的高度）。
            .defaultMinSize(minHeight = t.rowMinH)
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable { onClick() }
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
