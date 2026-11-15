package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.dotDiameter
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 单家视图的 provider chip 行（原型 .s18-providers）：可横滑、已配置带绿点、选中态互斥。
 *
 * 一行 = 一个**账号**：同一 provider 挂两个账号时会出现两个 chip（`DeepSeek · 账号 2`），
 * 选中态按实例键判，而不是按类型 —— 否则点第二个账号会高亮第一个。
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
        items(providers, key = { it.instanceId }) { info ->
            QuotaProviderChip(
                info = info,
                selected = info.instanceId == selectedId,
                onClick = { onSelect(info.instanceId) },
            )
        }
    }
}

/** chip 锚点按 provider **类型**逐字映射到口径（不改号、不新增）；字面量落在这里才能被锚点对账扫到。 */
private fun Modifier.chipTagOf(providerTypeId: String): Modifier = when (providerTypeId) {
    "ollama" -> testTag("s18-provider-ollama")
    "deepseek" -> testTag("s18-provider-deepseek")
    "opencode-go" -> testTag("s18-provider-opencode")
    "opendesign" -> testTag("s18-provider-opendesign")
    else -> this
}

/**
 * 单个 chip：绿点（是否已配置）+ 账号名。
 *
 * 名字来自 [QuotaProviderInfo.displayName]：同类型多账号时已带上账号名（`DeepSeek · 账号 2`），
 * 单账号时就是类型名 —— 这条口径只在 domain 的 `QuotaInstances.displayName` 里实现一次。
 */
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
            .then(Modifier.chipTagOf(info.providerTypeId))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                // 已是当前账号时不重复震动、不重复 toast（口径：同一操作不重复震动）
                if (selected) return@clickable
                // provider 切换等价于 Tab 切换：用中触，与列表点击的轻触区分
                haptic(HapticTone.MEDIUM)
                onClick()
            }
            .padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
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
            modifier = Modifier.testTag("chip.provider"),
        )
    }
}
