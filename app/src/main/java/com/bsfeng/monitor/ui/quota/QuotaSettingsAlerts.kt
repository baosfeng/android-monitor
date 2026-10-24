package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
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
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.math.BigDecimal

@Composable
internal fun AlertSwitchRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalDesignTokens.current
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
        AppSwitch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun LowBalanceRow(cny: BigDecimal, usd: BigDecimal, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
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

/** 通知权限状态：只读展示 + 点开系统设置（权限申请由系统弹窗负责，这里不重复申请）。 */
@Composable
internal fun NotificationPermissionRow() {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val allowed = remember(context) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic(HapticTone.LIGHT)
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

