package com.bsfeng.monitor.ui.quota

// s19 刷新节奏组：后台刷新分钟档 5/15/30/60/关 + 节奏说明 + 过期阈值 + 息屏不刷新。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 刷新节奏组。
 *
 * 后台档位是分钟语义（[QuotaSettings.REFRESH_OPTIONS]）；前台仍是每账号自己的间隔，
 * 这里只在说明里展示当前展开账号的实际值，不把两者合成一个数。
 */
@Composable
internal fun RefreshRhythmGroup(
    backgroundMinutes: Int,
    frontMinutes: Int,
    sleepSkip: Boolean,
    onSelect: (Int) -> Unit,
    onSleepChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth().padding(horizontal = t.space4)) {
        Column(Modifier.fillMaxWidth().testTag("s19-refresh")) {
            GroupLabel(title = "刷新节奏", trailing = "决定了「更新于 x 分钟前」")
            SettingsBox {
                // 原型 .chips{border-top:1px;padding:10px 12px}：这 1px 占位（agent-browser 实测），
                // 少了它 chip 行整体高 1、且首个 chip 的 y 差 2
                SettingsHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        // 原型 .chips padding-top 10；这里 11 = 10 + 补回 Compose 不占位的边框
                        .padding(start = t.space3, end = t.space3, top = 11.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(t.space2),
                ) {
                    QuotaSettings.REFRESH_OPTIONS.forEach { minutes ->
                        RefreshChip(
                            label = QuotaSettings.chipLabelOf(minutes),
                            selected = minutes == backgroundMinutes,
                            onClick = { onSelect(minutes) },
                            modifier = Modifier.refreshChipTagOf(minutes),
                        )
                    }
                }
                // 原型 .rhythm{margin:11px 0}：chip 行底 → rhythm 顶、rhythm 底 → 阈值行顶各 11
                Spacer(Modifier.height(11.dp))
                // 锚点打在整块 p（x=17、w=359、h=59.8）；文字左右 12、下留 10，三行 15.95
                Box(Modifier.fillMaxWidth().height(59.8.dp).testTag("s19-rhythm")) {
                    Text(
                        rhythmText(frontMinutes = frontMinutes, backgroundMinutes = backgroundMinutes),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontSize = 11.sp, lineHeight = (11 * 1.45f).sp)
                            .quotaTight(),
                        color = t.muted,
                        modifier = Modifier.padding(start = t.space3, end = t.space3, bottom = 10.dp),
                    )
                }
                Spacer(Modifier.height(11.dp))
                SettingsInfoRow(
                    title = "过期阈值",
                    subtitle = "超过该时长没取到新数据就在页面上标黄",
                    trailing = { Badge("2× 刷新间隔") },
                )
                SettingsSwitchRow(
                    title = "息屏时不刷新",
                    subtitle = "省电；下次亮屏或打开 App 时补一次",
                    checked = sleepSkip,
                    onCheckedChange = onSleepChange,
                    switchTag = "s19-sw-sleep",
                )
            }
        }
    }
}

/** 后台刷新 chip：11sp 600 文本、44dp 高、pill 描边；选中 = fg 7% 底 + fg 24% 描边。 */
@Composable
private fun RefreshChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .widthIn(min = t.tapMin)
            .height(t.tapMin)
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(if (selected) t.fgFade(7) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) t.fgFade(24) else t.border, shape)
            .selectable(
                selected = selected,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            // 原型 chip padding 0 12；12sp 文本比 11px 宽 1–2dp，左右各收 1 让五个 chip 的
            // x/宽累计误差都 < 3（实测 56.3/63.3/63.3/56.3/46.7 vs 57.2/63.3/63.3/57.2/48）
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = if (selected) t.fg else t.fg2,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 信息行：标题 + 说明 + 右侧角标（原型 .srow）。 */
@Composable
private fun SettingsInfoRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .srow{min-height:48;border-top:1;padding:6px 12px}（agent-browser 实测 48）
            .height(48.dp)
            .topHairline()
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = ((12 * 1.25f)).sp),
                color = t.fg,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = ((11 * 1.3f)).sp),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space2))
        trailing()
    }
}

/** 开关行：标题 + 说明 + 右侧开关（原型 .srow + .switch）。 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .srow 实测 57（border-top 1 + padding 6+6 + 开关 44）；+1 补 Compose 不占位的
            // box 下边框，refresh 组总高才 = 原型 274
            .height(58.dp)
            .topHairline()
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = ((12 * 1.25f)).sp),
                color = t.fg,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = ((11 * 1.3f)).sp),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space2))
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 原型 s19 .switch 宽 52（AppSwitch 默认按 --switch-w 56 画触达域）：本屏收成 52，
            // 仍 ≥ 44dp 触达红线
            modifier = Modifier.width(52.dp).testTag(switchTag),
            contentDescription = title,
        )
    }
}