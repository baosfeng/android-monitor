package com.bsfeng.monitor.service

import android.annotation.SuppressLint

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.autobook.AutoBookEngine
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.PageEvent
import com.bsfeng.monitor.record.PageTracker
import com.bsfeng.monitor.record.RecordCollector
import com.bsfeng.monitor.skip.ActionExecutor
import com.bsfeng.monitor.skip.SkipEventRecorder
import com.bsfeng.monitor.skip.SkipRuleEngine
import com.bsfeng.monitor.skip.SnapshotExporter
import com.bsfeng.monitor.skip.SnapshotRequest
import com.bsfeng.monitor.util.ActivityNames
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters

/** 时间线日志标签 — grep 此标签即可看到干净的用户操作时间线 */
private const val TL = "MonitorTimeline"

/** 页面指纹的轻量遍历上限：每秒轮询跑一次，限深 8 / 节点 200（快照导出是另一个全属性全深度通道） */
private const val PAGE_MAX_DEPTH = 8
private const val PAGE_MAX_NODES = 200

/** WINDOWS_CHANGED 的合并窗口：窗口列表变化可能一秒多次，1 秒内只重读一次根窗口 */
private const val WINDOWS_CHANGED_THROTTLE_MS = 1_000L

/**
 * 无障碍服务 — 仅追踪导航/页面跳转。
 *
 * 只处理 TYPE_WINDOW_STATE_CHANGED 事件。
 *
 * ⚠️ **本类不在 AndroidManifest 里直接注册**：manifest 注册的是它在
 * `com.google.android.accessibility.selecttospeak` 包下的伪装子类
 * [com.google.android.accessibility.selecttospeak.SelectToSpeakService]（微信白名单身份）。
 * 加 `open` 就是为了让那层伪装壳能继承本类的全部逻辑，见该子类的 KDoc 与
 * docs/记账/微信支付页识别调研.md。
 */
open class MonitorAccessibilityService : AccessibilityService() {

    private val recordCollector: RecordCollector
        get() = (application as MonitorApp).container.recordCollector

    /**
     * 页面轨迹状态机：当前页面指纹 + 进入时间（指纹口径见 [PageTracker]）。
     *
     * 懒加载是为了拿到 attach 之后的 `packageName` 作为自身包名 —— 本 App 内的 Tab 切换
     * 不产生 PAGE 记录（真机表现为每秒刷 4 条），过滤收口在 PageTracker 内部。
     */
    private val pageTracker: PageTracker by lazy { PageTracker(selfPackage = packageName) }

    /** WINDOWS_CHANGED 上次处理时刻（1 秒合并窗口） */
    private var lastWindowsChangedMs: Long = 0L

    private var lastPackageName: String? = null
    private var lastActivityClass: String? = null

    /** [lastActivityClass] 所属包名：换了 App 旧的 Activity 名就无意义，必须清空 */
    private var lastActivityPackage: String? = null
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

    /**
     * 自动记账引擎：在微信/支付宝支付结果页上弹确认浮窗。
     *
     * 懒加载 —— 服务启动时不会触碰记账模块，只有真正遇到支付页才会初始化。
     */
    private val autoBookEngine: AutoBookEngine by lazy { AutoBookEngine(this) }

    /** 是否在桌面（防止重复触发回桌面事件） */
    private var isOnHomeScreen: Boolean = false

