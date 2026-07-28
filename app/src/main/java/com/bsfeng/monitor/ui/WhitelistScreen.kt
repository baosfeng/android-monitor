package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.MonitorSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    viewModel: WhitelistViewModel = viewModel()
) {
    val filteredApps by viewModel.filteredApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        IOSNavigationBar(
            title = "广告白名单",
            onBack = onBack,
        )

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("搜索应用...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            singleLine = true,
        )

        // 提示文字
        Text(
            text = "加入白名单的 App 将跳过开屏广告检测",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs),
        )

        // 应用列表
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppWhitelistRow(
                    app = app,
                    onToggle = { viewModel.toggleWhitelist(app.packageName) },
                )
                if (app != filteredApps.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppWhitelistRow(
    app: AppInfo,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 应用图标
        val iconBitmap = remember(app.icon) {
            app.icon?.toBitmap(48, 48)?.asImageBitmap()
        }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp),
            )
        } else {
            // 无图标时占位
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(MonitorSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.appName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(MonitorSpacing.md))

        // 应用名 + 包名
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 白名单开关
        Switch(
            checked = app.isInWhitelist,
            onCheckedChange = {
                onToggle()
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            },
        )
    }
}
