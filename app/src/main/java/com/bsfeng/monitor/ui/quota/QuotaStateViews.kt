package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 额度页的状态视图：加载 / 未配置凭据 / 用量凭据缺失 / 失败条。
 *
 * 失败条与数据卡分开：刷新失败时**保留上次成功数据**（数据层已把 stale 与 fetchedAt 标好），
 * 页面只负责在数据上方补一条「数据停在 HH:mm」，而不是把整屏换成错误页。
 */

/** 加载态：细行骨架，形状与真实卡片一致（先骨架后卡片，避免高度跳变）。 */
@Composable
fun QuotaLoading(modifier: Modifier = Modifier) {
    AppCard(modifier) {
        StateSkeleton(rows = 5)
    }
}

/**
 * 未配置凭据（empty 态）：输入框 + 保存并查询 + 帮助文案。
 *
 * Ollama 只有一个凭据；DeepSeek 的余额（`sk-` Key）与用量（平台登录令牌）不通用，
 * 所以这里按 [QuotaProviderInfo] 的凭据类型决定显示一行还是两行。
 */
@Composable
fun QuotaConfigCard(
    info: QuotaProviderInfo,
    onSave: (balance: String, usage: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    var balance by remember(info.id) { mutableStateOf("") }
    var usage by remember(info.id) { mutableStateOf("") }
    val haptic = rememberHaptic()
    val needsUsageRow = info.usageKind != null && info.usageKind != info.balanceKind
    val canSubmit = balance.isNotBlank() || (needsUsageRow && usage.isNotBlank())
    AppCard(modifier) {
        Text("配置凭据后查看额度", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            "余额与用量来自 provider 官方接口；未配置前不会发起任何请求。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
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
            QuotaTextField(
                value = usage,
                onValueChange = { usage = it },
                placeholder = info.usageCredentialHint ?: "平台登录令牌",
            )
            Spacer(Modifier.height(t.space1))
            Text(
                info.usageCredentialHelp ?: "用量接口要平台登录令牌，与上面的 Key 不通用；只填一个也能用。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(t.space3))
        Button(
            onClick = {
                haptic(HapticTone.SUCCESS)
                onSave(balance.trim(), if (needsUsageRow) usage.trim() else "")
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(t.tapMin),
            shape = RoundedCornerShape(t.radiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.accent,
                contentColor = t.accentOn,
            ),
        ) {
            Text("保存并查询", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(t.space2))
        Text(
            "凭据只保存在本机，使用 Android Keystore 加密；不写日志、不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
    }
}

/**
 * 失败条：查询失败但仍有上次数据时叠在数据卡上方。
 *
 * [staleAtEpochSecond] 来自数据层的 fetchedAt（失败时不会前移），
 * 显示成「数据停在 HH:mm」让用户知道看到的是旧数，而不是当前值。
 */
@Composable
fun QuotaErrorBar(
    message: String,
    staleAtEpochSecond: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val haptic = rememberHaptic()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.danger.copy(alpha = t.danger.alpha * 0.10f))
            .border(1.dp, t.danger.copy(alpha = t.danger.alpha * 0.34f), shape)
            .padding(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.VpnKey,
            contentDescription = null,
            tint = t.dangerText,
            modifier = Modifier.padding(end = t.space3),
        )
        Column(Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
            Spacer(Modifier.height(t.space1))
            Text(
                if (staleAtEpochSecond > 0L) {
                    "数据停在 " + QuotaFormat.clockOf(staleAtEpochSecond) + " · 刷新失败"
                } else {
                    "刷新失败"
                },
                style = MaterialTheme.typography.bodySmall,
                color = t.fg2,
            )
        }
        Text(
            "重试",
            style = MaterialTheme.typography.labelMedium,
            color = t.dangerText,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(6))
                .clickable {
                    haptic(HapticTone.SUCCESS)
                    onRetry()
                }
                .padding(horizontal = t.space2, vertical = t.space1),
        )
    }
}

/** 余额有数据、但用量凭据没配置时的提示卡。 */
@Composable
fun QuotaUsageUnavailableCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier) {
        Text("用量", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            "余额已连通；再填一条用量凭据就能看请求次数、Token 与按模型明细。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        Text(
            "去额度设置填写",
            style = MaterialTheme.typography.labelMedium,
            color = t.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusSm))
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = t.space2, vertical = t.space1),
        )
    }
}

/** 单行凭据输入（密码遮罩；明文只在内存里出现）。 */
@Composable
fun QuotaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 金额阈值等非敏感输入传 false（明文 + 数字键盘）；凭据保持遮罩。 */
    obscure: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.surfaceWarm)
            .border(1.dp, t.border, shape)
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = t.muted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // 必须撑满整行：BasicTextField 的默认宽度随内容走，空值时点击区只剩光标那一小点，
            // 表现就是「输入框点不进去」。
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.fg),
            cursorBrush = SolidColor(t.accent),
            // 凭据一律遮罩：两类令牌都可能被旁人看到，没有「短的就明文」的余地
            visualTransformation = if (obscure) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (obscure) KeyboardType.Password else KeyboardType.Decimal,
            ),
        )
    }
}