    /** 前台 App 轮询：每 1 秒检测当前前台 App，变化即记录（不依赖窗口事件） */
    private val foregroundHandler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val foregroundPoller = object : Runnable {
        override fun run() {
            // 活性心跳：进程真在跑才会跳到这里，被冻结后自动停更。
            // 放在 pollForegroundApp() 之外 —— 那条路径在 rootInActiveWindow == null 时会提前 return。
            HeartbeatWatchdog.beat(this@MonitorAccessibilityService, android.os.SystemClock.elapsedRealtime())
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
        val root = rootInActiveWindow ?: run {
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
            return
        }
        val pkg = root.packageName?.toString()
        // 页面文本必须在 recycle 之前取；桌面/系统 UI/本 App 不参与页面轨迹，跳过遍历（省掉每秒一次全树扫描）
        val pageTexts = if (pkg != null && pageTracingEnabled() &&
            !isHomeApp(pkg) && !isSystemUi(pkg) && pkg != packageName
        ) {
            collectVisibleTexts(root)
        } else {
            emptyList()
        }
        root.recycle()
        if (pkg == null) {
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
            return
        }

        // ===== 页面快照导出（UI 请求 → 切到目标 App 后延迟导出"当时的前台页面"）=====
        SnapshotRequest.onForegroundPackage(pkg, now)
        if (SnapshotRequest.consumeIfDue(now)) {
            val snapshotRoot = rootInActiveWindow
            val path = SnapshotExporter.exportCurrentPage(this, snapshotRoot, pkg, lastActivityClass ?: "")
            snapshotRoot?.recycle()
            SnapshotRequest.record(path)
            Log.i("MonitorService", "页面快照导出: " + path)
            timeline("📸", if (path == null) "快照导出失败" else "已导出页面快照")
        }

        // 桌面
        if (isHomeApp(pkg)) {
            if (!isOnHomeScreen) {
                isOnHomeScreen = true
                timeline("🏠", "回到桌面")
            }
            // flush 当前 session（桌面不计时）
            recordCollector.onTick(null, null, now)
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
            lastPackageName = pkg
            return
        }
        isOnHomeScreen = false

        // 系统 UI → flush 当前 session（不计时）
        if (isSystemUi(pkg)) {
            recordCollector.onTick(null, null, now)
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
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
        // 页面轨迹：轮询路径用「Activity（窗口事件给的）+ 可见文本」算指纹
        observePage(pkg, lastActivityClass, pageTexts, appName, now, PageEvent.SOURCE_POLL)
    }

    // ========== 页面轨迹（指纹档） ==========

    private fun pageTracingEnabled(): Boolean = FeatureFlags.get().isEnabled(Feature.PAGE_TRACE)

    /**
     * 喂一次页面观测；任何异常都不外抛（页面轨迹异常不能影响监控与跳广告主链路）。
     *
     * 关闭「页面轨迹」时只做一次结算：已开始的页面先落一条带时长的记录，之后不再采集。
     */
    private fun observePage(
        packageName: String?,
        activityClass: String?,
        visibleTexts: List<String>,
        appName: String?,
        timestamp: Long,
        source: String,
    ) {
        try {
            if (!pageTracingEnabled()) {
                emitPageEvents(pageTracker.flush(timestamp))
                return
            }
            emitPageEvents(
                pageTracker.observe(packageName, activityClass, visibleTexts, appName, source, timestamp)
            )
        } catch (e: Exception) {
            Log.w("MonitorService", "页面轨迹采集异常", e)
        }
    }

    private fun emitPageEvents(events: List<PageEvent>) {
        for (event in events) recordCollector.onPageChanged(event)
    }

    /**
     * 轻量可见文本抽取（指纹输入，**只留在内存**）。
     *
     * 限深 [PAGE_MAX_DEPTH]、节点 [PAGE_MAX_NODES]、最多 [PageTracker.MAX_TEXTS] 条，
     * 只取 `visibleToUser` 的 text；不复用 SnapshotExporter（那边是 60 深全属性，量级差两个数量级）。
     */
    private fun collectVisibleTexts(root: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
        val target = root ?: return emptyList()
        val result = ArrayList<String>(PageTracker.MAX_TEXTS)
        val pending = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        val borrowed = ArrayList<android.view.accessibility.AccessibilityNodeInfo>(64)
        pending.addLast(target to 0)
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited < PAGE_MAX_NODES && result.size < PageTracker.MAX_TEXTS) {
                val (node, depth) = pending.removeFirst()
                visited++
                if (node.isVisibleToUser) {
                    val label = node.text?.toString()?.trim()
                    if (!label.isNullOrEmpty()) result.add(label)
                }
                if (depth < PAGE_MAX_DEPTH) {
                    for (index in 0 until node.childCount) {
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        borrowed.add(child)
                        pending.addLast(child to depth + 1)
                    }
                }
            }
        } finally {
            // getChild 返回的是新实例：不回收会让每秒一次的轮询堆积节点句柄
            for (node in borrowed) runCatching { node.recycle() }
        }
        return result
    }

    /** 输出干净的时间线日志：adb logcat -v time | grep MonitorTimeline */
    private fun timeline(emoji: String, message: String) {
        Log.d("MonitorTimeline", "$emoji $message")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        DiagnosticCounters.accessibilityEventCount++
        DiagnosticCounters.lastAccessibilityEventType = event.eventType.toString()
        DiagnosticCounters.lastAccessibilityPackage = event.packageName?.toString() ?: ""
        // CONTENT_CHANGED 事件频率极高（每 100ms 一批），仅用调试开关输出，避免刷屏
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("MonitorService", "onAccessibilityEvent: type=${event.eventType}, pkg=${event.packageName}")
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowsChanged()
            // 其余事件类型（通知、焦点、手势等）故意不处理：显式兜底，避免漏 case 的静默差异
            else -> Unit
        }

