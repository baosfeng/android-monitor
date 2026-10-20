package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.platform.LocalView
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
 *    手势条（fitInsetsTypes = 0 + LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS）。
 * 2. **面板实心**：确认卡底色用 surfaceWarm → surface → bg 依次合成到 100% 不透明 ——
 *    玻璃拟态主题的 surface 是 rgba(255,255,255,.74)，直接用会透出支付页文字
 *    （真机反馈「背景还是透明的，容易干扰」）。
 * 3. **检测到即弹**：识别到支付成功页后立即展示（不再延迟 1s）；付款前页面由
 *    [AutoBookParser.isBillPage] 的 PRE_PAY_WORDS 挡在门外，不会提前弹。
 *
 * ## 关闭契约（2026-10-20，用户真机反馈后收紧）
 *
 * 浮窗**只能由「记一笔」或「忽略」关闭**（判定集中在 [AutoBookDismissPolicy]）：
 * 点遮罩只吞掉点击 + 轻震动，切到任何 App 都不收起，只有服务销毁（[hide]）才卸载。
 * 旧实现「点遮罩 = onDismiss」害用户误触一次就被记成忽略 7 天。
 *
 * 入场：遮罩淡入 180ms + 卡片从底部滑入 220ms；关闭即时卸载（保持既有行为）。
 */
class AutoBookOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager
        get() = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var host: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null

    /** 当前窗口是否允许拿输入焦点（编辑「说明」时临时打开，见 [setImeFocusable]） */
    @Volatile
    private var imeFocusable = false

    /** 当前展示的草稿（用于重复识别时刷新而不是叠加多个浮窗） */
    @Volatile
    var currentDraft: AutoBookDraft? = null
        private set

    val isShowing: Boolean get() = host != null

    /**
     * 展示浮窗。
     *
     * @param categoriesByType 0=支出分类 / 1=收入分类，切换类型时无需再查库
     * @param onConfirm 用户点「记一笔」：回传最终的类型、分类与编辑后的说明
     * @param onIgnore 用户点「忽略」：**唯一**会让浮窗按用户意愿关闭的回调
     * @param onScrimTap 用户点到遮罩：只做诊断 / 轻提示，浮窗保持显示
     */
    fun show(
        draft: AutoBookDraft,
        categoriesByType: Map<Int, List<Category>>,
        onConfirm: (type: Int, categoryName: String, remark: String) -> Unit,
        onIgnore: () -> Unit,
        onScrimTap: () -> Unit = {},
    ) {
        hide()
        val lifecycleOwner = OverlayLifecycleOwner().apply { attach() }
        val ignore = {
            onIgnore()
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
                        onConfirm = { type, category, remark ->
                            onConfirm(type, category, remark)
                            hide()
                        },
                        onIgnore = ignore,
                        onScrimTap = onScrimTap,
                        onImeFocusRequest = this@AutoBookOverlay::setImeFocusable,
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 可点击但默认不拿输入焦点；不铺满就会退回「只包住底部卡片」，遮罩无法覆盖状态栏。
            // 编辑「说明」时由 setImeFocusable(true) 临时去掉 NOT_FOCUSABLE，否则输入法不会弹。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 无障碍叠加窗口默认可能走软件渲染（真机实测 isHardwareAccelerated=false）：
            // 全屏遮罩 + 分类网格滚动每帧都要 CPU 重绘，必然卡。显式请求硬件加速；
            // 部分 ROM 会忽略这个 flag，所以下面 attach 后仍会打印实际值。
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            // 键盘弹出时把窗口顶小，确认卡跟着上移；未编辑时窗口不可聚焦，键盘不会弹
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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

        val mountedAt = System.currentTimeMillis()
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            // 加不上就释放掉生命周期，避免泄漏
            lifecycleOwner.destroy()
            throw e
        }
        // 排查「分类网格滑动卡」：软件渲染下全屏浮窗滚动会掉帧，日志里能直接看到。
        // 必须等 View attach 完成再读，addView 返回时窗口可能还没挂上，读出来永远是 false。
        composeView.post {
            Log.d(TAG, "浮窗渲染(attach): hardwareAccelerated=${composeView.isHardwareAccelerated}")
        }
        // 首帧绘制完成时间：定位「识别到了但浮窗迟迟不显示」到底卡在哪一段
        composeView.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    composeView.viewTreeObserver.removeOnPreDrawListener(this)
                    Log.i(TAG, "延迟追踪: 首帧绘制=${System.currentTimeMillis() - mountedAt}ms")
                    return true
                }
            }
        )

        // 遮罩是深色：状态栏 / 手势条图标切浅色。系统栏外观最终由系统决定，
        // HyperOS 上可能仍按底层 App 的声明绘制 —— 能生效则生效，不生效也不影响遮罩铺满。
        @Suppress("DEPRECATION")
        ViewCompat.getWindowInsetsController(composeView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        host = composeView
        owner = lifecycleOwner
        windowParams = params
        imeFocusable = false
        currentDraft = draft
    }

    /**
     * 切换「窗口能不能拿输入焦点」。
     *
     * `TYPE_ACCESSIBILITY_OVERLAY` 默认 `FLAG_NOT_FOCUSABLE`：点遮罩不会抢目标 App 的输入，
     * 但 Compose 输入框也拿不到焦点，输入法根本不会弹。用户点「说明」行时临时去掉这个 flag
     * 并让窗口 fit IME（随键盘缩小），失焦 / 点「完成」后恢复，目标 App 正常接管输入。
     *
     * 必须在主线程调用（Compose 回调所在线程）。
     */
    fun setImeFocusable(focusable: Boolean) {
        val view = host ?: return
        val params = windowParams ?: return
        if (imeFocusable == focusable) return
        imeFocusable = focusable
        params.flags = if (focusable) {
            // 编辑态：去掉 NOT_FOCUSABLE 才能拿输入焦点；同时去掉 NO_LIMITS，
            // 否则窗口不受显示边界约束，ADJUST_RESIZE 不一定让位给键盘
            params.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        } else {
            // 退出编辑：恢复「不可聚焦 + 铺满无边」的展示态
            params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyImeInsets(params, focusable)
        }
        try {
            windowManager.updateViewLayout(view, params)
            Log.d(TAG, "窗口输入焦点切换: focusable=$focusable")
        } catch (e: Exception) {
            Log.w(TAG, "切换窗口输入焦点失败: ${e.message}")
        }
    }

    /**
     * 只 fit IME（不 fit 状态栏 / 手势条）：键盘弹出时窗口被顶小，遮罩仍铺满除键盘外的屏幕。
     *
     * 单独封装 + [RequiresApi]：`WindowInsets.Type` 是 API 30 才有的类型，直接在通用代码里
     * 引用会在 API 29 上冒类解析风险。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyImeInsets(params: WindowManager.LayoutParams, focusable: Boolean) {
        params.fitInsetsTypes = if (focusable) WindowInsets.Type.ime() else 0
    }

    /** 移除浮窗（幂等）。只有服务销毁等系统级生命周期会直接走这里，用户操作只能通过两个按钮。 */
    fun hide() {
        val view = host ?: return
        host = null
        currentDraft = null
        windowParams = null
        imeFocusable = false
        runCatching { windowManager.removeView(view) }
        owner?.destroy()
        owner = null
    }

    /**
     * 浮窗内容：全屏遮罩 + 贴底确认卡。
     *
     * 遮罩铺满整屏（含状态栏），**点遮罩只吞掉点击**：轻震动 + 回调诊断，浮窗继续显示；
     * 确认卡本身也吞掉点击，避免误关（关闭只走卡片里的两个按钮）。
     */
    @Composable
    private fun AutoBookOverlayContent(
        draft: AutoBookDraft,
        categoriesByType: Map<Int, List<Category>>,
        onConfirm: (type: Int, categoryName: String, remark: String) -> Unit,
        onIgnore: () -> Unit,
        onScrimTap: () -> Unit,
        onImeFocusRequest: (Boolean) -> Unit,
    ) {
        val t = LocalDesignTokens.current
        val view = LocalView.current
        // 遮罩点击要不要震动也由关闭策略裁决（唯一裁决点，UI 不自己判断）
        val scrimEffect = remember { AutoBookDismissPolicy.effectOf(AutoBookCloseSource.SCRIM_TAP) }
        // 首帧直接完整显示：不做淡入/滑入动画。真机反馈「浮窗出现仍慢」——
        // 80ms 淡入 + 100ms 滑入会让用户感觉又慢一截；识别链路已压到约 220ms，
        // 这里不再额外加动画时间（2026-10-20）。
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 原型 scrim 0.34；遮罩叠两层 ≈ 0.56，与窗口级浮层（WindowOverlay）同一口径
                    .background(t.scrim.compositeOver(t.scrim))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // 吞掉点击：浮窗只能由「记一笔」/「忽略」关闭（2026-10-20 用户要求）
                        if (scrimEffect.hapticFeedback) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                        onScrimTap()
                    }
            )

            Box(Modifier.align(Alignment.BottomCenter)) {
                AutoBookConfirmSheet(
                    draft = draft,
                    categoriesByType = categoriesByType,
                    onConfirm = onConfirm,
                    onIgnore = onIgnore,
                    onImeFocusRequest = onImeFocusRequest,
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

    private companion object {
        /** 日志 tag 与引擎保持一致，方便 `adb logcat -s AutoBook` 一起看 */
        const val TAG = "AutoBook"
    }
}
