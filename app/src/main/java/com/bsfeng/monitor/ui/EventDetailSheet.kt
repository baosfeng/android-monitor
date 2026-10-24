package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时页的事件详情抽屉 —— EventDetailSheet（标题 + 类型徽标 + 五个字段 + 关闭/复制按钮）。
// 负责「点一条事件后从底部升起的详情面板」；遮罩由 BottomSheet 的窗口级全屏浮层承担。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.detailOr
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDurationMs
import com.bsfeng.monitor.ui.live.recordSubtitle
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 一条事件的详情抽屉（s02 点行后升起）。
 *
 * 遮罩由 [BottomSheet] 的窗口级全屏浮层承担（页内遮罩盖不住状态栏/手势条，且会叠成两层）。
 */
@Composable
fun EventDetailSheet(
    record: Record,
    toast: ToastHostState,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
        BottomSheet(
            visible = true,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.label.ifBlank { PLACEHOLDER },
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge(recordTypeLabel(record.type))
            }
            Spacer(Modifier.height(t.space3))
            DetailRow("发生时间", formatClockSec(record.timestamp), first = true)
            DetailRow("事件类型", recordTypeLabel(record.type) + " · " + record.action)
            DetailRow("持续时长", formatDurationMs(record.duration))
            // 页面标题 / 地址都是仅本地字段：有才显示，没有就整行省略（不写占位符）
            record.detail?.get("page_title")?.takeIf { it.isNotBlank() }?.let {
                DetailRow("页面标题", it)
            }
            record.detail?.get("address")?.takeIf { it.isNotBlank() }?.let {
                DetailRow("地址", it)
            }
            DetailRow("包名", record.detailOr("package_name"))
            DetailRow("来源", recordSubtitle(record))
            Spacer(Modifier.height(t.space4))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(t.radiusPill),
                ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(record.toString()))
                        toast.show("已复制事件 JSON")
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(t.radiusPill),
                ) { Text("复制 JSON", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