        // ===== 自动记账：识别微信/支付宝支付结果页并弹确认浮窗 =====
        // 独立 try/catch：记账链路异常不能影响监控与开屏跳过主链路
        try {
            autoBookEngine.onAccessibilityEvent(event)
        } catch (e: Exception) {
            Log.w("MonitorService", "自动记账处理异常", e)
        }
    }

    // ========== Content Change（开屏跳过事件驱动加速） ==========

    /**
     * 处理窗口内容变化事件。
     *
     * 开屏广告的跳过按钮通常在广告加载完成后才出现（倒计时结束/广告渲染），
     * 此时节点树必然发生变化产生 CONTENT_CHANGED 事件。
     * 将该事件转发给 SkipRuleEngine，使其能在监控窗口内立即扫描而非等待轮询。
     *
     * 系统 UI/桌面事件直接丢弃（监控目标仅限应用窗口）。
     */
    private fun handleContentChanged(event: AccessibilityEvent) {
        // 广告跳过关闭：内容变化事件不进规则引擎（不扫描节点、不评估规则）
        if (!FeatureFlags.get().isEnabled(Feature.AD_SKIP)) return
        val pkg = event.packageName?.toString() ?: return
        if (isSystemUi(pkg) || isHomeApp(pkg)) return
        skipEngine.onContentChanged(pkg)
    }

    // ========== Windows Changed（修正悬浮窗顶掉根窗口的误判） ==========

    /**
     * 处理窗口列表变化事件。
     *
     * 第三方悬浮窗（来电、录屏、歌词）出现/消失时，`rootInActiveWindow` 会短暂指向悬浮窗自身的
     * 窗口，只看 WINDOW_STATE_CHANGED 会把那一瞬间当成一次页面切换。这里在窗口列表变化时
     * **重新读一次根窗口的包名**，只有真的换了包名/Activity 才结算页面。
     *
     * 只参与页面轨迹（不写文本、不扫描跳过规则），关闭页面轨迹时直接返回。
     */
    private fun handleWindowsChanged() {
        if (!pageTracingEnabled()) return
        val now = System.currentTimeMillis()
        // 窗口列表变化可能一秒多次（输入法、悬浮窗动画）：1 秒内只重读一次根窗口
        if (now - lastWindowsChangedMs < WINDOWS_CHANGED_THROTTLE_MS) return
        val root = rootInActiveWindow ?: return
        lastWindowsChangedMs = now
        val pkg = root.packageName?.toString()
        // 本 App（设置/实时页切 Tab）与桌面/系统 UI 一样不参与页面轨迹，也不做文本遍历
        val trackable = pkg != null && !isHomeApp(pkg) && !isSystemUi(pkg) && pkg != packageName
        val texts = if (trackable) collectVisibleTexts(root) else emptyList()
        root.recycle()
        if (pkg == null || !trackable) {
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_WINDOW_EVENT)
            return
        }
        observePage(pkg, lastActivityClass, texts, AppUtils.getAppName(pkg), now, PageEvent.SOURCE_WINDOW_EVENT)
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
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_WINDOW_EVENT)
            return
        }
        if (isSystemUi(packageName)) {
            DiagnosticCounters.accessibilityFilteredCount++
            Log.d("MonitorService", "handleWindowChange: filtered as system UI: $packageName")
            observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_WINDOW_EVENT)
            return
        }

        // ===== 开屏广告跳过 =====
        // 引擎以「App 切换时刻」为锚点开窗匹配：窗口内由 CONTENT_CHANGED 事件驱动扫描 +
        // 兜底轮询，根节点未就绪时自行重试（旧实现在 root 为 null 时整次启动直接漏检）。
        // 广告跳过关闭：不打开匹配窗口（不扫描、不轮询、不执行点击规则）。
        // 只跳过这一段：前台 App 追踪、自动记账、页面快照等本文件其余逻辑照常运行。
        val skipEnabled = FeatureFlags.get().isEnabled(Feature.AD_SKIP)
        try {
            if (packageName == this.packageName) {
                // 进入本 App：停止开屏跳过监控并重置状态。
                // 页面轨迹同样不记录本 App —— PageTracker 收到自身包名会结算上一个外部页面并清空，
                // 不产生新页（见 PageTracker.observe 的 selfPackage 分支），这里不需要额外开关。
                if (skipEnabled) skipEngine.resetAppState(packageName)
            } else {
                if (packageName != lastPackageName) removeCurrentDanmaku()
                if (skipEnabled) skipEngine.onWindowChanged(packageName, className)
            }
        } catch (e: Exception) {
            Log.w("MonitorService", "skipEngine.onWindowChanged 异常", e)
        }

        lastSwitchTimeMs = now
        // 同引擎：widget 类名的窗口事件（Toast/弹窗）不能覆盖"当前 Activity"，
        // 否则页面快照会记下错误的 Activity，写规则时按错误界面名写
        val appSwitched = packageName != lastActivityPackage
        lastActivityClass = ActivityNames.resolveActivityClass(lastActivityClass ?: "", className, appSwitched)
        lastActivityPackage = packageName

        // ===== 页面轨迹（指纹档）：窗口事件的 Activity 变化是权威信号，不等轮询确认 =====
        // 事件里没有页面文本，指纹先用「包名 + Activity」；轮询读到文本后会补上（PageTracker 内部
        // 视为同一页的就绪度变化，不会多记一条）。
        observePage(
            packageName,
            lastActivityClass,
            emptyList(),
            AppUtils.getAppName(packageName),
            now,
            PageEvent.SOURCE_WINDOW_EVENT,
        )

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

        /**
         * 服务实例是否已连接（[onServiceConnected] 置 true，[onDestroy] 置 false）。
         *
         * 系统 API（[AccessibilityManager] 列表）在部分 ROM 上会滞后，
         * 该标志用于兜底判定"用户已开启无障碍服务"（见 `AccessibilityStatus`）。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 已连接的服务实例：**仅供**设置页调试入口唤起自动记账浮窗（模拟器没有支付 App）。
         * onDestroy 置空，避免静态引用持住已销毁的 Service。
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var instance: MonitorAccessibilityService? = null
            private set
    }

    /**
     * 调试入口：不经过识别链路，直接弹出自动记账确认浮窗。
     *
     * @return false = 服务未连接（浮窗挂不上），调用方据此提示用户
     */
    fun debugShowAutoBookSheet(): Boolean {
        if (!isRunning) return false
        runCatching { autoBookEngine.showDebugSheet() }
            .onFailure { Log.w("MonitorService", "调试浮窗启动失败: ${it.message}") }
        return true
    }

    /**
     * 显示弹幕式跳过提示（居中半透明浮层）。
     * 使用 [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
     * 保证在任意 App 上方显示，且不需要额外权限。
     * 带有淡入淡出和上下滑入滑出的入场/退场动画。
     */
    private fun showSkipDanmaku(appName: String, confirmed: Boolean? = null) {
        Log.d("MonitorService", "showSkipDanmaku: $appName confirmed=$confirmed")
        try {
            // 移除之前的弹幕
            removeCurrentDanmaku()

            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            // 未确认时如实提示，避免把"点了没反应"也说成跳过成功
            val message = if (confirmed == false) {
                "⏭️ 已点击 $appName 跳过按钮（未确认）"
            } else {
                "⏭️ 已跳过 $appName 开屏广告"
            }

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
        isRunning = true
        instance = this
        // 实验 2 前提验证：确认系统真的把 isAccessibilityTool 读进来了。
        // 属性写在 res/xml/accessibility_service_config.xml，若 ROM 忽略它，
        // 「微信是否放行节点」的实验就会变成假阴性 —— 必须先把这条前提钉死。
        // 判定方法与回退见 docs/记账/微信支付页识别调研.md 的「实验 2」。
        val accessibilityTool = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            serviceInfo?.isAccessibilityTool
        } else null
        Log.i("MonitorService", "无障碍服务身份: isAccessibilityTool=$accessibilityTool")
        ActionExecutor.accessibilityService = this
        skipEngine.setRootNodeProvider { rootInActiveWindow }
        skipEngine.onSkipSuccess = { result ->
            val name = AppUtils.getAppName(result.packageName)
            val suffix = if (result.confirmed == false) "（未确认）" else ""
            timeline("⏭️", "跳过 $name 开屏广告$suffix")
            SkipEventRecorder.recordSkip(
                packageName = result.packageName,
                appName = name,
                selectorExpr = result.selector,
                className = lastActivityClass,
                confirmed = result.confirmed
            )
            showSkipDanmaku(name, result.confirmed)
        }
        // 启动前台 App 轮询（兜底监测）；已运行则跳过，防止服务重建后叠加轮询链
        if (!pollerActive) {
            pollerActive = true
            foregroundHandler.postDelayed(foregroundPoller, 10_000L)
        }
        // 预热自动记账：提前加载分类，第一笔账单弹窗时不再现查库（浮窗更快）
        runCatching { autoBookEngine.warmUp() }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        // 停止轮询链 + 清理弹幕浮层，防止服务重建后残留回调
        pollerActive = false
        foregroundHandler.removeCallbacks(foregroundPoller)
        // 结算当前页面（服务销毁 = 页面观测中断），时长不丢
        runCatching { emitPageEvents(pageTracker.flush(System.currentTimeMillis())) }
        // 服务是「被正常销毁」而不是「被冻结」：清掉心跳，否则下次进设置页会误报进程被冻结
        HeartbeatWatchdog.onServiceStopped(this)
        removeCurrentDanmaku()
        // 清理自动记账浮窗与协程，避免服务重建后残留窗口
        runCatching { autoBookEngine.release() }
        // 清空静态引用,防止持有已销毁的 Service 实例
        ActionExecutor.accessibilityService = null
        super.onDestroy()
    }
}
