package com.bsfeng.monitor.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.record.RecordCollector
import com.bsfeng.monitor.skip.ActionExecutor
import com.bsfeng.monitor.skip.SkipEventRecorder
import com.bsfeng.monitor.skip.SkipRuleEngine
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters

/** 时间线日志标签 — grep 此标签即可看到干净的用户操作时间线 */
private const val TL = "MonitorTimeline"

/**
 * 无障碍服务 — 仅追踪导航/页面跳转。
 *
 * 只处理 TYPE_WINDOW_STATE_CHANGED 事件。
 */
class MonitorAccessibilityService : AccessibilityService() {

    private val recordCollector: RecordCollector
        get() = (application as MonitorApp).container.recordCollector

    private var lastPackageName: String? = null
    private var lastActivityClass: String? = null
    private var lastSwitchTimeMs: Long = System.currentTimeMillis()
    private var lastPageTitle: String? = null  // 上一级页面标题
    private var homePackage: String? = null  // 系统桌面包名（缓存）
    private fun isHomeApp(pkg: String): Boolean {
        if (homePackage == null) {
            homePackage = try {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                }
                packageManager?.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            } catch (_: Exception) { null }
        }
        return pkg == homePackage
    }

    private val skipEngine: SkipRuleEngine by lazy {
        (application as MonitorApp).container.skipRuleEngine
    }

    /** 是否在桌面（防止重复触发回桌面事件） */
    private var isOnHomeScreen: Boolean = false

    /** 前台 App 轮询：每 1 秒检测当前前台 App，变化即记录（不依赖窗口事件） */
    private val foregroundHandler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val foregroundPoller = object : Runnable {
        override fun run() {
            try {
                pollForegroundApp()
            } catch (e: Exception) {
                Log.w("MonitorService", "pollForegroundApp 异常", e)
            }
            // 仅当服务仍存活时继续调度，防止服务重建后产生多条轮询链
            if (pollerActive) {
                foregroundHandler.postDelayed(this, 1_000L)
            }
        }
    }

    /** 轮询链运行标志：onServiceConnected 启动，onDestroy 停止（防叠加） */
    private var pollerActive: Boolean = false

    private fun pollForegroundApp() {
        val now = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        root.recycle()
        if (pkg == null) return

        // 桌面
        if (isHomeApp(pkg)) {
            if (!isOnHomeScreen) {
                isOnHomeScreen = true
                timeline("🏠", "回到桌面")
            }
            // flush 当前 session（桌面不计时）
            recordCollector.onTick(null, null, now)
            lastPackageName = pkg
            return
        }
        isOnHomeScreen = false

        // 系统 UI → flush 当前 session（不计时）
        if (isSystemUi(pkg)) {
            recordCollector.onTick(null, null, now)
            return
        }

        val appName = AppUtils.getAppName(pkg)

        // 日志（仅 App 切换时）
        if (pkg != lastPackageName) {
            Log.d("MonitorService", "pollForegroundApp: $appName ($pkg)")
            timeline("📱", appName)
            lastPackageName = pkg
        }

        // 通知 UsageTracker 累加时长（状态驱动，每秒调用一次）
        recordCollector.onTick(pkg, appName, now)
    }

    /** 输出干净的时间线日志：adb logcat -v time | grep MonitorTimeline */
    private fun timeline(emoji: String, message: String) {
        Log.d("MonitorTimeline", "$emoji $message")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        DiagnosticCounters.accessibilityEventCount++
        DiagnosticCounters.lastAccessibilityEventType = event.eventType.toString()
        DiagnosticCounters.lastAccessibilityPackage = event.packageName?.toString() ?: ""
        Log.d("MonitorService", "onAccessibilityEvent: type=${event.eventType}, pkg=${event.packageName}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
        }
    }

    // ========== Window Change ==========

    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: run {
            Log.w("MonitorService", "handleWindowChange: packageName is null")
            return
        }
        val className = event.className?.toString() ?: ""
        val now = System.currentTimeMillis()

        // 过滤桌面和系统界面（纯日志，不处理 App 切换——由轮询负责）
        if (isHomeApp(packageName)) {
            Log.d("MonitorService", "handleWindowChange: home screen")
            return
        }
        if (isSystemUi(packageName)) {
            DiagnosticCounters.accessibilityFilteredCount++
            Log.d("MonitorService", "handleWindowChange: filtered as system UI: $packageName")
            return
        }

        // ===== 开屏广告跳过（仅在真正进入 App 时检测）=====
        // 条件：包名变化（切换 App）
        // 同 App 内页面切换（Activity 跳转）不触发开屏检测，避免误拦截
        val shouldCheckSplash = packageName != lastPackageName
        if (shouldCheckSplash) {
            // 进入新 App，清除上一条残留弹幕
            removeCurrentDanmaku()
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    if (packageName == "com.bsfeng.monitor") {
                        // 进入本 App，停止所有外部监控和重置状态
                        skipEngine.resetAppState(packageName)
                    } else {
                        val skipped = skipEngine.onWindowChanged(packageName, className, root)
                        if (skipped) {
                            val appNameShort = AppUtils.getAppName(packageName)
                            timeline("⏭️", "跳过 $appNameShort 开屏广告")
                            SkipEventRecorder.recordSkip(packageName, appNameShort, "", className)
                            showSkipDanmaku(appNameShort)
                        } else {
                            // 首次尝试未命中，启动持续监控（处理白屏→广告延迟加载）
                            skipEngine.startWatching(packageName, className)
                        }
                    }
                    root.recycle()
                } else {
                    Log.w("MonitorService", "handleWindowChange: rootInActiveWindow is null for $packageName")
                }
            } catch (e: Exception) {
                Log.w("MonitorService", "skipEngine.onWindowChanged 异常", e)
            }
        }

        lastSwitchTimeMs = now
        lastActivityClass = className

        // 保留 updateKillDetectorTargets 为空调用（保持兼容）
        updateKillDetectorTargets(packageName)
    }

    // ========== Description Helpers ==========

    private fun describeActivity(shortClass: String, packageName: String): String {
        return when {
            // 微信聊天
            shortClass.contains("ChattingUI", ignoreCase = true) -> "聊天界面"
            shortClass.contains("Conversation", ignoreCase = true) ||
                shortClass.contains("LauncherUI", ignoreCase = true) -> "聊天列表"
            // 微信发现页
            shortClass.contains("SnsTimeLineUI", ignoreCase = true) ||
                shortClass.contains("ImproveSnsTimeline", ignoreCase = true) -> "朋友圈"
            shortClass.contains("SnsBrowseUI", ignoreCase = true) ||
                shortClass.contains("SnsComment", ignoreCase = true) -> "朋友圈详情"
            // 微信公众号
            shortClass.contains("BizTimeLineUI", ignoreCase = true) ||
                shortClass.contains("BizChattingUI", ignoreCase = true) -> "公众号列表"
            shortClass.contains("BizFlutterTLFlutterView", ignoreCase = true) -> "公众号主页"
            shortClass.contains("TmplWebViewMMUI", ignoreCase = true) -> "公众号文章"
            // 微信小程序
            shortClass.contains("AppBrandUI", ignoreCase = true) ||
                shortClass.contains("MiniProgram", ignoreCase = true) -> "小程序"
            // 微信支付
            shortClass.contains("Wallet", ignoreCase = true) ||
                shortClass.contains("PayUI", ignoreCase = true) -> "支付页面"
            // 微信视频号
            shortClass.contains("Finder", ignoreCase = true) ||
                shortClass.contains("VideoCollection", ignoreCase = true) -> "视频号"
            // 通讯录
            shortClass.contains("AddressUI", ignoreCase = true) ||
                shortClass.contains("Contact", ignoreCase = true) -> "通讯录"
            // 搜索
            shortClass.contains("Search", ignoreCase = true) -> "搜索页面"
            // 设置
            shortClass.contains("Setting", ignoreCase = true) -> "设置页面"
            // 相册
            shortClass.contains("Album", ignoreCase = true) ||
                shortClass.contains("Gallery", ignoreCase = true) -> "相册"
            // 浏览器/WebView
            shortClass.contains("Browser", ignoreCase = true) ||
                shortClass.contains("WebView", ignoreCase = true) -> {
                if (packageName == "com.tencent.mm") "公众号文章"
                else "网页浏览"
            }
            // 通用 UI 模式
            shortClass.contains("Progress", ignoreCase = true) ||
                shortClass.contains("Loading", ignoreCase = true) -> "加载中"
            shortClass.contains("Toast", ignoreCase = true) ||
                shortClass.contains("Snackbar", ignoreCase = true) -> "提示"
            shortClass.contains("Permission", ignoreCase = true) ||
                shortClass.contains("Rationale", ignoreCase = true) -> "权限请求"
            shortClass.contains("Share", ignoreCase = true) ||
                shortClass.contains("Send", ignoreCase = true) ||
                shortClass.contains("Forward", ignoreCase = true) -> "分享页面"
            shortClass.contains("Image", ignoreCase = true) ||
                shortClass.contains("Photo", ignoreCase = true) ||
                shortClass.contains("Picture", ignoreCase = true) -> "图片"
            shortClass.contains("Scan", ignoreCase = true) ||
                shortClass.contains("QR", ignoreCase = true) ->
                    if (shortClass.contains("Result", ignoreCase = true)) "扫码结果" else "扫码"
            shortClass.contains("Location", ignoreCase = true) ||
                shortClass.contains("Map", ignoreCase = true) -> "位置"
            shortClass.contains("Download", ignoreCase = true) ||
                shortClass.contains("Update", ignoreCase = true) -> "下载"
            shortClass.contains("Notice", ignoreCase = true) ||
                shortClass.contains("Notification", ignoreCase = true) -> "通知"
            shortClass.contains("Input", ignoreCase = true) ||
                shortClass.contains("Keyboard", ignoreCase = true) -> "输入"
            shortClass.contains("Picker", ignoreCase = true) ||
                shortClass.contains("Selector", ignoreCase = true) -> "选择器"
            // 电商
            shortClass.contains("Order", ignoreCase = true) ||
                shortClass.contains("Confirm", ignoreCase = true) -> "订单页面"
            shortClass.contains("Detail", ignoreCase = true) &&
                packageName.contains("taobao", ignoreCase = true) -> "商品详情"
            // 兜底
            packageName == "com.tencent.mm" -> "微信"
            else -> shortClass
        }
    }

    // 导航路径辅助
    private val recentForegroundPackages = mutableSetOf<String>()
    private fun updateKillDetectorTargets(packageName: String) {
        recentForegroundPackages.add(packageName)
        if (recentForegroundPackages.size > 5) {
            recentForegroundPackages.remove(recentForegroundPackages.first())
        }
    }

    private fun isSystemUi(packageName: String): Boolean {
        return packageName.startsWith("com.android.systemui") ||
                packageName == "com.miui.systemui" ||
                packageName == "com.miui.personalassistant" ||
                packageName == "com.miui.securitycenter" ||
                packageName == "com.miui.misound" ||
                packageName == "miui.systemui.plugin" ||
                packageName == "com.sohu.inputmethod.sogou" ||
                packageName == "com.baidu.input" ||
                packageName.startsWith("com.android.inputmethod") ||
                packageName == "com.android.quicksearchbox" ||
                packageName == "android"
    }

    override fun onInterrupt() {
        // 服务被系统中断
    }

    // ========== 跳过提示（弹幕风格浮层） ==========

    /** 当前正在显示的弹幕视图，防止重叠 */
    private var currentDanmaku: android.view.View? = null

    companion object {
        private const val DANMAKU_DURATION_MS = 1200L
    }

    /**
     * 显示弹幕式跳过提示（居中半透明浮层）。
     * 使用 [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
     * 保证在任意 App 上方显示，且不需要额外权限。
     * 带有淡入淡出和上下滑入滑出的入场/退场动画。
     */
    private fun showSkipDanmaku(appName: String) {
        Log.d("MonitorService", "showSkipDanmaku: $appName")
        try {
            // 移除之前的弹幕
            removeCurrentDanmaku()

            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val message = "⏭️ 已跳过 $appName 开屏广告"

            val tv = android.widget.TextView(this).apply {
                text = message
                textSize = 17f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
                setPadding(40, 20, 40, 20)
                elevation = 12f
                // 圆角通过 shape 背景
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(200, 0, 0, 0))
                    cornerRadius = 20f
                }
                // 初始状态：透明 + 向上偏移（为入场动画做准备）
                alpha = 0f
                translationY = -50f
            }

            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                y = 120
            }

            currentDanmaku = tv
            wm.addView(tv, params)

            // 入场动画：淡入 + 下滑到目标位置（300ms）
            tv.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                .start()

            // 展示 DANMAKU_DURATION_MS 后退场
            tv.postDelayed({
                try {
                    // 退场动画：淡出 + 上滑（250ms）
                    tv.animate()
                        .alpha(0f)
                        .translationY(-30f)
                        .setDuration(250)
                        .withEndAction {
                            // 只移除自己，不触碰 currentDanmaku——
                            // 若期间已展示新弹幕，currentDanmaku 指向新视图，不能误删
                            removeDanmakuView(tv)
                        }
                        .start()
                } catch (e: Exception) {
                    removeDanmakuView(tv)
                }
            }, DANMAKU_DURATION_MS)
        } catch (e: Exception) {
            Log.w("MonitorService", "显示跳过弹幕失败", e)
        }
    }

    /** 移除当前弹幕视图 */
    private fun removeCurrentDanmaku() {
        val view = currentDanmaku ?: return
        currentDanmaku = null
        removeDanmakuView(view)
    }

    /** 从 WindowManager 移除指定弹幕视图（幂等：已移除/服务已销毁时静默忽略） */
    private fun removeDanmakuView(view: android.view.View) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(view)
        } catch (_: Exception) {}
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return false // 不拦截按键
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ActionExecutor.accessibilityService = this
        skipEngine.setRootNodeProvider { rootInActiveWindow }
        skipEngine.onSkipSuccess = { pkg ->
            skipEngine.recordSkipCooldown(pkg)
            val name = AppUtils.getAppName(pkg)
            timeline("⏭️", "跳过 $name 开屏广告")
            SkipEventRecorder.recordSkip(pkg, name, "", null)
            showSkipDanmaku(name)
        }
        // 启动前台 App 轮询（兜底监测）；已运行则跳过，防止服务重建后叠加轮询链
        if (!pollerActive) {
            pollerActive = true
            foregroundHandler.postDelayed(foregroundPoller, 10_000L)
        }
    }

    override fun onDestroy() {
        // 停止轮询链 + 清理弹幕浮层，防止服务重建后残留回调
        pollerActive = false
        foregroundHandler.removeCallbacks(foregroundPoller)
        removeCurrentDanmaku()
        super.onDestroy()
    }
}
