package com.bsfeng.monitor.ui

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
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun AdScreen(
    modifier: Modifier = Modifier,
    adViewModel: AdViewModel = viewModel()
) {
    val records by adViewModel.skipRecords.collectAsState()
    val totalCount by adViewModel.totalCount.collectAsState()

    LaunchedEffect(Unit) {
        adViewModel.loadData()
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 头部统计
        section(style = SectionStyle.InsetGrouped) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "⏭️ 广告跳过记录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "累计跳过 $totalCount 次",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🛡️", fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "暂无跳过记录",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "打开有开屏广告的 App 即可自动跳过",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            section(style = SectionStyle.InsetGrouped) {
                items(records, key = { "${it.packageName}_${it.timestampMs}" }) { record ->
                    TimelineItem(record = record)
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 时间线左侧：时间 + 竖线
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                text = timeText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = dateText,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.width(12.dp))

        // 时间线竖线 + 圆点
        Box(
            modifier = Modifier.width(20.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            HorizontalDivider(
                modifier = Modifier.width(2.dp).padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Text(
                "⏭️",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        // 右侧内容
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.appName.ifBlank { record.packageName },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "已自动跳过开屏广告",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.selector.isNotBlank()) {
                Text(
                    text = "选择器: ${record.selector}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
