package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的零散助手 —— 通知开关读取、还原流打开、进程运行时长、
// 备份时间与运行时长文案、应用内更新对话框、字节格式化，以及动效档位 / 触觉强度的文案映射。

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.update.UpdateManifest
import com.bsfeng.monitor.update.UpdateUiState
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统通知开关状态（本 App 只有事件提醒与传送通知，不做营销推送）。
 */
internal fun notificationsEnabled(context: Context): Boolean =
    runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }.getOrDefault(false)

// 无障碍状态文案已收敛到 ui/AccessibilityCopy.kt：状态行用 s09AccessibilityStatusText(...)
// （两段式，带运行时长）、仅状态场景用 accessibilityStatusText(...)、空态用 noEventEmptyText(...)。
// s01 / s02 / s05 三屏同一数据源（AccessibilityStatus 三重判定）与同一措辞。

// 本进程存活时长（毫秒）—— 见下方 processUptimeMs() 与 formatUptime()。
// 无障碍服务运行在 App 进程内、与服务实例同生命周期，而系统没有提供「服务绑定时刻」的公开 API
// （AccessibilityServiceInfo 只有能力位与包名，无时间戳），因此进程启动至今是当前可得的最近似值：
// 服务由系统绑定拉起进程时两者一致，用户先开 App、后授权时该值会略大于服务真实运行时长。
// 若要精确到「服务运行时长」，需在 MonitorAccessibilityService 记录 onServiceConnected 时间戳
// 并由状态层暴露（超出本文件范围）。文案因此写「本次启动 N 分钟」而不是「已运行」（ui/AccessibilityCopy.kt）。

/**
 * 打开所选备份包的字节流。
 *
 * 流的所有权交给 [com.bsfeng.monitor.backup.RestoreIntake]（内部 `use {}` 负责关闭），
 * 因此这里不关闭；lint 的 Recycle 检查无法跨 suspend lambda 看到关闭点，定向豁免。
 */
@SuppressLint("Recycle")
internal fun openRestoreStream(context: Context, uri: Uri): InputStream? =
    context.contentResolver.openInputStream(uri)

internal fun processUptimeMs(): Long =
    (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).coerceAtLeast(0L)

internal fun formatBackupTime(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/** 运行时长文案：≥1 小时用「X 小时 Y 分」（同原型），不足 1 分钟用「不足 1 分钟」。 */
internal fun formatUptime(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        totalMinutes > 0 -> "$minutes 分钟"
        else -> "不足 1 分钟"
    }
}

/** 本机名称长度上限（发现报文一行文案，过长会被对端列表截断）。 */
internal const val ALIAS_MAX_LENGTH = 24

/** 更新记录滚动区的高度上限：超过就滚动，不撑破对话框（一屏正常能看 10 行左右）。 */
private val UPDATE_CHANGELOG_MAX_HEIGHT = 260.dp

/** 发布流程没写更新记录时的回落文案。 */
internal const val UPDATE_CHANGELOG_FALLBACK = "包含功能改进与问题修复。"

/**
 * 更新记录 → 行列表：按行拆分，去掉行尾空白与空行，全空时回落到一行说明。
 *
 * 为什么要拆行：更新记录是「上一 tag → HEAD」的提交列表，最多 40 行；整段塞进一个 Text 时
 * 既不能滚动、长文本一次性布局也慢。拆成每行一个 Text 后，容器按固定高度滚动，行高由
 * typography.bodyMedium 统一接管。行内容不截断——截断会吞掉提交信息，超长行交给 Text 换行。
 */
internal fun updateChangelogLines(changelog: String): List<String> {
    val lines = changelog.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
    return lines.ifEmpty { listOf(UPDATE_CHANGELOG_FALLBACK) }
}

/** 应用内更新的检查 / 下载 / 安装对话框。 */
@Composable
internal fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (File) -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${state.manifest.versionName}") },
            text = {
                // 更新记录最多 40 行：有限高度 + 垂直滚动，避免超出对话框后滚不动
                Column(
                    Modifier
                        .heightIn(max = UPDATE_CHANGELOG_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    updateChangelogLines(state.manifest.changelog).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onDownload(state.manifest) }) { Text("下载更新") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在下载更新") },
            text = {
                Column {
                    val fraction = if (state.total > 0) {
                        (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Text(
                        if (state.total > 0) {
                            "${(fraction * 100).toInt()}%  ${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                        } else {
                            formatBytes(state.downloaded)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )

        is UpdateUiState.Downloaded -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新已下载") },
            text = { Text("点击安装完成更新。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { onInstall(state.apk) }) { Text("安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        else -> Unit
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[index])
}

// ── 动效与触感：文案映射（放文件尾，屏内多处引用） ──────────────────────

/** 动效档位的短标签（Chip 与右对齐数值共用）。 */
internal fun motionTierLabel(tier: MotionTier): String = when (tier) {
    MotionTier.FULL -> "完整"
    MotionTier.NORMAL -> "常规"
    MotionTier.REDUCED -> "精简"
}

/** 动效档位的一句话说明（给用户看的，不写技术术语）。 */
internal fun motionTierSubtitle(tier: MotionTier): String = when (tier) {
    MotionTier.FULL -> "全部动效，含逐个入场与回弹（默认）"
    MotionTier.NORMAL -> "保留转场与数字滚动，去掉逐个入场"
    MotionTier.REDUCED -> "关闭动效，响应最快"
}

/** 触觉强度档的短标签。 */
internal fun hapticStrengthLabel(strength: HapticStrength): String = when (strength) {
    HapticStrength.LIGHT -> "轻"
    HapticStrength.NORMAL -> "标准"
    HapticStrength.STRONG -> "强"
}

/** 触觉强度档的一句话说明。 */
internal fun hapticStrengthSubtitle(strength: HapticStrength): String = when (strength) {
    HapticStrength.LIGHT -> "震动偏轻，安静场合更合适"
    HapticStrength.NORMAL -> "默认力度"
    HapticStrength.STRONG -> "震动更明显，嘈杂环境更容易察觉"
}
