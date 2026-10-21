package com.bsfeng.monitor.ui.foundation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 全屏遮罩浮层：底衬 + 遮罩都画到屏幕边（含状态栏与手势条），不经过 Scaffold 的 inset。
 *
 * ## 何时用
 * Composable 内部的整层浮层（跟随宿主窗口、需要跟随页面转场）用本基元；
 * 需要独立窗口（数据弹窗 / 底部抽屉，要压过 systemui 的状态栏窗口）时用
 * `ui/components/WindowOverlay.kt`。
 *
 * ## 叠法与点击
 * 底层 `pageBackdrop()` 铺满窗口 → 叠两层 `--scrim`（34% 单层盖不住下层，双层合成 ≈56%，
 * 与 `WindowOverlay` 同一口径）→ 上层放 [content]。点遮罩 = [onDismiss]，返回键 = [onDismiss]。
 * 浮层内容自己负责 `statusBarsPadding()` / `navigationBarsPadding()` 避让。
 *
 * ## 用法
 * ```
 * ImmersiveScrim(visible = show, onDismiss = { show = false }) {
 *     Column(Modifier.fillMaxSize()) { ... }
 * }
 * ```
 */
@Composable
fun ImmersiveScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    BackHandler { onDismiss() }
    Box(modifier.fillMaxSize().pageBackdrop()) {
        // 遮罩：铺满整屏（含状态栏与手势条），点即关。无 ripple（大面积点击反馈会糊成一片）
        Box(
            Modifier
                .fillMaxSize()
                .background(t.scrim)
                .background(t.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        content()
    }
}
