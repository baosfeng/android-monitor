package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.notificationsAllowed
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.math.BigDecimal

/**
 * 额度预警总开关行。
 *
 * 开关本身是共享件（`AppSwitch`），交互口径要求的两件额外语义落在这一层：
 * 键盘焦点环 2dp（[keyboardFocusRing]，与行内 chip 同口径）与读屏报读「额度预警 + 已开启/已关闭」。
 * 开关切换后的 toast 由调用方（本屏）负责，避免共享件反向依赖页面文案。
 */
@Composable
internal fun AlertSwitchRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = t.space3, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("额度预警", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text(
                "剩余 5% / 4% / 3% / 2% / 1% 各提醒一次，周期重置后重新计数",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.width(t.space3))
        AppSwitch(
            checked = enabled,
            onCheckedChange = onChange,
            modifier = Modifier
                .testTag("s19-alert-switch")
                .keyboardFocusRing(shape)
                .semantics {
                    contentDescription = "额度预警"
                    stateDescription = if (enabled) "已开启" else "已关闭"
                    role = Role.Switch
                },
        )
    }
}

/**
 * 低余额阈值行：行尾显示当前两个币种的阈值，点击开阈值弹窗。
 *
 * [focusRequester] 由本屏传入，弹窗收起后把焦点送回本行（口径「弹窗关闭后焦点回本行」）；
 * 按压反馈走列表行的 0.99 + alpha .9（`RowPress.kt`），与开关的位移反馈区分。
 */
@Composable
internal fun LowBalanceRow(
    cny: BigDecimal,
    usd: BigDecimal,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .rowPressFeedback(interaction)
            .testTag("row.alert")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space3, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("低余额提醒", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text(
                "仅余额型 provider · 低于阈值提醒一次，充值回升后重新计数",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.width(t.space2))
        Text(
            "${QuotaFormat.amount(cny, QuotaUnit.CNY)} / " +
                QuotaFormat.amount(usd, QuotaUnit.USD),
            style = MaterialTheme.typography.bodySmall,
            color = t.fg2,
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "修改阈值",
            tint = t.muted,
        )
    }
}

/**
 * 通知权限行：只读展示 + 点开系统设置（权限申请由系统弹窗负责，这里不重复申请）。
 *
 * 角标在每次回到本页时重读（[LifecycleResumeEffect]）：用户从系统设置返回后立即看到新状态。
 */
@Composable
internal fun NotificationPermissionRow() {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = notificationsAllowed(context)
        onPauseOrDispose { }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .rowPressFeedback(interaction)
            .testTag("row.permission")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                // 系统设置不可达时原地无反应，不弹错误（口径边界）
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }
            .padding(horizontal = t.space3, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("通知权限", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text("点通知可直接进入额度页", style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        Badge(
            if (allowed) "已允许" else "未允许",
            tone = if (allowed) BadgeTone.Success else BadgeTone.Warn,
        )
    }
}
