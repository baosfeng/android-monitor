package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的浮层区 —— GalleryOverlays（弹窗/抽屉/Toast 三个入口）与 BottomSheetCard（演示抽屉内容）。负责 s12 的覆盖层陈列。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun GalleryOverlays(onDialog: () -> Unit, onSheet: () -> Unit, onToast: () -> Unit) {
    DemoSection("浮层 Overlays") {
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space2)) {
                AppButton("弹窗", onDialog, Modifier.weight(1f), AppButtonVariant.Ghost)
                AppButton("底部抽屉", onSheet, Modifier.weight(1f), AppButtonVariant.Ghost)
            }
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            AppButton("Toast 轻提示", onToast, variant = AppButtonVariant.Ghost, block = true)
        }
    }
}

@Composable
internal fun BottomSheetCard(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalDesignTokens.current
    var chip by remember { mutableIntStateOf(0) }
    com.bsfeng.monitor.ui.components.BottomSheet(visible = true, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = t.space12, height = t.space1).clip(RoundedCornerShape(t.radiusPill)).background(t.border))
        }
        Spacer(Modifier.height(t.space3))
        Text("选择记录类型", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text("底部抽屉从下方推入，220ms 进入 / 150ms 退出。", style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space3))
        ChipRow(listOf("前台切换", "广告跳过", "亮屏解锁", "充电"), chip, { chip = it })
        Spacer(Modifier.height(t.space4))
        AppButton("确定", onConfirm, block = true)
    }
}

// ── s12 各小节 ────────────────────────────────────────────────────────────
