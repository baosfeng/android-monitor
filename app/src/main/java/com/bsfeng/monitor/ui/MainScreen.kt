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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.MonitorIconSize
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius
import com.bsfeng.monitor.ui.theme.toEventColor
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

    // 活跃 App 出现时自动滚动到顶部（让绿色卡片可见）
    LaunchedEffect(state.activeAppName) {
        if (state.activeAppName != null) {
            listState.animateScrollToItem(0)
        }
    }
    // 新事件产生时也滚动到顶部
    var lastEventCount by remember { mutableStateOf(state.todayEventCount) }
    LaunchedEffect(state.todayEventCount) {
        if (state.todayEventCount > lastEventCount && lastEventCount > 0) {
            listState.animateScrollToItem(0)
        }
        lastEventCount = state.todayEventCount
    }

    // 检测是否接近底部（还剩 3 项时触发加载更多）
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.hasMore && !state.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    Column(modifier = modifier.padding(MonitorSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "行为记录",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(MonitorSpacing.xxs))
                Text(
                    text = "今日共 ${state.todayEventCount} 条，已加载 ${state.recentEvents.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 清除今日按钮
            if (state.todayEventCount > 0) {
                TextButton(
                    onClick = { viewModel.clearToday() }
                ) {
                    Text(
                        text = "清除今日",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(MonitorSpacing.md))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(MonitorSpacing.sm.plus(2.dp)),
        ) {
            // ===== 当前活跃 App（置顶） =====
            if (state.activeAppName != null) {
                item(key = "active_app") {
                    ActiveAppCard(
                        appName = state.activeAppName ?: "",
                        durationText = state.activeAppDurationText ?: ""
                    )
                }
            }

    // ===== 历史记录列表（过滤掉与当前活跃 App 重复的最新一条） =====
            val hiddenIds = if (state.activeAppName != null) {
                val firstMatch = state.recentEvents.indexOfFirst {
                    it.type is RecordType.APP_USAGE && it.label == state.activeAppName
                }
                if (firstMatch >= 0) setOf(state.recentEvents[firstMatch].id) else emptySet()
            } else emptySet()
            itemsIndexed(
                items = state.recentEvents.filter { it.id !in hiddenIds },
                key = { _, record -> record.id }
            ) { _, record ->
                TimelineCard(record)  // 历史记录时长固定不变
            }

            // ===== 加载更多指示器 =====
            if (state.hasMore || state.isLoadingMore) {
                item(key = "loading_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MonitorSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(MonitorIconSize.md),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = "上拉加载更多",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========== Emoji 映射 ==========

private val emojiMap: Map<RecordType, (String) -> String> = mapOf(
    RecordType.SCREEN to { action ->
        when (action) { "OFF" -> "🌙"; "UNLOCKED" -> "🔓"
            "AD_SKIPPED" -> "⏭️"; else -> "🖥️" }
    },
    RecordType.APP_USAGE to { "📱" },
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

// ========== App 图标加载 ==========

@Composable
private fun AppIcon(packageName: String?, modifier: Modifier = Modifier.size(MonitorIconSize.md)) {
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
            modifier = modifier.clip(RoundedCornerShape(Radius.xs)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text("📱", fontSize = MaterialTheme.typography.bodyMedium.fontSize)
    }
}

// ========== 时间线卡片 ==========

@Composable
private fun TimelineCard(record: Record) {
    val emoji = getEmoji(record.type, record.action)
    val typeColor = record.type.toEventColor()
    val detail = record.detail

    val appPkg: String? = if (record.type is RecordType.APP_USAGE)
        detail?.get("package_name") else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonitorSpacing.xs),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonitorSpacing.sm.plus(2.dp), vertical = MonitorSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Emoji 圆点
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.width(MonitorSpacing.sm))

            // 时间
            Text(
                text = formatTime(record.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp),
            )

            // App 图标（仅 APP_USAGE）
            if (appPkg != null) {
                Spacer(Modifier.width(MonitorSpacing.xs))
                AppIcon(appPkg)
                Spacer(Modifier.width(MonitorSpacing.xs))
            }

            // Label（可伸缩，自动省略）
            Text(
                text = record.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            // 时长（历史记录固定不变）
            val displayDuration = record.duration?.let { dur ->
                "${dur / 60000}分${(dur % 60000) / 1000}秒"
            }
            if (displayDuration != null) {
                Spacer(Modifier.width(MonitorSpacing.sm.minus(2.dp)))
                Text(
                    text = displayDuration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }

            // 类型标签
            Spacer(Modifier.width(MonitorSpacing.sm.minus(2.dp)))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(typeColor.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = MonitorSpacing.xxs),
            ) {
                Text(
                    text = record.type::class.simpleName?.take(4) ?: "",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = typeColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ========== 当前活跃 App 卡片（置顶） ==========

@Composable
private fun ActiveAppCard(appName: String, durationText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonitorSpacing.xs),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonitorSpacing.sm.plus(2.dp), vertical = MonitorSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 绿色活跃指示圆点
            Box(
                modifier = Modifier
                    .size(MonitorIconSize.sm)
                    .clip(CircleShape)
                    .background(IOSColors.green)
            )
            Spacer(Modifier.width(MonitorSpacing.sm))

            // App 名称
            Text(
                text = appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            // "使用中" 标签
            Text(
                text = "使用中",
                style = MaterialTheme.typography.labelSmall,
                color = IOSColors.green,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(MonitorSpacing.xs))
            // 时长（绿色）
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall,
                color = IOSColors.green,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(ms: Long): String {
    @Suppress("MagicNumber")
    val d = Date(ms)
    return timeFormatter.format(d)
}
