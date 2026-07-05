package com.bsfeng.monitor.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.click.AppClickHandlerRegistry
import com.bsfeng.monitor.event.AppSessionManager
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.event.NavChangeResult
import com.bsfeng.monitor.event.NavigationTracker
import com.bsfeng.monitor.pipeline.EventSource
import com.bsfeng.monitor.pipeline.RawEventData
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DebugConfig
import com.bsfeng.monitor.util.DiagnosticCounters

/** 时间线日志标签 — grep 此标签即可看到干净的用户操作时间线 */
private const val TL = "MonitorTimeline"

/**
 * 无障碍服务 — 仅追踪导航/页面跳转。
 *
 * 只处理 TYPE_WINDOW_STATE_CHANGED 事件，其他交互事件全部忽略。
 */
class MonitorAccessibilityService : AccessibilityService() {

    private var lastPackageName: String? = null
    private var lastActivityClass: String? = null
    private var lastSwitchTimeMs: Long = System.currentTimeMillis()
    private var lastPageTitle: String? = null  // 上一级页面标题
    private var isAway: Boolean = false  // 用户是否离开了当前 App（去桌面/系统界面）
    private var homePackage: String? = null  // 系统桌面包名（缓存）

    /** 检测当前包名是否为系统桌面 */
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

    private var lastClickTimeMs: Long = 0  // 点击事件防抖
    private var lastClickText: String? = null  // 上次点击文本（防重复）
    private var lastNavPathMs: Long = 0  // 导航路径防抖
    private var lastNavPath: String = ""  // 上一次的导航路径

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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClick(event)
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

        // ===== 检测桌面（Home）：离开时保存会话，但不追踪桌面 =====
        if (isHomeApp(packageName)) {
            if (packageName != lastPackageName && lastPackageName != null) {
                saveCurrentSession()
            }
            isAway = true
            Log.d("MonitorService", "handleWindowChange: going to home screen, isAway=true")
            return
        }

        // 过滤系统界面
        if (isSystemUi(packageName)) {
            DiagnosticCounters.accessibilityFilteredCount++
            Log.d("MonitorService", "handleWindowChange: filtered as system UI: $packageName")
            return
        }

        val appName = AppUtils.getAppName(packageName)

        // 提取页面内容（标题 + 页面关键文本：发帖人、文章标题、商品名等）
        // 跳过本 App 自身的事件——界面显示的是已存事件的回显，无新内容
        val pageContent = if (packageName == "com.bsfeng.monitor") {
            Pair(null, emptyList())
        } else {
            extractPageContent(event)
        }
        val pageTitle = pageContent.first
        val contentTexts = pageContent.second

        // ===== 从 Home 回到当前 App（恢复会话） =====
        if (isAway && packageName == lastPackageName) {
            isAway = false
            handleReturnFromAway(packageName, appName, className, pageTitle, now)
            AppSessionManager.clearSession(packageName)
        }

        // 保存当前 App 的会话（离开时记录导航上下文）
        if (packageName != lastPackageName && lastPackageName != null) {
            saveCurrentSession()
        }

        // app_foreground: App 切换（含会话返回检测）
        if (packageName != lastPackageName) {
            val fromAppName = if (lastPackageName != null) AppUtils.getAppName(lastPackageName!!) else ""
            val duration = if (lastPackageName != null) now - lastSwitchTimeMs else 0L

            // 检查是否回到之前保存的会话
            val session = AppSessionManager.restoreSession(packageName)
            if (session != null) {
                handleReturnWithSession(session, packageName, appName, className, pageTitle, fromAppName, now)
            } else {
                // ===== 全新 App 切换 =====
                // NAVIGATION_CHANGE 已有 📍 输出，不再重复输出 📱
                val navResult = NavigationTracker.onAppSwitch(packageName, appName, className)
                emitNavigationEvent(
                    navResult, packageName, appName, pageTitle, className,
                    extra = mapOf(
                        "from_app_name" to fromAppName,
                        "foreground_duration_ms" to duration.toString()
                    )
                )
            }
        }

