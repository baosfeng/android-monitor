package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 本文件只画行，左右 12dp 缩进由 wrapper 的 RecoverRow 给，行内不再叠加。
 *
 * 三态口径与主屏三态区同源（[QuotaAlertTriadSection]）：这里只解释颜色与出路，不重复算数据。
 */

// ── 原型 .srow 的行盒 ────────────────────────────────────────────────────
// 实测原型（agent-browser 量 .screen.s19 .srow）：padding 6/12、标题 12px·600·line-height 15、
// 副行 11px·400·line-height 14.3、两行 gap 4。开关行由 44 高的开关撑成 56（第二行 +1px 描边 = 57），
// 无开关的阈值行取 min-height 48。字阶里没有 11/12 这两档，按 checkUiStyle 的 copy 特例就地取
// （与 9sp 类型角标同一处理）。

/** 行标题：原型 .srow .t（--text-xs 12px / 600 / 1.25）。 */
@Composable
private fun alertRowTitleStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)

/** 行副行：原型 .srow .s（11px / 400 / 1.3）。 */
@Composable
private fun alertRowSubtitleStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.3.sp)

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
        modifier = Modifier.testTag("s19-sw-dead"),
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
        modifier = Modifier.testTag("s19-sw-back"),
        contentDescription = "额度恢复时提醒",
    )
}

/**
 * 开关行共用骨架：标题 + 副行 + 右侧开关。
 *
 * 行高不写死：开关 44 + 上下 6 = 56，与原型实测一致（原型 .srow 也是被开关撑到 56/57）。
 */
@Composable
private fun AlertSwitchLayout(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    contentDescription: String,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        // 原型 .srow 实测 56（44 高的开关 + 上下 6）；第二行原型 57 是它自带 1px 描边，
        // 实现侧描边由 wrapper 绘制不占位，差 1 在容差内。
        // 钉 56 而不是靠内容撑：AppSwitch 的 minimumInteractiveComponentSize 触达高是 48，
        // 48 + 12 会变成 60（多 4）。
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlertRowText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(t.space3))
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            contentDescription = contentDescription,
            modifier = modifier.keyboardFocusRing(shape),
        )
    }
}

/** 标题 + 副行两段（原型 .body：column + gap 4）。 */
@Composable
private fun AlertRowText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier) {
        Text(title, style = alertRowTitleStyle(), color = t.fg, maxLines = 1)
        Spacer(Modifier.height(4.dp)) // 原型 .srow .body{gap:var(--space-1)}
        Text(subtitle, style = alertRowSubtitleStyle(), color = t.muted, maxLines = 2)
    }
}

/**
 * 「快见底阈值」行（原型 s19-recover 第三行）。
 *
 * 原型只展示 badge「20% · 10% · 5%」；本项目还有余额型额度（DeepSeek），
 * 那一档的真实阈值是金额、可配置 —— 展示金额并点开弹窗，比照抄一个不可改的 badge 有用。
 *
 * 行高钉 48（原型该行无开关，min-height 48 即最终值）：内容 38 居中，多出的部分不再撑高。
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
            // 原型 .srow{min-height:48}：这一行没有 44 高的开关，48 就是最终高度
            .height(48.dp)
            .rowPressFeedback(interaction)
            .testTag("row.alert")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 副行必须单行：折成两行会把 48 撑到 66（原型同位置就是单行 13 字）
        AlertRowText(
            title = "快见底阈值",
            subtitle = "余额型低于该值提醒一次",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            QuotaFormat.amount(cny, QuotaUnit.CNY) + " / " + QuotaFormat.amount(usd, QuotaUnit.USD),
            style = alertRowSubtitleStyle(),
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
            .height(48.dp)
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
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlertRowText(
            title = "通知权限",
            subtitle = "点通知可直接进入额度页",
            modifier = Modifier.weight(1f),
        )
        Badge(
            if (allowed) "已允许" else "未允许",
            tone = if (allowed) BadgeTone.Success else BadgeTone.Warn,
        )
    }
}
