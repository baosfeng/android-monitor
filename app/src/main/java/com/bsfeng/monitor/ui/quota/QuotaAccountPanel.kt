package com.bsfeng.monitor.ui.quota

// 设置页账号行的展开区：改名 → 凭据 → 保存 → 清除 → 刷新间隔 → 删除账号。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 展开区：改名 → 凭据 → 保存 → 清除 → 刷新间隔 → 删除账号。 */
@Composable
internal fun AuthoredAccountPanel(
    info: QuotaProviderInfo,
    balance: String,
    usage: String,
    nameDraft: String,
    needsUsageRow: Boolean,
    saving: Boolean,
    saveError: String?,
    clearAction: CredentialClearAction,
    intervalMinutes: Int,
    onBalanceChange: (String) -> Unit,
    onUsageChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onClearClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onIntervalChange: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Column {
        Spacer(Modifier.height(t.space3))
        Text("账号名", style = MaterialTheme.typography.labelMedium, color = t.fg2)
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                QuotaTextField(
                    value = nameDraft,
                    onValueChange = onNameChange,
                    placeholder = if (info.multiAccount) info.displayName else info.typeDisplayName,
                    modifier = Modifier.testTag("field.accountName"),
                )
            }
            Spacer(Modifier.width(t.space2))
            InlineTextAction(
                label = "改名",
                enabled = !saving,
                onClick = {
                    haptic(HapticTone.LIGHT)
                    onRename()
                },
            )
        }
        Spacer(Modifier.height(t.space1))
        Text(
            "留空则用默认名；同一 provider 挂多个账号时，榜单与 chip 会带上这个名字。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )

        Spacer(Modifier.height(t.space3))
        Text("余额 · ${kindLabel(info.balanceKind)}", style = MaterialTheme.typography.labelMedium, color = t.fg2)
        Spacer(Modifier.height(t.space1))
        QuotaTextField(
            value = balance,
            onValueChange = onBalanceChange,
            placeholder = info.credentialHint.ifBlank { "API Key" },
        )
        if (info.credentialHelp.isNotBlank()) {
            Spacer(Modifier.height(t.space1))
            Text(info.credentialHelp, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        if (needsUsageRow) {
            Spacer(Modifier.height(t.space3))
            Text(
                "用量 · ${kindLabel(requireNotNull(info.usageKind))}",
                style = MaterialTheme.typography.labelMedium,
                color = t.fg2,
            )
            Spacer(Modifier.height(t.space1))
            QuotaTextField(
                value = usage,
                onValueChange = onUsageChange,
                placeholder = info.usageCredentialHint ?: "平台登录令牌",
            )
            Spacer(Modifier.height(t.space1))
            Text(
                info.usageCredentialHelp ?: "用量接口要平台登录令牌，与余额 Key 不通用；只填一个也能用。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(t.space3))
        SaveButton(
            enabled = !saving && (balance.isNotBlank() || (needsUsageRow && usage.isNotBlank())),
            onClick = onSave,
        )
        if (clearAction.visible) {
            Spacer(Modifier.height(t.space2))
            ClearCredentialButton(enabled = clearAction.enabled, onClick = onClearClick)
        }
        if (saveError != null) {
            Spacer(Modifier.height(t.space2))
            // 就地提示：保存 / 查询失败时输入保留，改一改再点保存即可（锚点 s19 边界）
            Text(
                "保存后查询失败：" + saveError + " · 输入已保留，可修改后重试",
                style = MaterialTheme.typography.bodySmall,
                color = t.dangerText,
            )
        }
        Spacer(Modifier.height(t.space4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "刷新间隔",
                style = MaterialTheme.typography.labelMedium,
                color = t.fg2,
                modifier = Modifier.weight(1f),
            )
            IntervalChips(selected = intervalMinutes, onSelect = onIntervalChange)
        }
        Spacer(Modifier.height(t.space1))
        Text(
            "前台停留在额度页时按这个间隔刷新；Ollama 限流 10 次/分钟（跨设备共享），别调太小。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        // 只剩一个账号时不给删除入口：没有「某类型零账号」这个状态
        if (info.multiAccount) {
            Spacer(Modifier.height(t.space3))
            InlineTextAction(
                label = "删除此账号",
                enabled = !saving,
                danger = true,
                onClick = {
                    haptic(HapticTone.LIGHT)
                    onDeleteClick()
                },
            )
        }
    }
}

/** 展开区里的一行文字操作（改名 / 删除）：触达区 ≥44dp，破坏性用 danger 语义。 */
@Composable
private fun InlineTextAction(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = t.tapMin)
            .clip(RoundedCornerShape(t.radiusSm))
            .pressFeedback(interaction, enabled = enabled)
            .testTag(if (danger) "button.deleteAccount" else "button.renameAccount")
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = t.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> t.muted
                danger -> t.dangerText
                else -> t.accent
            },
        )
    }
}
