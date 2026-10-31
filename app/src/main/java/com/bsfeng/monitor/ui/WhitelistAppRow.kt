package com.bsfeng.monitor.ui

// 从 ui/WhitelistScreen.kt 拆出：白名单的应用行 —— AppRow（真实图标 + 应用名 + 包名 + 开关，无图标回落首字）。
// 入场动画不在这里做：由调用方注入到 StackRow 的 modifier，与 RuleRow / SkipRecordRow 同一分工。

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.theme.LocalDesignTokens


/**
 * 白名单应用行：真实图标（无图标回落首字） + 应用名 + 包名 + 开关。
 *
 * 入场动画（stagger）不在这里做：它由调用方（LazyColumn 的 item）注入到 StackRow 的 modifier 上，
 * 与 SkipRuleScreen 的 RuleRow / AdScreen 的 SkipRecordRow 同一分工。
 */
@Composable
internal fun AppRow(app: AppInfo, onToggle: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconBitmap = remember(app.icon) {
            app.icon?.toBitmap(72, 72)?.asImageBitmap()
        }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(t.space8).clip(RoundedCornerShape(t.radiusSm)),
            )
        } else {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.appName.take(1), style = MaterialTheme.typography.titleSmall, color = t.fg2)
            }
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space3))
        // 行标题是兄弟节点：开关单独取焦点时要自带应用名
        AppSwitch(app.isInWhitelist, { onToggle() }, contentDescription = app.appName + " 白名单")
    }
}