        // activity_change: 同 App 内界面切换（只需更新导航路径，由 NAVIGATION_CHANGE 统一呈现）
        if (packageName == lastPackageName && className != lastActivityClass) {
            val shortClass = className.substringAfterLast('.')
            val desc = describeActivity(shortClass, packageName)

            // 更新导航栈
            val navResult = NavigationTracker.onActivityChange(
                packageName, appName, className, pageTitle
            )

            // 只在导航路径确实变化时才发射 NAVIGATION_CHANGE（不再单独发射 ACTIVITY_CHANGE）
            if (navResult.isNewPath) {
                emitNavigationEvent(
                    navResult, packageName, appName, pageTitle, className,
                    extra = buildContentTextsMap(contentTexts) + mapOf(
                        "to_activity_name" to desc
                    )
                )
            }
        }

        // 保留 updateKillDetectorTargets 为空调用（保持兼容）
        updateKillDetectorTargets(packageName)

        lastPackageName = packageName
        lastActivityClass = className
        lastPageTitle = pageTitle
        lastSwitchTimeMs = now
    }

    // ========== View Click ==========

    /** 处理点击事件：提取点击了什么元素上的什么文本 + 当前页面内容 */
    private fun handleViewClick(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // 防抖：500ms 内忽略
        if (now - lastClickTimeMs < 500) return

        // 过滤系统界面和桌面
        if (isSystemUi(packageName) || isHomeApp(packageName)) return

        // 提取点击元素的文本
        val clickText = event.text?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: try {
                event.source?.let { src ->
                    val t = src.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: src.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    src.recycle()
                    t
                }
            } catch (_: Exception) { null }

        // ===== App 专用处理器：优先替换无意义的点击文本（时间戳等） =====
        val appHandler = AppClickHandlerRegistry.getHandler(packageName)
        val handlerResolved = appHandler?.resolveClickDisplayText(event, clickText)
        DebugConfig.log("handleViewClick: pkg=$packageName clickText=$clickText handler=${appHandler?.javaClass?.simpleName} resolved=$handlerResolved")

        // 提取 viewIdResourceName
        val viewId = try {
            event.source?.let { src -> val id = src.viewIdResourceName; src.recycle(); id }
        } catch (_: Exception) { null }

        // 重复文本过滤：3秒内相同文本忽略
        if (clickText != null && clickText == lastClickText && now - lastClickTimeMs < 3000) return
        if (clickText.isNullOrBlank() && viewId == null) return

        lastClickTimeMs = now
        lastClickText = clickText

        val appName = AppUtils.getAppName(packageName)

        // ===== 同时抓取当前页面上下文内容（视频标题、帖子正文等） =====
        val pageTexts = try {
            val root = rootInActiveWindow
            DebugConfig.log("handleViewClick: rootInActiveWindow=${if (root != null) "non-null(pkg=${root.packageName})" else "null"}")
            if (root != null) {
                val texts = linkedSetOf<String>()
                val rootPkg = root.packageName?.toString()
                if (rootPkg == packageName) {
                    // 用 quick collect 只提取有意义的长文本
                    collectContentTexts(root, texts, max = 30, depth = 0, maxDepth = 12)
                }
                root.recycle()
                // 过滤掉点击文本自身
                texts.filter { it.length >= 4 && !it.contains(clickText ?: "") }
                    .sortedByDescending { it.length }
                    .take(5)
            } else emptyList()
        } catch (_: Exception) { emptyList() }

        DebugConfig.log("handleViewClick: pageTexts(${pageTexts.size}): ${pageTexts.take(3)}")

        // 点击文本：优先使用 App 专用处理器结果，否则用原始点击文本（不依赖不稳定的页面上下文）
        val displayText = handlerResolved ?: clickText

        val clickSummary = if (displayText != null) {
            "在 $appName 点击了「${displayText.take(30)}」"
        } else {
            "在 $appName 点击了 $viewId"
        }

        timeline("👆", "点击「${displayText?.take(25) ?: viewId ?: "?"}」")
        Log.d("MonitorService", "  -> CLICK: ${displayText ?: viewId ?: "(unknown)"}")
        DebugConfig.log("handleViewClick: final displayText='$displayText' (clickText='$clickText', handlerResolved='$handlerResolved')")

        val extra = mutableMapOf(
            "app_name" to appName,
            "package_name" to packageName,
            "view_id" to (viewId ?: ""),
            "current_activity" to (lastActivityClass ?: ""),
            "current_page" to (lastPageTitle ?: "")
        )
        if (clickText != null) extra["click_text"] = clickText
        // 加入当前页面上下文内容（视频标题、帖子正文等）
        if (pageTexts.isNotEmpty()) {
            for ((i, t) in pageTexts.withIndex()) {
                extra["page_context_${i + 1}"] = t
            }
            Log.d("MonitorService", "  -> page_context(${pageTexts.size}): ${pageTexts.first().take(40)}")
        }

        MonitorApp.instance.getCollector().emit(object : EventSource {
            override fun collectRaw() = RawEventData(
                eventType = EventType.VIEW_CLICKED,
                packageName = packageName,
                className = lastActivityClass,
                summary = clickSummary,
                rawExtra = extra
            )
        })
    }

    // ========== 辅助方法：会话保存与返回处理 ==========

    /** 保存当前 App 的导航状态到会话管理器（离开时调用） */
    private fun saveCurrentSession() {
        val pkg = lastPackageName ?: return
        val oldNavPath = NavigationTracker.saveState(pkg)
        if (oldNavPath.isNotEmpty()) {
            AppSessionManager.saveSession(
                packageName = pkg,
                appName = AppUtils.getAppName(pkg),
                navPath = oldNavPath,
                pageTitle = lastPageTitle,
                activityClass = lastActivityClass
            )
            Log.d("MonitorService", "  -> saved session for ${AppUtils.getAppName(pkg)}, " +
                    "path=${NavigationTracker.getPathAsString(pkg)}")
        }
    }

    /** 从 Home 回到当前 App 时的处理 */
    private fun handleReturnFromAway(
        packageName: String,
        appName: String,
        className: String,
        pageTitle: String?,
        now: Long
    ) {
        val session = AppSessionManager.restoreSession(packageName)
        val durationAway = if (session != null) now - session.leaveTimeMs else 0L
        val durationText = if (session != null) formatDuration(durationAway) else ""
        Log.d("MonitorService", "  -> returning from away for $appName" +
                if (session != null) ", session found" else ", no session")

        if (session != null) {
            handlePageReturn(session, packageName, appName, className, pageTitle, durationAway, durationText)
        } else {
            val navResult = NavigationTracker.onAppSwitch(packageName, appName, className)
            emitNavigationEvent(navResult, packageName, appName, pageTitle, className)
        }
    }

    /** 从另一个 App 回到之前到访过的 App 时的处理 */
    private fun handleReturnWithSession(
        session: com.bsfeng.monitor.event.SessionRecord,
        packageName: String,
        appName: String,
        className: String,
        pageTitle: String?,
        fromAppName: String,
        now: Long
    ) {
        val durationAway = now - session.leaveTimeMs
        val durationText = formatDuration(durationAway)
        Log.d("MonitorService", "  -> returning to session for $appName, away for $durationText")
        AppSessionManager.clearSession(packageName)
        isAway = false

        handlePageReturn(session, packageName, appName, className, pageTitle, durationAway, durationText)
    }

    /** 通用：检测是否回到相同页面，发射 RETURN_TO_PAGE 或重置导航 */
    private fun handlePageReturn(
        session: com.bsfeng.monitor.event.SessionRecord,
        packageName: String,
        appName: String,
        className: String,
        pageTitle: String?,
        durationAway: Long,
        durationText: String
    ) {
        val currentShortClass = className.substringAfterLast('.')
        val previousShortClass = session.lastActivityClass?.substringAfterLast('.')
        val isSamePage = currentShortClass == previousShortClass ||
                className == session.lastActivityClass

        if (isSamePage && pageTitle != null && session.lastPageTitle != null) {
            NavigationTracker.restoreState(packageName, session.navPath)
            val returnSummary = "回到之前浏览的页面「${session.lastPageTitle}」（离开 $durationText）"
            Log.d("MonitorService", "  -> RETURN_TO_PAGE: ${session.lastPageTitle}")
            MonitorApp.instance.getCollector().emit(object : EventSource {
                override fun collectRaw() = RawEventData(
                    eventType = EventType.RETURN_TO_PAGE,
                    packageName = packageName,
                    className = className,
                    summary = returnSummary,
                    rawExtra = mapOf(
                        "app_name" to appName,
                        "page_title" to pageTitle,
                        "previous_page_title" to (session.lastPageTitle ?: ""),
                        "duration_away_ms" to durationAway.toString(),
                        "duration_away" to durationText,
                        "nav_path" to NavigationTracker.getPathAsString(packageName)
                    )
                )
            })
            val restoredPath = NavigationTracker.getCurrentPath(packageName)
            if (restoredPath.isNotEmpty()) {
                emitNavigationEvent(NavChangeResult(restoredPath, true, 0),
                    packageName, appName, pageTitle, className)
            }
        } else {
            val navResult = NavigationTracker.onAppSwitch(packageName, appName, className)
            emitNavigationEvent(navResult, packageName, appName, pageTitle, className)
        }
    }

    // ========== Navigation Event ==========

    private fun emitNavigationEvent(
        result: NavChangeResult,
        packageName: String,
        appName: String,
        pageTitle: String?,
        className: String?,
        extra: Map<String, String> = emptyMap()
    ) {
        if (!result.isNewPath) return
        if (result.path.isEmpty()) return

        val path = result.path
        val pathString = path.joinToString(" > ") { it.name }

        // 防抖：15 秒内相同路径忽略（瞬态中间 Activity 返回时不重复发射）
        val now = System.currentTimeMillis()
        if (pathString == lastNavPath && now - lastNavPathMs < 15000) return
        lastNavPath = pathString
        lastNavPathMs = now

        val depth = path.size

        val levelMap = mutableMapOf<String, String>()
        for (i in path.indices) {
            val level = i + 1
            val node = path[i]
            levelMap["nav_level_${level}"] = node.name
            levelMap["nav_level_${level}_type"] = node.type
        }

        val summary = buildString {
            append("导航：${pathString}")
            if (pageTitle != null && path.lastOrNull()?.name != pageTitle) {
                append("（${pageTitle}）")
            }
        }

        Log.d("MonitorService", "  -> NAVIGATION_CHANGE: ${pathString}")
        timeline("📍", "$pathString")
        MonitorApp.instance.getCollector().emit(object : EventSource {
            override fun collectRaw() = RawEventData(
                eventType = EventType.NAVIGATION_CHANGE,
                packageName = packageName,
                className = className,
                summary = summary,
                rawExtra = mapOf(
                    "app_name" to appName,
                    "page_title" to (pageTitle ?: ""),
                    "nav_path" to pathString,
                    "nav_depth" to depth.toString(),
                    "nav_index" to result.changedIndex.toString()
                ) + levelMap + extra
            )
        })
    }

    // ========== Content Extraction ==========

    /**
     * 从 AccessibilityEvent 中提取页面内容。
     * 返回 (bestTitle, contentTexts)
     * - bestTitle: 最适合作为页面标题的文本
     * - contentTexts: 页面上所有有意义的文本（发帖人、文章标题、商品名等），按长度降序
     */
    private fun extractPageContent(event: AccessibilityEvent): Pair<String?, List<String>> {
        val allTexts = linkedSetOf<String>()

        // 1. 从 event 本身获取
        event.text?.joinToString("")?.takeIf { it.isNotBlank() }?.let {
            allTexts.add(it.trim())
        }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            allTexts.add(it.trim())
        }

        // 2. 从 event.source 节点树遍历（加深到12层，收集最多30个）
        if (event.source != null) {
            collectContentTexts(event.source!!, allTexts, max = 30, depth = 0, maxDepth = 12)
        }

        // 3. 从 rootInActiveWindow 补充（仅当 package 匹配，防止跨 App 文本污染）
        try {
            val root = rootInActiveWindow
            if (root != null && root != event.source) {
                val rootPkg = root.packageName?.toString()
                if (rootPkg == event.packageName?.toString()) {
                    collectContentTexts(root, allTexts, max = 30, depth = 0, maxDepth = 12)
                }
                root.recycle()
            }
        } catch (_: Exception) {}

        if (allTexts.isEmpty()) return Pair(null, emptyList())

        // 按长度降序排列，过滤无意义短文本
        val meaningful = allTexts
            .map { it.trim() }
            .filter { it.length >= 2 && !isNonsenseText(it) }
            .distinct()
            .sortedByDescending { it.length }

        // bestTitle: 优先选最长的 >=6 字符的文本，否则选最长的
        val bestTitle = meaningful.firstOrNull { it.length >= 6 }
            ?: meaningful.firstOrNull()

        // 取前 8 个作为 content_texts
        val topTexts = meaningful.take(8)

        // 日志：打印找到的文本（用于调试 content extraction）
        if (topTexts.isNotEmpty()) {
            Log.d("MonitorService", "  -> texts[${topTexts.size}]: ${topTexts.joinToString(" | ") { it.take(40) }}")
        }

        return Pair(bestTitle, topTexts)
    }

    /** 过滤无意义的占位文本 */
    private fun isNonsenseText(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf(
            "返回", "取消", "确定", "确认", "关闭", "更多", "分享",
            "退出", "保存", "删除", "编辑", "完成", "下一步",
            "back", "cancel", "ok", "close", "more", "share"
        ) || text.all { it.isWhitespace() || it.isDigit() || it == ':' || it == '/' }
    }

    private fun collectContentTexts(
        node: android.view.accessibility.AccessibilityNodeInfo,
        texts: MutableSet<String>,
        max: Int,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || texts.size >= max) return

        // 读取 text
        node.text?.toString()?.takeIf {
            it.isNotBlank() && it.trim().length >= 2
        }?.let { t ->
            val trimmed = t.trim()
            if (trimmed !in texts) texts.add(trimmed)
        }

        // 读取 contentDescription（很多 App 的关键内容在此）
        node.contentDescription?.toString()?.takeIf {
            it.isNotBlank() && it.trim().length >= 2
        }?.let { cd ->
            val trimmed = cd.trim()
            if (trimmed !in texts) texts.add(trimmed)
        }

        // 遍历子节点
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let {
                    collectContentTexts(it, texts, max, depth + 1, maxDepth)
                }
            } catch (_: Exception) { /* 安全忽略单个子节点异常 */ }
        }
    }

    /** 构建 content_text_N 的 rawExtra 映射 */
    private fun buildContentTextsMap(texts: List<String>): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in texts.indices) {
            val key = "content_text_${i + 1}"
            val value = texts[i]
            map[key] = value
            if (i >= 7) break
        }
        return map
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

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}小时${minutes % 60}分钟"
            minutes > 0 -> "${minutes}分钟${seconds % 60}秒"
            seconds > 0 -> "${seconds}秒"
            else -> "不到1秒"
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
                packageName == "com.sohu.inputmethod.sogou" ||
                packageName == "com.baidu.input" ||
                packageName.startsWith("com.android.inputmethod") ||
                packageName == "android"
    }

    override fun onInterrupt() {
        // 服务被系统中断
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return false // 不拦截按键
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务已连接
    }
}
