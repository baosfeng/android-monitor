package com.bsfeng.monitor.ui.quota

// 从 ui/quota/QuotaSettingsProviders.kt 拆出：provider 行 —— ProviderSettingsRow（名称 + 状态 + 凭据填写）、SaveButton、IntervalChips（刷新间隔选项）。负责「一行内完成 provider 配置」。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.theme.dotDiameter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.quota.QuotaSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

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
// ───────────────────── 单家态 · provider chip 行 ─────────────────────
// 从 QuotaCards.kt 迁来并补契约锚点：共享件只给容器 modifier，chip 级 testTag 落不下去，
// 而 s18 契约要求 4 个 chip 各有锚点（s18-provider-ollama / -deepseek / -opencode / -opendesign）。

/**
 * provider 横向 chip 行（原型 .s18-providers）：可横滑、已配置带绿点、选中态互斥。
 *
 * 横滑只改变可视范围、不切换 provider：LazyRow 自带位移阈值，位移小于 8px 判为 click 不误触。
 */
@Composable
internal fun QuotaProviderChips(
    providers: List<QuotaProviderInfo>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("s18-providers"),
        contentPadding = PaddingValues(horizontal = t.space4),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items(providers, key = { it.id }) { info ->
            QuotaProviderChip(
                info = info,
                selected = info.id == selectedId,
                onClick = { onSelect(info.id) },
            )
        }
    }
}

/** chip 锚点按 provider id 逐字映射到契约（不改号、不新增）；字面量落在这里才能被锚点对账扫到。 */
private fun chipTagOf(providerId: String): Modifier = when (providerId) {
    "ollama" -> Modifier.testTag("s18-provider-ollama")
    "deepseek" -> Modifier.testTag("s18-provider-deepseek")
    "opencode-go" -> Modifier.testTag("s18-provider-opencode")
    "opendesign" -> Modifier.testTag("s18-provider-opendesign")
    else -> Modifier
}

@Composable
private fun QuotaProviderChip(
    info: QuotaProviderInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "providerChipBg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "providerChipStroke",
    )
    Row(
        modifier = Modifier
            .height(t.tapMin)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .then(chipTagOf(info.id))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                // 已是当前 provider 时不重复震动、不重复 toast（口径：同一操作不重复震动）
                if (selected) return@clickable
                // provider 切换等价于 Tab 切换：用中触，与列表点击的轻触区分
                haptic(HapticTone.MEDIUM)
                onClick()
            }
            .padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        // 绿点只表示「已配置凭据且能拉数据」，未配置的家恒为灰点
        Box(
            Modifier
                .size(t.dotDiameter)
                .clip(CircleShape)
                .background(if (info.connected) t.success else t.fgFade(30)),
        )
        Text(
            info.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.fg else t.fg2,
            maxLines = 1,
        )
    }
}
