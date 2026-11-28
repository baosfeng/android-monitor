package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 额度设置页「恢复提醒」组内的行（原型 s19-recover）。
 *
 * 组标题 / 卡片底 / 组尾说明由设置页的 wrapper 提供（锚点 s19-recover 与 s19-test 在那层）；
 * 本文件只画行，左右 16dp 缩进由 [SettingsGroup] 统一给，行内不再叠加。
 *
 * 三态口径与主屏三态区同源（[QuotaAlertTriadSection]）：这里只解释颜色与出路，不重复算数据。
 */

/**
 * 「额度耗尽时提醒」（原型 s19-sw-dead）。
 *
 * 这是总开关：[com.bsfeng.monitor.quota.QuotaAlertNotifier.enabled] 关掉后只刷新不评估，
 * 恢复事件也不会产生。开关本身的语义（角色 / 开或关）由 [AppSwitch] 的 toggleable 承担。
 */
@Composable
internal fun AlertSwitchRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    AlertSwitchLayout(
        title = "额度耗尽时提醒",
        subtitle = "剩余 0% 或被接口标成 rate-limited 时发一条",
        checked = enabled,
        onCheckedChange = onChange,
        switchModifier = Modifier.testTag("s19-sw-dead"),
        contentDescription = "额度耗尽时提醒",
    )
}

/**
 * 「额度恢复时提醒」（原型 s19-sw-back）。
 *
 * 自包含读写的理由：这个开关的消费方只有通知器，为一个布尔值给 QuotaViewModel 加字段会让
 * 每个调用方都得透传一遍；这里直接读写通知器同一份 prefs，[onChange] 只用于设置页弹 toast。
 */
@Composable
internal fun RecoverAlertSwitchRow(onChange: ((Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember(context) { QuotaAlertPrefs(context) }
    var checked by remember { mutableStateOf(prefs.recoverEnabled) }
    AlertSwitchLayout(
        title = "额度恢复时提醒",
        subtitle = "从低于阈值回到阈值以上（通常是重置）时发一条",
        checked = checked,
        onCheckedChange = { on ->
            checked = on
            prefs.recoverEnabled = on
            onChange?.invoke(on)
        },
        switchModifier = Modifier.testTag("s19-sw-back"),
        contentDescription = "额度恢复时提醒",
    )
}

/** 开关行共用骨架：标题 + 副行 + 右侧开关（原型 .srow，min-height 48）。 */
@Composable
private fun AlertSwitchLayout(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchModifier: Modifier,
    contentDescription: String,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
        Spacer(Modifier.width(t.space3))
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            contentDescription = contentDescription,
            modifier = switchModifier.keyboardFocusRing(shape),
        )
    }
}

/**
 * 「快见底阈值」行（原型 s19-recover 第三行）。
 *
 * 原型只展示 badge「20% · 10% · 5%」；本项目还有余额型额度（DeepSeek），
 * 那一档的真实阈值是金额、可配置 —— 展示金额并点开弹窗，比照抄一个不可改的 badge 有用。
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
            .heightIn(min = 48.dp)
            .rowPressFeedback(interaction)
            .testTag("row.alert")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("快见底阈值", style = MaterialTheme.typography.labelLarge, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text(
                "余额型低于该值提醒一次；百分比型按 5% / 4% / 3% / 2% / 1% 各一次",
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.width(t.space2))
        Text(
            QuotaFormat.amount(cny, QuotaUnit.CNY) + " / " + QuotaFormat.amount(usd, QuotaUnit.USD),
            style = MaterialTheme.typography.labelSmall,
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
 * 三态说明（原型 s19-tristate）：解释三种颜色的含义与「点通知去哪」。
 *
 * 纯展示、不可点，所以不给 testTag 之外的交互语义；三态数据的真值在主屏 [QuotaAlertTriadSection]。
 */
@Composable
internal fun AlertTriadLegend() {
    val t = LocalDesignTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = t.space2)
            .testTag("s19-tristate"),
    ) {
        TriadLegendLine(t.warn, "快见底", "卡片转黄、组件同色；点通知直达该账号")
        Spacer(Modifier.height(t.space1))
        TriadLegendLine(t.danger, "已耗尽", "卡片转红并给「切主号 / 等重置」两条出路")
        Spacer(Modifier.height(t.space1))
        TriadLegendLine(t.success, "已恢复", "绿色回弹，附「从 4% → 96%」的变化")
    }
}

/** 一行图例：8dp 圆点 + 加粗状态名 + 说明（原型 .ts）。 */
@Composable
private fun TriadLegendLine(color: Color, title: String, detail: String) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(t.space2))
        Text(title, style = MaterialTheme.typography.labelSmall, color = t.fg)
        Text(" · " + detail, style = MaterialTheme.typography.labelSmall, color = t.muted)
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
            .heightIn(min = 48.dp)
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
            .padding(vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("通知权限", style = MaterialTheme.typography.labelLarge, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text("点通知可直接进入额度页", style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
        Badge(
            if (allowed) "已允许" else "未允许",
            tone = if (allowed) BadgeTone.Success else BadgeTone.Warn,
        )
    }
}
