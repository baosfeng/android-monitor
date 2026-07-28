package com.bsfeng.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorSpacing

@Composable
fun StatsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val grouped = remember(state.recentEvents) {
        state.recentEvents.groupBy { it.type::class.simpleName ?: "" }.entries.toList()
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(top = MonitorSpacing.sm)) {
        // 今日统计 — 总览
        item(key = "stats_title") {
            Text(
                "今日统计",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("事件总数",
                        style = MaterialTheme.typography.titleSmall)
                    val animatedEventCount by animateIntAsState(
                        targetValue = state.todayEventCount,
                        animationSpec = tween(durationMillis = 600),
                    )
                    Text(
                        "$animatedEventCount",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // App 使用时长排行
        item(key = "usage_header") {
            Text(
                "App 使用时长",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                if (state.appUsageStats.isEmpty()) {
                    Text(
                        "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                    )
                } else {
                    state.appUsageStats.forEachIndexed { index, stat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // App 名
                            Text(
                                text = stat.appName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 使用时长
                            Text(
                                text = if (stat.totalMinutes > 0) "${stat.totalMinutes} 分钟" else "<1 分钟",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(MonitorSpacing.md))
                            // 打开次数
                            Text(
                                text = "${stat.openCount} 次",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < state.appUsageStats.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = MonitorSpacing.lg),
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }

        // 屏幕事件时间线
        item(key = "screen_header") {
            Text(
                "屏幕状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                if (state.screenEvents.isEmpty()) {
                    Text(
                        "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                    )
                } else {
                    val displayScreens = state.screenEvents.reversed().take(10)
                    displayScreens.forEachIndexed { index, record ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatTime(record.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                text = record.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (index < displayScreens.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = MonitorSpacing.lg),
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }

        // 事件类型分布
        item(key = "type_header") {
            Text(
                "事件类型分布",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                grouped.forEach { (type, events) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(type, style = MaterialTheme.typography.bodyMedium)
                        Text("${events.size} 次",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
