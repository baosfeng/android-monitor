package com.bsfeng.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Image as ComposeImage

/**
 * 主界面 — 美观的时间线事件流。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // 检测是否接近底部（还剩 3 项时触发加载更多）
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.hasMore && !state.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "行为记录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "今日共 ${state.todayEventCount} 条，已加载 ${state.recentEvents.size} 条",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 清除今日按钮
            if (state.todayEventCount > 0) {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.clearToday() }
                ) {
                    Text(
                        text = "清除今日",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                items = state.recentEvents,
                key = { _, record -> record.id }
            ) { index, record ->
                // 如果是当前活跃 App 的 OPENED 记录，传递实时时长
                val activeText = remember(record.id, state.activeAppName, state.activeAppDurationText) {
                    if (record.type is RecordType.APP_USAGE &&
                        record.action == "OPENED" &&
                        record.label == state.activeAppName)
                        state.activeAppDurationText else null
                }
                TimelineCard(record, activeDurationText = activeText)
            }

            // 加载更多指示器
            if (state.hasMore || state.isLoadingMore) {
                item(key = "loading_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "上拉加载更多",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========== Emoji & 颜色映射 ==========

private val emojiMap: Map<RecordType, (String) -> String> = mapOf(
    RecordType.SCREEN to { action ->
        when (action) { "ON" -> "🔆"; "OFF" -> "🌙"; "UNLOCKED" -> "🔓"
            "AD_SKIPPED" -> "⏭️"; else -> "🖥️" }
    },
    RecordType.APP_USAGE to { when (it) { "OPENED" -> "📱" else -> "📱" } },
    RecordType.CHARGING to { when (it) { "STARTED" -> "🔌" else -> "🔋" } },
    RecordType.BATTERY to { when (it) { "LOW" -> "⚠️"; "OKAY" -> "✅" else -> "🔋" } },
    RecordType.WIFI to { when (it) { "CONNECTED" -> "📶" else -> "📵" } },
    RecordType.AUDIO to { when (it) { "PLUGGED" -> "🎧" else -> "🔇" } },
    RecordType.PACKAGE to { when (it) { "ADDED" -> "📦"; "REMOVED" -> "🗑️" else -> "🔄" } },
    RecordType.POWER_SAVE to { when (it) { "ON" -> "🪫" else -> "🔋" } },
    RecordType.AIRPLANE to { when (it) { "ON" -> "✈️" else -> "🌐" } },
    RecordType.PHONE_STATE to { when (it) { "RINGING" -> "📞"; "OFFHOOK" -> "💬" else -> "📞" } },
    RecordType.LOCATION to { "📍" }
)

private fun getEmoji(type: RecordType, action: String): String =
    emojiMap[type]?.invoke(action) ?: "📝"

private fun getTypeColor(type: RecordType): Color = when (type) {
    RecordType.SCREEN -> Color(0xFF5B8DEF)
    RecordType.APP_USAGE -> Color(0xFF34C759)
    RecordType.CHARGING, RecordType.BATTERY -> Color(0xFFFF9500)
    RecordType.WIFI -> Color(0xFF007AFF)
    RecordType.AUDIO -> Color(0xFFAF52DE)
    RecordType.PACKAGE -> Color(0xFFFF2D55)
    RecordType.POWER_SAVE -> Color(0xFF8E8E93)
    RecordType.AIRPLANE -> Color(0xFF5856D6)
    RecordType.PHONE_STATE -> Color(0xFF30B0C7)
    RecordType.LOCATION -> Color(0xFF4CD964)
    else -> Color(0xFF8E8E93)
}

// ========== App 图标加载 ==========

@Composable
private fun AppIcon(packageName: String?, modifier: Modifier = Modifier.size(20.dp)) {
    if (packageName == null) return
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // 异步加载图标，避免 PackageManager IPC 阻塞主线程
    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val w = (drawable.intrinsicWidth).coerceAtLeast(1)
                val h = (drawable.intrinsicHeight).coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap = bmp
            } catch (_: Exception) { bitmap = null }
        }
    }
    if (bitmap != null) {
        val icon = bitmap!!
        ComposeImage(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Text("📱", fontSize = 16.sp)
    }
}

// ========== 时间线卡片 ==========

@Composable
private fun TimelineCard(record: Record, activeDurationText: String? = null) {
    val emoji = getEmoji(record.type, record.action)
    val typeColor = getTypeColor(record.type)
    val detail = record.detail

    val appPkg: String? = if (record.type is RecordType.APP_USAGE)
        detail?.get("package_name") else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji 圆点
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 14.sp)
            }

            Spacer(Modifier.width(8.dp))

            // 时间
            Text(
                text = formatTime(record.timestamp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )

            // App 图标（仅 APP_USAGE）
            if (appPkg != null) {
                Spacer(Modifier.width(4.dp))
                AppIcon(appPkg)
                Spacer(Modifier.width(4.dp))
            }

            // Label（可伸缩，自动省略）
            Text(
                text = record.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            // 时长：优先使用活跃时长文字，其次用记录的 duration
            val displayDuration = activeDurationText ?: record.duration?.let { dur ->
                "${dur / 60000}分${(dur % 60000) / 1000}秒"
            }
            if (displayDuration != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = displayDuration,
                    fontSize = 11.sp,
                    color = if (activeDurationText != null) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            // 类型标签
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = record.type::class.simpleName?.take(4) ?: "",
                    fontSize = 9.sp,
                    color = typeColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(ms: Long): String {
    @Suppress("MagicNumber")
    val d = Date(ms)
    return timeFormatter.format(d)
}
