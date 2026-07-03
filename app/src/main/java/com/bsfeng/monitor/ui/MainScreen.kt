package com.bsfeng.monitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.util.DiagnosticCounters

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 状态栏
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isPaused) "⏸ 已暂停" else "⚫ 监控中",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = { viewModel.togglePause() }) {
                        Text(if (isPaused) "恢复" else "暂停")
                    }
                }
                Spacer(Modifier.height(8.dp))
                val accStatus = if (state.accessibilityEnabled) "✅ 无障碍已开启" else "❌ 无障碍未开启"
                Text(accStatus, fontSize = 13.sp)
                val serverStatus = when (state.serverConnected) {
                    null -> "⏳ 正在检测服务器..."
                    true -> "✅ 服务器已连接"
                    false -> "❌ 服务器无法连接"
                }
                Text(serverStatus, fontSize = 13.sp,
                    color = when (state.serverConnected) {
                        false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                Spacer(Modifier.height(4.dp))
                Text("今日事件: ${state.todayEventCount}  待上报: ${state.unsentCount}")
                Text("解锁次数: ${state.todayUnlockCount}")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { viewModel.uploadNow() },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("↑ 立即上报", fontSize = 12.sp)
                    }
                }

                // ========== 诊断面板 ==========
                DiagnoseSection(
                    viewModel = viewModel,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // 最近事件列表
        Text(
            "最近事件",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.recentEvents) { event ->
                EventCard(event = event)
            }
        }
    }
}

@Composable
fun EventCard(event: com.bsfeng.monitor.data.EventEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(EventType.getChineseName(event.eventType), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    formatTime(event.timestampMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.extraJson != "{}" && event.extraJson.isNotBlank()) {
                Text(
                    event.extraJson.take(80),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 应用内诊断面板 — 无需 adb 即可查看数据流各环节状态
 */
@Composable
private fun DiagnoseSection(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = {
                expanded = !expanded
                DiagnosticCounters.lastRefreshTimestamp = System.currentTimeMillis()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (expanded) "▼ 诊断面板" else "▶ 诊断面板 (点击展开)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (expanded) {
            DiagnosticCounters.let { d ->
                val accentColor = MaterialTheme.colorScheme.error

                // 无障碍服务
                Text("【无障碍服务】", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val accColor = if (d.accessibilityEventCount > 0) MaterialTheme.colorScheme.onSurface else accentColor
                Text("  收到事件: ${d.accessibilityEventCount} 次", fontSize = 11.sp, color = accColor)
                if (d.lastAccessibilityPackage.isNotBlank()) {
                    Text("  最近包名: ${d.lastAccessibilityPackage}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  最近类型: ${d.lastAccessibilityEventType}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("  被系统UI过滤: ${d.accessibilityFilteredCount} 次", fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))

                // EventCollector
                Text("【EventCollector】", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val emitColor = if (d.emitCalledCount > 0) MaterialTheme.colorScheme.onSurface else accentColor
                Text("  emit 被调用: ${d.emitCalledCount} 次", fontSize = 11.sp, color = emitColor)
                if (d.lastEmittedEventType.isNotBlank()) {
                    Text("  最近事件: ${d.lastEmittedEventType}", fontSize = 11.sp)
                }
                Text("  写入成功: ${d.emitSuccessCount}  失败: ${d.emitFailCount}", fontSize = 11.sp,
                    color = if (d.emitFailCount > 0) accentColor else MaterialTheme.colorScheme.onSurface)
                if (d.lastErrorMessage.isNotBlank()) {
                    Text("  异常: ${d.lastErrorMessage.take(80)}", fontSize = 10.sp, color = accentColor)
                }
                Spacer(Modifier.height(4.dp))

                // DatabaseHelper
                Text("【DatabaseHelper】", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val dbColor = if (d.dbInsertCalledCount > 0) MaterialTheme.colorScheme.onSurface else accentColor
                Text("  insert 被调用: ${d.dbInsertCalledCount} 次", fontSize = 11.sp, color = dbColor)
                Text("  成功: ${d.dbInsertSuccessCount}  冲突: ${d.dbInsertConflictCount}  失败: ${d.dbInsertFailCount}", fontSize = 11.sp,
                    color = if (d.dbInsertFailCount > 0) accentColor else MaterialTheme.colorScheme.onSurface)
                if (d.dbLastException.isNotBlank()) {
                    Text("  异常: ${d.dbLastException.take(80)}", fontSize = 10.sp, color = accentColor)
                }
                Spacer(Modifier.height(4.dp))

                // 上传到服务器
                Text("【上传到服务器】", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val uploadColor = if (d.uploadSuccessCount > 0) MaterialTheme.colorScheme.onSurface else accentColor
                Text("  上传尝试: ${d.uploadAttemptCount} 次", fontSize = 11.sp, color = uploadColor)
                Text("  成功: ${d.uploadSuccessCount}  失败: ${d.uploadFailCount}", fontSize = 11.sp,
                    color = if (d.uploadFailCount > 0) accentColor else MaterialTheme.colorScheme.onSurface)
                if (d.lastUploadResult.isNotBlank()) {
                    Text("  上次结果: ${d.lastUploadResult.take(80)}", fontSize = 10.sp,
                        color = if (d.uploadFailCount > 0) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.lastUploadError.isNotBlank()) {
                    Text("  错误: ${d.lastUploadError.take(80)}", fontSize = 10.sp, color = accentColor)
                }
                Text("  有 Token: ${d.isTokenAvailable}", fontSize = 10.sp,
                    color = if (!d.isTokenAvailable) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // 危险操作：重发今日数据（隐匿在诊断面板内防误触）
                TextButton(
                    onClick = { viewModel.resendTodayData() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("↻ 重发今日全部数据到服务器", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("提示: 切换 App 后展开本面板查看最新计数; 点击「立即上报」触发上传", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
