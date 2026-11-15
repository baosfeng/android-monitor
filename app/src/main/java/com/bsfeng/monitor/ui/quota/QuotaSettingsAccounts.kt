package com.bsfeng.monitor.ui.quota

// 额度设置页的账号分组：每个 provider 类型一个分组标题，组内一行一个账号，组尾「+ 添加账号」。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.IOSDivider
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 「+ 添加账号」那一行的文案（组尾，也用于无障碍读名）。 */
internal const val ADD_ACCOUNT_LABEL: String = "添加账号"

/**
 * 账号分组：**一个 provider 类型一个分组**，组内一行一个账号。
 *
 * 为什么不把多账号塞进同一张卡片做 tab：任务口径是「同一 provider 下挂多行账号」，
 * 每行都要能独立改名、独立填凭据、独立删；tab 会把「谁是谁」藏进点击层级，
 * 而聚合榜单与 chip 上恰恰要一眼分清。
 *
 * 分组顺序跟随 [providers]（= 注册表顺序），组内顺序跟随实例清单（= 创建顺序）。
 */
@Composable
internal fun AccountGroups(
    providers: List<QuotaProviderInfo>,
    expandedId: String?,
    savingProviderId: String?,
    saveErrors: Map<String, String>,
    intervalOf: (String) -> Int,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onSave: (instanceId: String, balance: String, usage: String) -> Unit,
    onClear: (String) -> Unit,
    onRename: (instanceId: String, label: String) -> Unit,
    onDelete: (String) -> Unit,
    onIntervalChange: (instanceId: String, minutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // linkedMapOf：保持分组首次出现的顺序（providers 已按注册顺序排好）
    val groups = linkedMapOf<String, MutableList<QuotaProviderInfo>>()
    providers.forEach { info -> groups.getOrPut(info.providerTypeId) { mutableListOf() } += info }

    Column(modifier.fillMaxWidth().testTag("s19-providers"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        groups.forEach { (providerTypeId, accounts) ->
            val title = accounts.first().typeDisplayName
            SectionHeader(title = title)
            SettingsGroup {
                accounts.forEachIndexed { index, info ->
                    if (index > 0) IOSDivider()
                    // 行本体在 QuotaProviderRows.kt：锚点只能由本屏包一层承载，
                    // 逐字沿用老锚点（第一实例 id 恒等于类型 id，老锚点天然命中）
                    Box(Modifier.fillMaxWidth().then(Modifier.providerRowTagOf(info.instanceId, providerTypeId))) {
                        ProviderSettingsRow(
                            info = info,
                            expanded = expandedId == info.instanceId,
                            onToggle = { onToggle(info.instanceId) },
                            onSave = { balance, usage -> onSave(info.instanceId, balance, usage) },
                            onClear = { onClear(info.instanceId) },
                            onRename = { label -> onRename(info.instanceId, label) },
                            onDelete = { onDelete(info.instanceId) },
                            saving = savingProviderId == info.instanceId,
                            saveError = saveErrors[info.instanceId],
                            intervalMinutes = intervalOf(info.instanceId),
                            onIntervalChange = { minutes -> onIntervalChange(info.instanceId, minutes) },
                        )
                    }
                }
                IOSDivider()
                AddAccountRow(label = title, onAdd = { onAdd(providerTypeId) })
            }
        }
    }
}

/** 组尾的「+ 添加账号」行：整行可点、触达 ≥44dp、成功后由数据层补一个空账号。 */
@Composable
private fun AddAccountRow(label: String, onAdd: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction)
            .testTag("row.addAccount")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onAdd()
            }
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = t.accent,
            modifier = Modifier.width(t.space4),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            ADD_ACCOUNT_LABEL,
            style = MaterialTheme.typography.labelLarge,
            color = t.accent,
        )
        Spacer(Modifier.weight(1f))
        Text(
            label + " · 另一个账号",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
    }
}

/** 分组容器：卡片底 + 圆角 + 描边（与设置页 iOS 分组一致），行内自己撑内边距。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surface)
            .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg)),
    ) {
        content()
    }
}
