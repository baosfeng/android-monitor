package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：跳过记录的详情抽屉 —— 应用名 + 命中方式徽标 + 五个字段 + 关闭/复制选择器。
// 负责「点一条跳过记录后从底部升起的面板」；遮罩由 BottomSheet 的窗口级浮层承担。

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClockSec
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 一条跳过记录的详情抽屉。
 */
@Composable
internal fun AdDetailSheet(
    record: SkipRecord,
    toast: ToastHostState,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val clipboard = LocalClipboardManager.current
// 遮罩由 BottomSheet 的窗口级全屏浮层承担（页内遮罩盖不住状态栏/手势条，且会叠成两层）
Box(Modifier.fillMaxSize()) {
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.displayName + " · 跳过成功",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Badge(record.method.label + "匹配")
        }
        Spacer(Modifier.height(t.space3))
        DetailRow("发生时间", formatClockSec(record.timestampMs), first = true)
        DetailRow("命中规则", record.selector.ifBlank { PLACEHOLDER })
        DetailRow("响应耗时", record.costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
        DetailRow(
            "结果",
            when (record.confirmed) {
                true -> "已进入正片 · 未误触"
                false -> "点击后广告仍在（未确认）"
                null -> "未复核"
            },
        )
        DetailRow("包名", record.packageName.ifBlank { PLACEHOLDER })
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(t.radiusPill),
            ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(record.selector))
                    toast.show("已复制选择器")
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(t.radiusPill),
            ) { Text("复制选择器", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
}
