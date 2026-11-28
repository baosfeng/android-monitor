package com.bsfeng.monitor.ui

// 应用内更新的三态弹窗（发现新版本 / 下载中 / 已下载）。
//
// 从 ui/SettingsSupport.kt 拆出并重做：旧实现用 M3 AlertDialog + 一个 260dp 滚动区，
// 把发布清单里 20 条「- feat(scope): 标题」原样铺开 —— 更新一多就成了一堵墙（用户反馈）。
// 现在按类型分组、去掉提交前缀、只留正文，标题行给「版本 → 版本 · 发布日 · 项数」。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.DialogAction
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.opaquePanel
import com.bsfeng.monitor.update.UpdateManifest
import com.bsfeng.monitor.update.UpdateUiState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 更新记录滚动区高度上限：标题 / 副行 / 按钮之外再留 360dp，一屏能扫 10 条左右。 */
private val UPDATE_LIST_MAX_HEIGHT = 360.dp

/**
 * 发布副行：`当前 1.3.0 → 1.4.0 · 11-28 发布 · 12 项更新`。
 *
 * 发布日解析不出来就整段省掉（清单是发布流程生成的，缺字段不该让弹窗出现「无效日期」）。
 */
internal fun updateReleaseLine(
    currentVersion: String,
    manifest: UpdateManifest,
    itemCount: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val published = runCatching {
        Instant.parse(manifest.publishedAt).atZone(zone)
    }.getOrNull()
    val date = published?.let { String.format(Locale.US, "%02d-%02d 发布", it.monthValue, it.dayOfMonth) }
    return listOfNotNull(
        "当前 " + currentVersion + " → " + manifest.versionName,
        date,
        if (itemCount > 0) itemCount.toString() + " 项更新" else null,
    ).joinToString(" · ")
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
        is UpdateUiState.Available -> UpdateAvailableDialog(
            manifest = state.manifest,
            onDismiss = onDismiss,
            onDownload = { onDownload(state.manifest) },
        )

        is UpdateUiState.Downloading -> UpdateDownloadingDialog(state, onDismiss)

        is UpdateUiState.Downloaded -> ConfirmDialog(
            visible = true,
            title = "更新已下载",
            message = "点击安装完成更新。",
            confirmLabel = "安装",
            dismissLabel = "稍后",
            onConfirm = { onInstall(state.apk) },
            onDismiss = onDismiss,
        )

        else -> Unit
    }
}

/**
 * 「发现新版本」：标题 + 版本副行 + 分组后的更新记录 + 稍后 / 下载。
 *
 * 副行走 [ConfirmDialog] 的 message，正文走 extraContent —— 弹窗骨架与破坏性确认同源，
 * 不另写一套按钮与圆角。
 */
@Composable
private fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val changes = remember(manifest.changelog) { parseUpdateChangelog(manifest.changelog) }
    val groups = remember(changes) { groupUpdateChanges(changes) }
    ConfirmDialog(
        visible = true,
        title = "发现新版本 " + manifest.versionName,
        message = updateReleaseLine(BuildConfig.VERSION_NAME, manifest, changes.size),
        confirmLabel = "下载更新",
        dismissLabel = "稍后",
        onConfirm = onDownload,
        onDismiss = onDismiss,
        // 锚点与原型 s09-update / -later / -download 对齐（弹窗默认锚点不对外暴露）
        panelTag = "s09-update",
        dismissTag = "s09-update-later",
        confirmTag = "s09-update-download",
        extraContent = {
            if (groups.size > 1) {
                // 只有一组时不画分组标题：标题重复一次没有信息量
                UpdateChangelogBody(groups)
            } else {
                UpdateChangelogBody(groups, showGroupLabels = false)
            }
        },
    )
}

/**
 * 更新记录正文：按类型分组，组内一行一条。
 *
 * 滚动区高度封顶（[UPDATE_LIST_MAX_HEIGHT]），超过就在框内滚动 —— 更新再多也不撑破弹窗。
 */
@Composable
private fun UpdateChangelogBody(
    groups: List<UpdateChangeGroup>,
    showGroupLabels: Boolean = true,
) {
    val t = LocalDesignTokens.current
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = UPDATE_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .testTag("s09-update-list"),
            verticalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
                    if (showGroupLabels) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                group.kind.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                                color = t.accentText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(t.space2))
                            Text(
                                group.size.toString() + " 项",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = t.muted,
                            )
                        }
                    }
                    group.changes.forEach { change -> UpdateChangeRow(change.text) }
                }
            }
        }
        // 底部渐隐：列表被截断时给「下面还有」的信号（与原型 .s09-upd-scrollwrap::after 同款）
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, t.opaquePanel))),
        )
    }
}

/** 一条改动的正文（换行时与正文左沿对齐，不跟圆点跑）。 */
@Composable
private fun UpdateChangeRow(text: String) {
    val t = LocalDesignTokens.current
    Row(Modifier.fillMaxWidth()) {
        Text("·", style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.width(t.space2))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = t.fg2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 下载中：进度 + 一个取消键（没有确认键，避免「下载」被点两次）。 */
@Composable
private fun UpdateDownloadingDialog(state: UpdateUiState.Downloading, onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    val fraction = if (state.total > 0) {
        (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    AppDialog(visible = true, onDismiss = onDismiss) {
        Text("正在下载更新", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space3))
        Text(
            if (state.total > 0) {
                (fraction * 100).toInt().toString() + "%  " +
                    formatBytes(state.downloaded) + " / " + formatBytes(state.total)
            } else {
                formatBytes(state.downloaded)
            },
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space2))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(t.space4))
        DialogAction("取消", primary = false, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
