package com.bsfeng.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 窗口级浮层基元：**所有**模态浮层（底部抽屉 / 居中弹窗 / 纯遮罩）的唯一承载方式。
 *
 * ## 为什么必须是窗口级
 * 宿主 MainActivity 已 enableEdgeToEdge，但 Scaffold 未接管 contentWindowInsets（动它会牵动所有页面），
 * 于是**组合在页面里的**浮层只能画到系统栏之内 —— 真机表现是「遮罩盖不住状态栏与手势条、顶部仍是亮的」，
 * 不像模态，系统栏区域的触摸还会落到下层页面。走 [Dialog] 窗口则天然覆盖整屏（含状态栏 / 手势条），
 * 也把触摸整体拦在浮层外。
 *
 * ## 遮罩
 * `--scrim` 只有 34% 不透明度，单层盖不住下层内容（真机反馈：背景表单清清楚楚）。这里叠两层，
 * 合成后 ≈ 1 − (1 − 0.34)² ≈ 0.56 —— 这是**对原型 0.34 的有意偏离**：模态浮层是操作面板，
 * 可读性优先于装饰语言。全项目只保留这一处遮罩绘制。
 *
 * ## 交互
 * 点遮罩 = [onDismiss]；返回键 / 系统返回手势 = Dialog 的 onDismissRequest = [onDismiss]
 * （Compose Dialog 默认消费返回事件，调用方的页面级 BackHandler 只作幂等兜底）。
 *
 * ## 面板不透明
 * glassmorphism 主题的 `--surface-warm` 是 rgba(238,246,255,0.72)，半透明会让下层按钮透出来。
 * 面板底色统一用 `t.surfaceWarm.compositeOver(t.surface)` 合成到不透明（同样是对该主题语言的
 * **有意偏离**）。本基元只负责遮罩与窗口，面板几何由调用方在 [content] 里自绘。
 *
 * ## 用法
 * ```
 * WindowOverlay(visible = show, onDismiss = { show = false }) {
 *     // 这里是 BoxScope：贴底用 Modifier.align(Alignment.BottomCenter)，
 *     // 居中用 Modifier.align(Alignment.Center)
 *     Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { ... }
 * }
 * ```
 *
 * 贴底面板记得加 `navigationBarsPadding()`：底色铺到屏幕底（延伸到手势条之下），内容上抬，
 * 否则「确定 / 关闭」会被手势条压住。
 */
@Composable
fun WindowOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(modifier.fillMaxSize()) {
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
}
