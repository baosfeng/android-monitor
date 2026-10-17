package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme

/**
 * 自动记账确认浮窗。
 *
 * 用 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 浮在微信/支付宝之上，
 * 不需要「悬浮窗」权限（跟随无障碍服务即可），与本项目已有的跳过弹幕同一套机制。
 *
 * 内容用 Compose 渲染并包在 [MonitorTheme] 里 —— 因此浮窗自动跟随用户在设置里选的
 * 8 套主题（见 .open-design/24-autobook-themes.html），不写死任何色值。
 *
 * ## v2 显示契约（2026-10-17，真机反馈后重做）
 *
 * 1. **全屏沉浸式遮罩**：窗口不再只包住底部卡片，而是铺满整屏，遮罩盖住状态栏、挖孔区与
 *    手势条（fitInsetsTypes = 0 + LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS）。点遮罩 = 忽略。
 * 2. **面板实心**：确认卡底色用 surfaceWarm → surface → bg 依次合成到 100% 不透明 ——
 *    玻璃拟态主题的 surface 是 rgba(255,255,255,.74)，直接用会透出支付页文字
 *    （真机反馈「背景还是透明的，容易干扰」）。
 * 3. **检测到即弹**：识别到支付成功页后立即展示（不再延迟 1s）；付款前页面由
 *    [AutoBookParser.isBillPage] 的 PRE_PAY_WORDS 挡在门外，不会提前弹。
 *
 * 入场：遮罩淡入 180ms + 卡片从底部滑入 220ms；关闭即时卸载（保持既有行为）。
 */
class AutoBookOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager
        get() = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var host: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    /** 当前展示的草稿（用于重复识别时刷新而不是叠加多个浮窗） */
    @Volatile
    var currentDraft: AutoBookDraft? = null
        private set

    val isShowing: Boolean get() = host != null

    /**
     * 展示浮窗。
     *
     * @param categoriesByType 0=支出分类 / 1=收入分类，切换类型时无需再查库
     * @param onConfirm 用户点「记一笔」：回传最终的类型与分类
     * @param onDismiss 用户点「忽略」/ 点遮罩 / 关闭
     */
    fun show(
        draft: AutoBookDraft,
        categoriesByType: Map<Int, List<Category>>,
        onConfirm: (type: Int, categoryName: String) -> Unit,
        onDismiss: () -> Unit
    ) {
        hide()
        val lifecycleOwner = OverlayLifecycleOwner().apply { attach() }
        val dismiss = {
            onDismiss()
            hide()
        }
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                MonitorTheme {
                    AutoBookOverlayContent(
                        draft = draft,
                        categoriesByType = categoriesByType,
                        onConfirm = { type, category ->
                            onConfirm(type, category)
                            hide()
                        },
                        onDismiss = dismiss,
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 可点击但不需要输入法焦点；不铺满就会退回「只包住底部卡片」，遮罩无法覆盖状态栏
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 声明「不吃系统栏 inset」：否则窗口会被状态栏 / 手势条挤小，遮罩铺不满
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            // 加不上就释放掉生命周期，避免泄漏
            lifecycleOwner.destroy()
            throw e
        }

        // 遮罩是深色：状态栏 / 手势条图标切浅色。系统栏外观最终由系统决定，
        // HyperOS 上可能仍按底层 App 的声明绘制 —— 能生效则生效，不生效也不影响遮罩铺满。
        @Suppress("DEPRECATION")
        ViewCompat.getWindowInsetsController(composeView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        host = composeView
        owner = lifecycleOwner
        currentDraft = draft
    }

    /** 移除浮窗（幂等） */
    fun hide() {
        val view = host ?: return
        host = null
        currentDraft = null
        runCatching { windowManager.removeView(view) }
        owner?.destroy()
        owner = null
    }

    /**
     * 浮窗内容：全屏遮罩 + 贴底确认卡。
     *
     * 遮罩铺满整屏（含状态栏），点遮罩 = 忽略；确认卡本身吞掉点击，避免误关。
     */
    @Composable
    private fun AutoBookOverlayContent(
        draft: AutoBookDraft,
        categoriesByType: Map<Int, List<Category>>,
        onConfirm: (type: Int, categoryName: String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val t = LocalDesignTokens.current
        // 首次组合先不进入，LaunchedEffect 再翻 true —— 否则 AnimatedVisibility 不播放入场动画
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = entered,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(180)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 原型 scrim 0.34；遮罩叠两层 ≈ 0.56，与窗口级浮层（WindowOverlay）同一口径
                        .background(t.scrim.compositeOver(t.scrim))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() }
                )
            }

            AnimatedVisibility(
                visible = entered,
                // ⚠️ align 必须落在 AnimatedVisibility（Box 的直接子节点）上，见踩坑文档
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(animationSpec = tween(220)) { fullHeight -> fullHeight } +
                    fadeIn(animationSpec = tween(220)),
            ) {
                AutoBookConfirmSheet(
                    draft = draft,
                    categoriesByType = categoriesByType,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    /**
     * 浮窗的宿主生命周期。
     *
     * Compose 在 Activity 之外渲染（WindowManager 直挂 View）时不会有人替它推进
     * Lifecycle / SavedState，必须自己提供一个最小实现，否则 ComposeView 会直接崩在
     * 「ViewTreeLifecycleOwner not found」。
     */
    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun attach() {
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
