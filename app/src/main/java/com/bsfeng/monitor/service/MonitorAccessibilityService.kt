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
import com.bsfeng.monitor.util.ActivityNames
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters

/** 时间线日志标签 — grep 此标签即可看到干净的用户操作时间线 */
private const val TL = "MonitorTimeline"

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

    internal val recordCollector: RecordCollector
        get() = (application as MonitorApp).container.recordCollector

    /**
     * 页面轨迹状态机：当前页面指纹 + 进入时间（指纹口径见 [PageTracker]）。
     *
     * 懒加载是为了拿到 attach 之后的 `packageName` 作为自身包名 —— 本 App 内的 Tab 切换
     * 不产生 PAGE 记录（真机表现为每秒刷 4 条），过滤收口在 PageTracker 内部。
     */
    internal val pageTracker: PageTracker by lazy { PageTracker(selfPackage = packageName) }

    /** WINDOWS_CHANGED 上次处理时刻（1 秒合并窗口） */
    internal var windowsChangedAtMs: Long = 0L

    internal var lastPackageName: String? = null
    internal var lastActivityClass: String? = null

    /** [lastActivityClass] 所属包名：换了 App 旧的 Activity 名就无意义，必须清空 */
    private var lastActivityPackage: String? = null
    private var lastSwitchTimeMs: Long = System.currentTimeMillis()
    private var lastPageTitle: String? = null  // 上一级页面标题
    /** 系统桌面包名（缓存；解析失败留空，不重复尝试）。 */
    private var homePackage: String? = null

    internal fun isHomeApp(pkg: String): Boolean {
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

    internal fun isSystemUi(packageName: String): Boolean = isSystemUiPackage(packageName)

    private val skipEngine: SkipRuleEngine by lazy {
        (application as MonitorApp).container.skipRuleEngine
    }

    /**
     * 自动记账引擎：在微信/支付宝支付结果页上弹确认浮窗。
     *
     * 懒加载 —— 服务启动时不会触碰记账模块，只有真正遇到支付页才会初始化。
     */
    internal val autoBookEngine: AutoBookEngine by lazy { AutoBookEngine(this) }

    /** 是否在桌面（防止重复触发回桌面事件） */
    internal var isOnHomeScreen: Boolean = false

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

    /** 输出干净的时间线日志：adb logcat -v time | grep MonitorTimeline */
    internal fun timeline(emoji: String, message: String) {
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
     * 处理窗口内容变化事件 → 转发给开屏跳过引擎（实现在 AdSkipEventBridge）。
     *
     * 开屏广告的跳过按钮通常在广告加载完成后才出现，此时节点树必然变化产生
     * CONTENT_CHANGED 事件；转发后引擎能在监控窗口内立即扫描而非等轮询。
     */
    private fun handleContentChanged(event: AccessibilityEvent) = AdSkipEventBridge.onContentChanged(
        event = event, isSystemUi = ::isSystemUi, isHomeApp = ::isHomeApp, skipEngine = skipEngine,
    )

    // ========== Window Change ==========
    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
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

        // ===== 开屏广告跳过（转发层见 AdSkipEventBridge）=====
        AdSkipEventBridge.onWindowStateChanged(
            service = this,
            packageName = packageName,
            className = className,
            appSwitchedFrom = lastPackageName,
            skipEngine = skipEngine,
        )

        lastSwitchTimeMs = now
        // 同引擎：widget 类名的窗口事件（Toast/弹窗）不能覆盖"当前 Activity"，
        // 否则页面快照会记下错误的 Activity，写规则时按错误界面名写
        val appSwitched = packageName != lastActivityPackage
        lastActivityClass = ActivityNames.resolveActivityClass(lastActivityClass ?: "", className, appSwitched)
        lastActivityPackage = packageName

        // ===== 页面轨迹（指纹档）：窗口事件的 Activity 变化是权威信号，不等轮询确认 =====
        // 顺手读一次当前窗口的可见文本与窗口标题：ENTER 记录一出生就带标题，不用等轮询补；
        // 指纹仍按「包名 + Activity + 文本」计算，与轮询路径口径一致（PageTracker 内部视为
        // 同一页的就绪度变化，不会多记一条）。
        val pageRoot = rootInActiveWindow
        val pageTexts = collectVisibleTexts(pageRoot)
        val windowTitle = pageRoot?.window?.title?.toString()
        pageRoot?.recycle()
        // 窗口事件自带的文本：TYPE_WINDOW_STATE_CHANGED 的 event.text 第一个通常就是标题栏文字，
        // 比「窗口标题（=应用名）」和「遍历节点找文本」都更接近用户看到的页面名，且不用对照表。
        val eventTexts = event.text?.mapNotNull { it?.toString()?.takeIf { t -> t.isNotBlank() } }
            ?: emptyList()
        observePage(
            packageName,
            lastActivityClass,
            pageTexts,
            AppUtils.getAppName(packageName),
            now,
            PageEvent.SOURCE_WINDOW_EVENT,
            windowTitle,
            eventTexts,
        )

    }

    override fun onInterrupt() {
        // 服务被系统中断
    }

    companion object {
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
