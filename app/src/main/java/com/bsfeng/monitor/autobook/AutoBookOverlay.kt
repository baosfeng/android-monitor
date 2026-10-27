package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.ui.components.rememberMotionTier
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme

// 从 autobook/AutoBookOverlay.kt 拆出：浮窗的展示 / 隐藏与 Compose 内容。
// 窗口参数在 OverlayWindowConfig，宿主生命周期在 OverlayLifecycleOwner。

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
 * 点遮罩只抖 150ms + 轻震（不关、不写忽略），点卡片内空白被吞掉，切到任何 App 都不收起，
 * 只有服务销毁（[hide]）才卸载。旧实现「点遮罩 = onDismiss」害用户误触一次就被记成忽略 7 天。
 *
 * 动效（s15 契约）：入场首帧直接完整显示（真机反馈「浮窗出现仍慢」，不再加淡入/滑入）；
 * 退场 150ms（`dur-overlay-out`）播完才真正卸载；遮罩点击的卡片抖动 150ms（精简动效档跳过）。
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
     * @param categoryTree 一级/二级分类树（含全部分类，切换类型时无需再查库）
     * @param onConfirm 用户点「记一笔」：回传最终的类型、分类与编辑后的说明
     * @param onIgnore 用户点「忽略」：**唯一**会让浮窗按用户意愿关闭的回调
     * @param onScrimTap 用户点到遮罩：只做诊断 / 轻提示，浮窗保持显示
     */
    fun show(
        draft: AutoBookDraft,
        categoryTree: CategoryTree,
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
                        categoryTree = categoryTree,
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

        val params = OverlayWindowConfig.build()

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
        OverlayWindowConfig.applyFocusable(params, focusable)
        try {
            windowManager.updateViewLayout(view, params)
            Log.d(TAG, "窗口输入焦点切换: focusable=$focusable")
        } catch (e: Exception) {
            Log.w(TAG, "切换窗口输入焦点失败: ${e.message}")
        }
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
     * 契约锚点（docs/交互设计/s15-自动记账浮窗-交互契约.md）：
     * - `screen-s15` 整屏拦截；`s15-layer` 遮罩+卡片的容器；`s15-scrim` 卡片以外的遮罩；
     * - `s15-sheet` 卡片本体（吞点击，不因点空白/拖拽关闭）。
     *
     * 关闭只有两条路（卡片里的两个按钮）：点下去先播 150ms 退场再回调，回调里才落库/写忽略；
     * 遮罩点击只抖 + 轻震 + 每笔一次 toast，浮窗继续显示。
     */
    @Composable
    private fun AutoBookOverlayContent(
        draft: AutoBookDraft,
        categoryTree: CategoryTree,
        onConfirm: (type: Int, categoryName: String, remark: String) -> Unit,
        onIgnore: () -> Unit,
        onScrimTap: () -> Unit,
        onImeFocusRequest: (Boolean) -> Unit,
    ) {
        val t = LocalDesignTokens.current
        val density = LocalDensity.current
        val haptic = rememberHaptic()
        val motionEnabled = rememberMotionTier() != MotionTier.REDUCED

        // ── 退场：先播 dur-overlay-out（150ms）再真正卸载窗口 ──────────────────
        // 顺序很重要：退场动画期间只关入口、不落库；动画播完才回调（落库/写忽略）。
        // 反过来「先落库再播动画」会让用户在动画里看到的还是旧数据，且崩溃点落在中间态。
        var closing by remember { mutableStateOf(false) }
        var closeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val exit = remember { Animatable(0f) }
        LaunchedEffect(closing) {
            if (!closing) return@LaunchedEffect
            if (motionEnabled) {
                exit.animateTo(1f, tween(t.durOverlayOut, easing = t.easeOut))
            } else {
                exit.snapTo(1f)
            }
            val action = closeAction
            closeAction = null
            action?.invoke()
        }
        // 连点只生效一次：closing 置位后的第二次点击直接丢掉
        val requestClose: (() -> Unit) -> Unit = { action ->
            if (!closing) {
                closeAction = action
                closing = true
            }
        }

        // ── 遮罩点击：卡片抖 150ms（±4dp）+ 轻震，浮窗不关 ────────────────────
        val shake = remember { Animatable(0f) }
        var shakeTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(shakeTick) {
            if (shakeTick == 0 || !motionEnabled) return@LaunchedEffect
            val amplitude = with(density) { SCRIM_SHAKE_AMPLITUDE.toPx() }
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = SCRIM_SHAKE_MS
                    -amplitude at 0
                    -amplitude at SCRIM_SHAKE_MS * 3 / 10
                    amplitude at SCRIM_SHAKE_MS * 13 / 20
                    0f at SCRIM_SHAKE_MS
                },
            )
        }

        // 退场位移按卡片自身高度换算：卡片从底部滑出屏幕，不依赖屏幕高
        var sheetHeightPx by remember { mutableStateOf(0f) }

        Box(Modifier.fillMaxSize().testTag("screen-s15")) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("s15-layer")
                    // 退场只淡出整层：卡片另有下滑位移（见 s15-sheet）
                    .graphicsLayer { alpha = 1f - exit.value }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("s15-scrim")
                        // 原型 scrim 0.34；遮罩叠两层 ≈ 0.56，与窗口级浮层（WindowOverlay）同一口径
                        .background(t.scrim.compositeOver(t.scrim))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            // 吞掉点击：浮窗只能由「记一笔」/「忽略」关闭（2026-10-20 用户要求）
                            haptic(HapticTone.LIGHT)
                            shakeTick++
                            onScrimTap()
                        }
                )

                Box(Modifier.align(Alignment.BottomCenter)) {
                    AutoBookConfirmSheet(
                        draft = draft,
                        categoryTree = categoryTree,
                        onConfirm = { type, category, remark ->
                            haptic(HapticTone.LIGHT)
                            requestClose { onConfirm(type, category, remark) }
                        },
                        onIgnore = {
                            haptic(HapticTone.LIGHT)
                            requestClose(onIgnore)
                        },
                        onImeFocusRequest = onImeFocusRequest,
                        dismissing = closing,
                        modifier = Modifier
                            .testTag("s15-sheet")
                            .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                            .graphicsLayer {
                                translationX = shake.value
                                translationY = exit.value * sheetHeightPx
                            }
                            // 卡内点击一律吞掉（无高亮、不失焦、不关闭）；用 pointerInput 而不是
                            // clickable —— clickable 会让 TalkBack 把整张卡读成按钮，而它不可点。
                            .pointerInput(Unit) { detectTapGestures { } }
                    )
                }
            }
        }
    }

    private companion object {
        /** 日志 tag 与引擎保持一致，方便 `adb logcat -s AutoBook` 一起看 */
        const val TAG = "AutoBook"

        /** 遮罩点击的卡片抖动幅度（原型 @keyframes ab-shake：translateX ±4px） */
        val SCRIM_SHAKE_AMPLITUDE = 4.dp

        /** 遮罩点击的卡片抖动时长（契约：150ms；设计 token 里没有这一档，故就地定义） */
        const val SCRIM_SHAKE_MS = 150
    }
}
