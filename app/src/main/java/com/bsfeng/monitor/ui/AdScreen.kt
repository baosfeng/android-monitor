package com.bsfeng.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdScreen(
    modifier: Modifier = Modifier,
    adViewModel: AdViewModel = viewModel()
) {
    val records by adViewModel.skipRecords.collectAsState()
    val totalCount by adViewModel.totalCount.collectAsState()
    val confirmedCount by adViewModel.confirmedCount.collectAsState()

    LaunchedEffect(Unit) {
        adViewModel.loadData()
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 头部统计
        item {
            InsetGroupedCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "⏭️ 广告跳过记录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(MonitorSpacing.xs))
                        val animatedCount by animateIntAsState(
                            targetValue = totalCount,
                            animationSpec = tween(durationMillis = 600),
                        )
                        Text(
                            text = "累计跳过 " + animatedCount + " 次" +
                                if (confirmedCount > 0) "（已确认 " + confirmedCount + " 次）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (records.isNotEmpty()) {
                        OutlinedButton(onClick = { adViewModel.clearAll() }) {
                            Text("清空")
                        }
                    }
                }
            }
        }

        if (records.isEmpty()) {
            // 空状态
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🛡️",
                            style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(MonitorSpacing.lg))
                        Text(
                            "暂无跳过记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MonitorSpacing.xs))
                        Text(
                            "打开有开屏广告的 App 即可自动跳过",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        } else {
            // 时间线列表
            item(key = "timeline_header") {
                Text(
                    "时间线",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                )
            }
            item {
                InsetGroupedCard {
                    records.forEach { record ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically { it / 6 },
                        ) {
                            TimelineItem(record = record)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(record: SkipRecord) {
    val timeText = remember(record.timestampMs) { formatTimestamp(record.timestampMs) }
    val dateText = remember(record.timestampMs) { formatDate(record.timestampMs) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
        verticalAlignment = Alignment.Top,
    ) {
        // 时间线左侧：时间 + 竖线
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(52.dp),
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        Spacer(Modifier.width(MonitorSpacing.md))

        // 时间线竖线 + 圆点
        Box(
            modifier = Modifier.width(20.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            HorizontalDivider(
                modifier = Modifier.width(2.dp).padding(top = MonitorSpacing.sm),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            )
            Text(
                "⏭️",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = MonitorSpacing.xxs),
            )
        }

        Spacer(Modifier.width(MonitorSpacing.sm))

        // 右侧内容
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.appName.ifBlank { record.packageName },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(MonitorSpacing.xxs))
            Text(
                text = when (record.confirmed) {
                    true -> "已自动跳过开屏广告（已确认）"
                    false -> "已点击跳过按钮，但广告仍在（未确认）"
                    null -> "已自动跳过开屏广告"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.selector.isNotBlank()) {
                Text(
                    text = "选择器: ${record.selector}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}

private fun formatDate(ms: Long): String {
    val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
    return sdf.format(Date(ms))
}
