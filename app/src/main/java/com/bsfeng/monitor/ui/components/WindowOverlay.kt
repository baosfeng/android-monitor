package com.bsfeng.monitor.ui.components

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
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
 * ## 真机上还差一步：Dialog 窗口自己要声明系统栏透明
 * AOSP 模拟器上遮罩能盖住状态栏，小米 14（HyperOS）上却盖不住 —— 状态栏在手机上不是应用窗口的
 * 装饰，而是 systemui 的**独立窗口**（`dumpsys window` 里 `Window{StatusBar} mBaseLayer=151000`），
 * 常驻在应用窗口之上；HyperOS 另有常驻的 DynamicIslandWindow（灵动岛）。
 * 而 `MainActivity` 的 `enableEdgeToEdge(TRANSPARENT)` 只作用于 **Activity 窗口**，Compose 的
 * [Dialog] 是**独立窗口**，默认按主题绘制不透明系统栏底 → 状态栏那条压在遮罩之上。
 * 因此见 [ImmersiveDialogWindow]：给 Dialog 窗口自己设系统栏透明 + 深色系统栏图标 + 允许进挖孔区。
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
        // Dialog 窗口级参数（系统栏透明 / 关掉自带 dim / 允许进挖孔区），见函数注释
        ImmersiveDialogWindow()
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

/**
 * 把承载本浮层的 **Dialog 窗口**自己调成沉浸式。
 *
 * 为什么不能只靠 Activity：手机（HyperOS / MIUI）上状态栏是 systemui 的独立窗口，层级高于所有
 * 应用窗口（`Window{StatusBar} mBaseLayer=151000`），`enableEdgeToEdge` 只改了 Activity 窗口的
 * 系统栏，Dialog 作为独立窗口仍按主题画不透明系统栏底，于是那一条永远亮在遮罩之上。
 * 这几行只为 Dialog 窗口补齐同样的声明。
 *
 * 逐条说明（都是幂等操作，窗口销毁即失效，无需恢复）：
 * 1. `setDecorFitsSystemWindows(false)`：[DialogProperties.decorFitsSystemWindows] 已经设过一次，
 *    这里显式再设，防某些 ROM 把窗口退回 insets 约束；
 * 2. 关掉窗口自带 dim：遮罩由我们自己画，平台的 dim 会让背景双重变暗；
 * 3. `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` + 系统栏色透明：让 systemui 用**本窗口**声明的颜色画系统栏；
 * 4. 系统栏图标明暗：按遮罩叠两层后的实际底色选（浅色主题 → 深色图标，深色主题自动切回浅色）；
 * 5. 窗口铺满 + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`：小米 14 顶部挖孔 insets = 134px，
 *    不让内容避开它，遮罩才真的是「一整屏」。
 *
 * ⚠️ Compose 侧不需要再消费 insets：本组件从不调用 `windowInsetsPadding` / `safeDrawingPadding`，
 * 所以 `Box(fillMaxSize)` 直接铺满（含挖孔区）。
 */
@Composable
private fun ImmersiveDialogWindow() {
    val view = LocalView.current
    val t = LocalDesignTokens.current
    // 遮罩叠两层后的近似底色（遮罩盖在页面上）：浅色主题 ≈ 中灰 → 深色图标；深色主题接近遮罩本色 → 浅色图标
    val backdrop = t.scrim.compositeOver(t.scrim.compositeOver(t.surface))
    val darkSystemBarIcons = backdrop.luminance() > 0.179f
    DisposableEffect(view, darkSystemBarIcons) {
        val window = view.findDialogWindow()
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            @Suppress("DEPRECATION")
            window.setDimAmount(0f)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            run {
                // statusBarColor / navigationBarColor 在 API 35 起弃用（改由 WindowInsetsController 的外观
                // 开关 + 强制 edge-to-edge 接管），但那套只对 Activity 窗口生效：Dialog 窗口在 Android 14
                // 及以下仍必须靠这两个属性把系统栏刷成透明，所以这里保留（HyperOS 正是这种组合）。
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = darkSystemBarIcons
                isAppearanceLightNavigationBars = darkSystemBarIcons
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose { }
    }
}

/**
 * 沿 View 树向上找承载本视图的 Dialog 窗口。
 *
 * Compose 的 `DialogLayout` 实现了 [DialogWindowProvider]（`ui-android` 1.7.x），
 * 在 Dialog 内容里它就是 `LocalView` 本身或其祖先，因此从当前 View 起向上遍历最稳。
 */
private tailrec fun View.findDialogWindow(): Window? = when (this) {
    is DialogWindowProvider -> window
    else -> (parent as? View)?.findDialogWindow()
}
