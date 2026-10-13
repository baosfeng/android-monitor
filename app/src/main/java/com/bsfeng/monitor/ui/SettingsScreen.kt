package com.bsfeng.monitor.ui

import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.ui.theme.InsetGroupedSection
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.SettingsRow

/**
 * 无障碍服务状态文案。
 *
 * 「已开启但服务未连接」（刚 install -r、系统 UI 重建等场景）单独提示，
 * 避免用户误以为服务没生效而反复去系统设置里开关。
 */
private fun accessibilitySubtitle(state: MonitorState): String = when {
    state.accessibilityServiceRunning -> "✅ 已开启"
    state.accessibilityEnabled -> "✅ 已开启（服务未连接，稍候或重进 App 生效）"
    else -> "❌ 未开启"
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToSkipRules: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()

    // 从系统「无障碍」设置页返回时立即刷新状态（不看 3 秒轮询的运气）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        onPauseOrDispose { }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 标题
        item(key = "title") {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = MonitorSpacing.lg,
                    vertical = MonitorSpacing.md
                ),
            )
        }

        // Section 1: 无障碍服务
        item {
            InsetGroupedSection {
                SettingsRow(
                    title = "无障碍服务",
                    subtitle = accessibilitySubtitle(state),
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
        item {
            InsetGroupedSection {
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
        item {
            InsetGroupedSection {
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

        // Section 4: 开屏广告跳过
        item {
            InsetGroupedSection {
                SettingsRow(
                    title = "开屏广告跳过",
                    subtitle = "管理自动跳过规则",
                    trailing = {
                        Button(onClick = onNavigateToSkipRules) {
                            Text("管理")
                        }
                    }
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SettingsRow(
                    title = "广告白名单",
                    subtitle = "选择不检测广告的 App",
                    trailing = {
                        Button(onClick = onNavigateToWhitelist) {
                            Text("管理")
                        }
                    }
                )
            }
        }

        // Section 5: 调试模式
        item {
            InsetGroupedSection {
                SettingsRow(
                    title = "调试模式",
                    subtitle = if (debugEnabled) "输出详细调试日志" else "已关闭",
                    trailing = {
                        Switch(
                            checked = debugEnabled,
                            onCheckedChange = {
                                viewModel.toggleDebug()
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            }
                        )
                    }
                )
            }
        }

        // Section 6: 关于
        item {
            InsetGroupedSection {
                Column(modifier = Modifier.padding(MonitorSpacing.lg)) {
                    Text(
                        "关于",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(MonitorSpacing.xs))
                    Text(
                        "版本: 1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "设备 ID: ${viewModel.getDeviceId().take(8)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "包名: com.bsfeng.monitor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
