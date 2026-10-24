package com.bsfeng.monitor.ui.quota

// 从 ui/quota/QuotaSettingsProviders.kt 拆出：provider 的两个弹窗 —— ThresholdDialog（阈值设置）与内部 DialogButton。负责「改阈值」这条编辑路径。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.math.BigDecimal

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
