package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：Toast 提示 —— AppToast 组件、ToastHostState 状态持有者与 rememberToastHostState。负责「一次性轻提示」。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/**
 * Toast：1.6s 自动消失（契约 §5）。
 *
 * 有意**不**走 [WindowOverlay]：窗口级浮层会独占触摸（Dialog 窗口收到的事件不会再传给下层
 * Activity），提示出现的 1.6s 里用户点任何东西都会失效 —— 对「操作完成」这种非模态提示是不可接受的
 * 回归。它也不绘制遮罩，所以不属于「第二套遮罩实现」。
 */
@Composable
fun AppToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Int = 1600,
) {
    val t = LocalDesignTokens.current
    if (message == null) return
    LaunchedEffect(message) {
        delay(durationMs.toLong())
        onDismiss()
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = t.accentOn,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.fg)
                .padding(horizontal = t.space4, vertical = t.space2),
        )
    }
}

/** 简单 toast 状态（配合 AppToast 使用）。 */
class ToastHostState {
    var message by mutableStateOf<String?>(null)
        private set

    fun show(text: String) {
        message = text
    }

    fun clear() {
        message = null
    }
}

@Composable
fun rememberToastHostState(): ToastHostState = remember { ToastHostState() }
