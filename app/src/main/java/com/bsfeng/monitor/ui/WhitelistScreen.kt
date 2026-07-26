package com.bsfeng.monitor.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    viewModel: WhitelistViewModel = viewModel()
) {
    val filteredApps by viewModel.filteredApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = { Text("广告白名单", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("返回", color = MaterialTheme.colorScheme.primary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("搜索应用...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )

        // 提示文字
        Text(
            text = "加入白名单的 App 将跳过开屏广告检测",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 应用列表
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppWhitelistRow(
                    app = app,
                    onToggle = { viewModel.toggleWhitelist(app.packageName) }
                )
                if (app != filteredApps.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppWhitelistRow(
    app: AppInfo,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 应用图标
        val iconBitmap = remember(app.icon) {
            app.icon?.toBitmap(48, 48)?.asImageBitmap()
        }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp)
            )
        } else {
            // 无图标时占位
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.take(1),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 应用名 + 包名
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                text = app.packageName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 白名单开关
        Switch(
            checked = app.isInWhitelist,
            onCheckedChange = { onToggle() }
        )
    }
}
