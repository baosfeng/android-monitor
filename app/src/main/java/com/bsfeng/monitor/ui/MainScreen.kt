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

private fun formatTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
