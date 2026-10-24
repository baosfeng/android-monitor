package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.quota.QuotaSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.math.BigDecimal

/**
 * 一行 provider：名称 / 连接状态 / 已配置凭据类型；点开在两行内完成填写。
 *
 * 展开态不做二级页面：凭据只有 1～2 条，push 一层反而让「改一条 Key」变成三次导航。
 */
@Composable
internal fun ProviderSettingsRow(
    info: QuotaProviderInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (balance: String, usage: String) -> Unit,
    intervalMinutes: Int = QuotaSettings.defaultMinutesFor(info.id),
    onIntervalChange: (Int) -> Unit = {},
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    var balance by remember(info.id, expanded) { mutableStateOf("") }
    var usage by remember(info.id, expanded) { mutableStateOf("") }
    val needsUsageRow = info.usageKind != null && info.usageKind != info.balanceKind
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic(HapticTone.LIGHT)
                onToggle()
            }
            .padding(t.space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.displayName, style = MaterialTheme.typography.titleSmall, color = t.fg)
                Spacer(Modifier.height(t.space1))
                Text(
                    statusText(info),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            Spacer(Modifier.width(t.space2))
            if (info.connected) {
                Badge("已连接", tone = BadgeTone.Success)
            } else {
                Badge("未配置", tone = BadgeTone.Warn)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = t.muted,
                modifier = Modifier.padding(start = t.space1),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(t.space3))
            Text(
                "余额 · ${kindLabel(info.balanceKind)}",
                style = MaterialTheme.typography.labelMedium,
                color = t.fg2,
            )
            Spacer(Modifier.height(t.space1))
            QuotaTextField(
                value = balance,
                onValueChange = { balance = it },
                placeholder = info.credentialHint.ifBlank { "API Key" },
            )
            if (info.credentialHelp.isNotBlank()) {
                Spacer(Modifier.height(t.space1))
                Text(info.credentialHelp, style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
            if (needsUsageRow) {
                Spacer(Modifier.height(t.space3))
                Text(
                    "用量 · ${kindLabel(info.usageKind)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.fg2,
                )
                Spacer(Modifier.height(t.space1))
                QuotaTextField(
                    value = usage,
                    onValueChange = { usage = it },
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
                enabled = balance.isNotBlank() || (needsUsageRow && usage.isNotBlank()),
                onClick = {
                    haptic(HapticTone.SUCCESS)
                    onSave(balance.trim(), usage.trim())
                },
            )
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
        }
    }
}

/** 保存按钮（主色 + 卡片内宽度；命中成功档触觉）。 */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (enabled) t.accent else t.fgFade(10))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "保存并查询",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) t.accentOn else t.muted,
        )
    }
}

/** 低余额阈值弹窗：CNY / USD 各一个输入，保存即生效（空值不允许提交）。 */
@Composable
internal fun ThresholdDialog(
    initialCny: BigDecimal,
    initialUsd: BigDecimal,
    onDismiss: () -> Unit,
    onConfirm: (BigDecimal, BigDecimal) -> Unit,
) {
    val t = LocalDesignTokens.current
    var cny by remember { mutableStateOf(initialCny.toPlainString()) }
    var usd by remember { mutableStateOf(initialUsd.toPlainString()) }
    val cnyValue = cny.trim().toBigDecimalOrNull()
    val usdValue = usd.trim().toBigDecimalOrNull()
    val valid = cnyValue != null && usdValue != null &&
        cnyValue.signum() >= 0 && usdValue.signum() >= 0
    AppDialog(visible = true, onDismiss = onDismiss) {
        Text("低余额阈值", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            "余额低于阈值时提醒一次，充值回升后重新计数；两个币种各算各的。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        Text("CNY（¥）", style = MaterialTheme.typography.labelMedium, color = t.fg2)
        Spacer(Modifier.height(t.space1))
        QuotaTextField(value = cny, onValueChange = { cny = it }, placeholder = "50", obscure = false)
        Spacer(Modifier.height(t.space3))
        Text("USD（$）", style = MaterialTheme.typography.labelMedium, color = t.fg2)
        Spacer(Modifier.height(t.space1))
        QuotaTextField(value = usd, onValueChange = { usd = it }, placeholder = "5", obscure = false)
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DialogButton("取消", primary = false, onClick = onDismiss, modifier = Modifier.weight(1f))
            DialogButton(
                "保存",
                primary = true,
                enabled = valid,
                onClick = { if (valid) onConfirm(cnyValue, usdValue) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (primary) t.accent else t.surface)
            .border(1.dp, if (primary) t.accent else t.border, RoundedCornerShape(t.radiusMd))
            .clickable(enabled = enabled) {
                // 保存走成功档（CONFIRM），取消走轻触
                haptic(if (primary) HapticTone.SUCCESS else HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> t.muted
                primary -> t.accentOn
                else -> t.fg
            },
        )
    }
}

private fun statusText(info: QuotaProviderInfo): String {
    val kinds = buildList {
        if (info.balanceConfigured) add(kindLabel(info.balanceKind))
        if (info.usageConfigured && info.usageKind != null && info.usageKind != info.balanceKind) {
            add(kindLabel(info.usageKind))
        }
    }
    return when {
        kinds.isNotEmpty() -> "已连接 · 已配置 ${kinds.joinToString(" / ")}"
        info.connected -> "已连接"
        info.usageKind != null -> "未配置 · 余额 + 用量（需平台登录令牌）"
        else -> "未配置"
    }
}

private fun kindLabel(kind: QuotaCredentialKind): String = when (kind) {
    QuotaCredentialKind.API_KEY -> "API Key"
    QuotaCredentialKind.PLATFORM_TOKEN -> "平台登录令牌"
    QuotaCredentialKind.SESSION_COOKIE -> "网页 Cookie"
}

/** 刷新间隔小 chip（1 / 3 / 5 / 10 / 15 分钟）。 */
@Composable
private fun IntervalChips(selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(t.space1)) {
        QuotaSettings.MINUTE_OPTIONS.forEach { minutes ->
            val on = minutes == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .background(if (on) t.fgFade(10) else Color.Transparent)
                    .clickable {
                        haptic(HapticTone.LIGHT)
                        onSelect(minutes)
                    }
                    .padding(horizontal = t.space2, vertical = t.space1),
            ) {
                Text(
                    "${minutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) t.fg else t.muted,
                )
            }
        }
    }
}
