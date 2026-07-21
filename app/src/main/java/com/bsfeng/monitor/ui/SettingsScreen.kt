package com.bsfeng.monitor.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.section

@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 标题
        item(key = "title") {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // Section 1: 无障碍服务
        section(style = SectionStyle.InsetGrouped) {
            item {
                SettingsRow(
                    title = "无障碍服务",
                    subtitle = if (state.accessibilityEnabled) "✅ 已开启" else "❌ 未开启",
                    subtitleColor = if (state.accessibilityEnabled)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    trailing = {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(if (state.accessibilityEnabled) "管理" else "去开启")
                        }
                    }
                )
            }
        }

        // Section 2: 监控开关
        section(style = SectionStyle.InsetGrouped) {
            item {
                SettingsRow(
                    title = "监控开关",
                    subtitle = if (isPaused) "已暂停" else "运行中",
                    trailing = {
                        Button(onClick = { viewModel.togglePause() }) {
                            Text(if (isPaused) "恢复" else "暂停")
                        }
                    }
                )
            }
        }

        // Section 3: 电池优化
        section(style = SectionStyle.InsetGrouped) {
            item {
                SettingsRow(
                    title = "电池优化",
                    subtitle = "设为无限制以防止被系统关闭",
                    trailing = {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Text("设置")
                        }
                    }
                )
            }
        }

        // Section 4: 调试模式
        section(style = SectionStyle.InsetGrouped) {
            item {
                SettingsRow(
                    title = "调试模式",
                    subtitle = if (debugEnabled) "输出详细调试日志" else "已关闭",
                    trailing = {
                        Switch(
                            checked = debugEnabled,
                            onCheckedChange = { viewModel.toggleDebug() }
                        )
                    }
                )
            }
        }

        // Section 5: 关于
        section(style = SectionStyle.InsetGrouped) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("版本: 1.0.0", fontSize = 14.sp)
                    Text("设备 ID: ${viewModel.getDeviceId().take(8)}...",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("包名: com.bsfeng.monitor",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 13.sp, color = subtitleColor)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
