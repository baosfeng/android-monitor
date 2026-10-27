package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：Toast 提示 —— AppToast 组件、ToastHostState 状态持有者与 rememberToastHostState。负责「一次性轻提示」。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/** 入场位移：原型 \`@keyframes od-toast-in\` 的 \`translate3d(0,12px,0)\` → 自下 12dp 浮起。 */
private val TOAST_RISE: Dp = 12.dp

/** 退场位移：原型 \`@keyframes od-toast-out\` 的 \`translate3d(0,8px,0)\` → 淡出时下沉 8dp。 */
private val TOAST_DROP: Dp = 8.dp

/**
 * Toast：总时长 1.6s 自动消失（契约 §5）。
 *
 * 进出场取契约口径：入场 \`--dur-overlay-in\` 220ms + \`--ease-out\`，退场 \`--dur-overlay-out\` 150ms +
 * \`--ease-in\`，位移复用原型 od-toast-in / od-toast-out（12 / 8）。**停留窗口仍是 1.6s 总时长**，
 * 淡出占最后 150ms，故 [onDismiss] 在「1.6s − 退场时长」时触发，退场动画结束后组件才移除。
 * 动效档为 [MotionTier.REDUCED]（用户选「精简」或系统「动画时长缩放 = 0」）时不播动画，直接落终态。
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

    // 退场期间文案要留在屏幕上：消息置空后仍渲染最后一条非空文案。
    var lastMessage by remember { mutableStateOf(message) }
    SideEffect { if (message != null) lastMessage = message }

    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        // 停留窗口 = durationMs 总时长；退场占最后一段，因此提前退场时长触发 onDismiss。
        delay((durationMs - t.durOverlayOut).coerceAtLeast(0).toLong())
        onDismiss()
    }

    val motionEnabled = rememberMotionTier() != MotionTier.REDUCED
    val inMs = t.durOverlayIn
    val outMs = t.durOverlayOut
    val density = LocalDensity.current
    val risePx = with(density) { TOAST_RISE.roundToPx() }
    val dropPx = with(density) { TOAST_DROP.roundToPx() }

    // 契约 s21 §toast-host：全局 Toast 承载层锚点（常驻，toast 不可见时也在）
    Box(modifier = modifier.testTag("toast-host"), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = message != null,
            enter = if (motionEnabled) {
                fadeIn(tween(inMs, easing = t.easeOut)) +
                    slideInVertically(tween(inMs, easing = t.easeOut), initialOffsetY = { risePx })
            } else {
                EnterTransition.None
            },
            exit = if (motionEnabled) {
                fadeOut(tween(outMs, easing = t.easeIn)) +
                    slideOutVertically(tween(outMs, easing = t.easeIn), targetOffsetY = { dropPx })
            } else {
                ExitTransition.None
            },
        ) {
            Text(
                text = message ?: lastMessage.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = t.accentOn,
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.fg)
                    .padding(horizontal = t.space4, vertical = t.space2),
            )
        }
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
