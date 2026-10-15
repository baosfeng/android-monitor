package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
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
import com.bsfeng.monitor.ui.theme.MonitorTheme

/**
 * 自动记账确认浮窗。
 *
 * 用 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 浮在微信/支付宝之上，
 * 不需要「悬浮窗」权限（跟随无障碍服务即可），与本项目已有的跳过弹幕同一套机制。
 *
 * 内容用 Compose 渲染并包在 [MonitorTheme] 里 —— 因此浮窗自动跟随用户在设置里选的
 * 8 套主题（见 `.open-design/24-autobook-themes.html`），不写死任何色值。
 *
 * 显示策略：只浮出底部卡片、**不加全屏遮罩**，用户仍能看到上方支付页面的金额做核对，
 * 也不会误触挡住页面操作。
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
     * @param onDismiss 用户点「忽略」或关闭
     */
    fun show(
        draft: AutoBookDraft,
        categoriesByType: Map<Int, List<Category>>,
        onConfirm: (type: Int, categoryName: String) -> Unit,
        onDismiss: () -> Unit
    ) {
        hide()
        val lifecycleOwner = OverlayLifecycleOwner().apply { attach() }
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                MonitorTheme {
                    AutoBookConfirmSheet(
                        draft = draft,
                        categoriesByType = categoriesByType,
                        onConfirm = { type, category ->
                            onConfirm(type, category)
                            hide()
                        },
                        onDismiss = {
                            onDismiss()
                            hide()
                        }
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 可点击但不需要输入法焦点；不加 FLAG_NOT_TOUCHABLE，否则按钮点不动
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            // 加不上就释放掉生命周期，避免泄漏
            lifecycleOwner.destroy()
            throw e
        }

        // 入场动画：从底部滑入 + 淡入
        composeView.alpha = 0f
        composeView.translationY = 96f
        composeView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()

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
