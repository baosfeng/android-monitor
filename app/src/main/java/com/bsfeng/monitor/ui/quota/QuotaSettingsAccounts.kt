package com.bsfeng.monitor.ui.quota

// s19 账号分组：一组「账号」卡（同 provider 多账号并列），组尾每组一个「添加 X 账号」行。
// 与旧版「每个 provider 一张卡 + 组头」不同：原型是一张卡里按 provider 连续排，加号行属于该组。

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 分组小标题：左侧 11sp 700 标题，右侧 11sp muted 摘要（原型 .grp-label）。 */
@Composable
internal fun GroupLabel(title: String, trailing: String = "") {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space1, vertical = 0.dp)
            .padding(bottom = t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = ((11 * t.leadingBody)).sp),
            color = t.fg,
            fontWeight = FontWeight.Bold,
        )
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = ((11 * t.leadingBody)).sp),
                color = t.muted,
            )
        }
    }
}

/** iOS 分组卡底：1dp 描边 + radiusLg + surface；行内自己撑内边距（原型 .box）。 */
@Composable
internal fun SettingsBox(content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surface)
            .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg)),
    ) {
        content()
    }
}

/**
 * 账号组：一张卡内按 provider 连续排列，组尾各有一条「添加 X 账号」。
 *
 * @param providers 实例清单（顺序 = 注册顺序 + 组内创建顺序，见 QuotaInstances.normalize）
 * @param states 每个实例的拉取状态（副行的时间/窗口/错误都从这里来）
 */
@Composable
internal fun AccountsGroup(
    providers: List<QuotaProviderInfo>,
    states: Map<String, QuotaRepository.ProviderState>,
    nowEpochSecond: Long,
    expandedId: String?,
    savingProviderId: String?,
    saveErrors: Map<String, String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onSave: (instanceId: String, value: String) -> Unit,
    onClear: (String) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val groups = linkedMapOf<String, MutableList<QuotaProviderInfo>>()
    providers.forEach { groups.getOrPut(it.providerTypeId) { mutableListOf() } += it }
    val statuses = providers.associateWith { accountStatusText(it, states[it.instanceId], nowEpochSecond) }
    Column(modifier.fillMaxWidth().padding(horizontal = t.space4).testTag("s19-accounts")) {
        GroupLabel(
            title = "账号",
            trailing = accountsGroupSummary(
                providers.size,
                statuses.values.count(::accountNeedsAttention),
            ),
        )
        SettingsBox {
            var first = true
            groups.forEach { (typeId, accounts) ->
                accounts.forEachIndexed { index, info ->
                    val suffix = accountAnchorSuffix(typeId, index)
                    AccountRow(
                        info = info,
                        suffix = suffix,
                        topBorder = !first,
                        status = statuses.getValue(info),
                        expanded = expandedId == info.instanceId,
                        configured = info.connected,
                        saving = savingProviderId == info.instanceId,
                        saveError = saveErrors[info.instanceId],
                        onToggle = { onToggle(info.instanceId) },
                        onSave = { value -> onSave(info.instanceId, value) },
                        onClear = { onClear(info.instanceId) },
                        onToast = onToast,
                    )
                }
                // 该组有账号处于错误态时不放「添加账号」：先把当前凭据修好，别叠一个新账号
                // （原型 s19 的 DeepSeek 组也没有这一行，与它的令牌过期态对应）
                first = false
                val hasError = accounts.any { states[it.instanceId]?.errorMessage != null }
                if (!hasError) {
                    AddAccountRow(
                        label = "添加 " + accounts.first().typeDisplayName + " 账号",
                        anchor = addAccountAnchorOf(typeId),
                        onAdd = { onAdd(typeId) },
                    )
                }
            }
        }
        Text(
            "添加账号不会改动已有账号的凭据与 ID；只剩一个账号时不显示「删除」。",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = ((12 * t.leadingBody)).sp),
            color = t.muted,
            modifier = Modifier
                .padding(horizontal = 2.dp) // 原型 .fine 的 margin:6px 2px 0
                .padding(top = 6.dp)
                .testTag("s19-accounts-foot"),
        )
    }
}

/** 组尾「+ 添加 X 账号」：44dp 高、整宽可点；成功与否由屏层 toast。 */
@Composable
private fun AddAccountRow(label: String, anchor: String, onAdd: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .pressFeedback(interaction)
            .testTag(anchor)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onAdd()
            }
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null, // decorative：行文本「添加 X 账号」已是可读名
            tint = t.accentText,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = t.accentText,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 通用分割线（组内行间）：1dp、borderSoft。 */
@Composable
internal fun SettingsHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalDesignTokens.current.borderSoft),
    )
}

/**
 * 行顶边线：原型 .arow/.acc/.srow 的 border-top 画在**行自己的盒子里**（不额外占高）。
 *
 * 用 drawBehind 而不是再插一个 1dp 分割线：后者会把每个边界累计推高 1dp，
 * 六七个边界之后整页偏低 6–7dp（s19 实测）。
 */
@Composable
internal fun Modifier.topHairline(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val color = LocalDesignTokens.current.borderSoft
    return drawBehind {
        val y = 0.5.dp.toPx()
        drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}