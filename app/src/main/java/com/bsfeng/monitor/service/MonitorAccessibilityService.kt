package com.bsfeng.monitor.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.event.AppSessionManager
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.event.NavChangeResult
import com.bsfeng.monitor.event.NavigationTracker
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters

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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        DiagnosticCounters.accessibilityEventCount++
        DiagnosticCounters.lastAccessibilityEventType = event.eventType.toString()
        DiagnosticCounters.lastAccessibilityPackage = event.packageName?.toString() ?: ""
        Log.d("MonitorService", "onAccessibilityEvent: type=${event.eventType}, pkg=${event.packageName}")

        // 只处理窗口切换事件（App 切换 / 页面跳转）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(event)
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

        // 提取页面标题（如聊天对象名、设置项名、页面标题等）
        val pageTitle = event.text?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: extractTitleFromNode(event.source)

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
                val durationText = if (duration >= 60_000L) formatDuration(duration) else ""
                val titleSuffix = if (pageTitle != null) "（$pageTitle）" else ""
                val summary = if (lastPackageName != null) {
                    val base = "从 $fromAppName 切换到 $appName$titleSuffix"
                    if (durationText.isNotBlank()) "$base（停留 $durationText）" else base
                } else {
                    "打开了 $appName$titleSuffix"
                }
                Log.d("MonitorService", "  -> APP_FOREGROUND for $packageName")
                EventCollector.emit(EventType.APP_FOREGROUND, mapOf(
                    "package_name" to packageName,
                    "app_name" to appName,
                    "page_title" to (pageTitle ?: ""),
                    "activity_name" to className.substringAfterLast('.'),
                    "activity_class" to className,
                    "from_package" to (lastPackageName ?: ""),
                    "from_activity" to (lastActivityClass ?: ""),
                    "foreground_duration_ms" to duration.toString()
                ), summary = summary)

                val navResult = NavigationTracker.onAppSwitch(packageName, appName)
                emitNavigationEvent(navResult, packageName, appName, pageTitle, className)
            }
        }

        // activity_change: 同 App 内界面切换
        if (packageName == lastPackageName && className != lastActivityClass) {
            val shortClass = className.substringAfterLast('.')
            val desc = describeActivity(shortClass, packageName)
            val duration = now - lastSwitchTimeMs

            val base = if (pageTitle != null && pageTitle != lastPageTitle) {
                "在 $appName 中进入「$pageTitle」"
            } else if (lastPageTitle != null && pageTitle == null) {
                "在 $appName 中$desc（$lastPageTitle）"
            } else {
                "在 $appName 中进入$desc"
            }

            val durationText = if (duration >= 10_000L) formatDuration(duration) else ""
            val summary = if (durationText.isNotBlank()) "$base（停留 $durationText）" else base
            Log.d("MonitorService", "  -> ACTIVITY_CHANGE for $className")
            EventCollector.emit(EventType.ACTIVITY_CHANGE, mapOf(
                "package_name" to packageName,
                "app_name" to appName,
                "page_title" to (pageTitle ?: ""),
                "from_page_title" to (lastPageTitle ?: ""),
                "from_activity" to (lastActivityClass ?: ""),
                "to_activity" to className,
                "to_class" to className
            ), summary = summary)

            val navResult = NavigationTracker.onActivityChange(
                packageName, appName, className, pageTitle
            )
            emitNavigationEvent(navResult, packageName, appName, pageTitle, className)
        }

        // 保留 updateKillDetectorTargets 为空调用（保持兼容）
        updateKillDetectorTargets(packageName)

        lastPackageName = packageName
        lastActivityClass = className
        lastPageTitle = pageTitle
        lastSwitchTimeMs = now
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
        val summary = if (session != null) "回到 $appName（离开 $durationText）" else "回到 $appName"
        Log.d("MonitorService", "  -> returning from away for $appName" +
                if (session != null) ", session found" else ", no session")

        EventCollector.emit(EventType.APP_FOREGROUND, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "page_title" to (pageTitle ?: ""),
            "activity_name" to className.substringAfterLast('.'),
            "activity_class" to className,
            "from_package" to "",
            "returning" to "true",
            "duration_away_ms" to durationAway.toString(),
            "duration_away" to durationText,
            "previous_activity" to (session?.lastActivityClass ?: ""),
            "previous_page" to (session?.lastPageTitle ?: "")
        ), summary = summary)

        if (session != null) {
            handlePageReturn(session, packageName, appName, className, pageTitle, durationAway, durationText)
        } else {
            val navResult = NavigationTracker.onAppSwitch(packageName, appName)
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
        val summary = "回到 $appName（离开 $durationText）"
        Log.d("MonitorService", "  -> returning to session for $appName, away for $durationText")
        EventCollector.emit(EventType.APP_FOREGROUND, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "page_title" to (pageTitle ?: ""),
            "activity_name" to className.substringAfterLast('.'),
            "activity_class" to className,
            "from_package" to (lastPackageName ?: ""),
            "from_app_name" to fromAppName,
            "returning" to "true",
            "duration_away_ms" to durationAway.toString(),
            "duration_away" to durationText,
            "previous_activity" to (session.lastActivityClass ?: ""),
            "previous_page" to (session.lastPageTitle ?: "")
        ), summary = summary)
        AppSessionManager.clearSession(packageName)

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
            EventCollector.emit(EventType.RETURN_TO_PAGE, mapOf(
                "package_name" to packageName,
                "app_name" to appName,
                "page_title" to pageTitle,
                "previous_page_title" to session.lastPageTitle,
                "duration_away_ms" to durationAway.toString(),
                "duration_away" to durationText,
                "nav_path" to NavigationTracker.getPathAsString(packageName),
                "activity_class" to className
            ), summary = returnSummary)
            val restoredPath = NavigationTracker.getCurrentPath(packageName)
            if (restoredPath.isNotEmpty()) {
                emitNavigationEvent(NavChangeResult(restoredPath, true, 0),
                    packageName, appName, pageTitle, className)
            }
        } else {
            val navResult = NavigationTracker.onAppSwitch(packageName, appName)
            emitNavigationEvent(navResult, packageName, appName, pageTitle, className)
        }
    }

    // ========== Navigation Event ==========

    private fun emitNavigationEvent(
        result: NavChangeResult,
        packageName: String,
        appName: String,
        pageTitle: String?,
        className: String?
    ) {
        if (!result.isNewPath) return
        if (result.path.isEmpty()) return

        val path = result.path
        val pathString = path.joinToString(" > ") { it.name }
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
        EventCollector.emit(EventType.NAVIGATION_CHANGE, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "page_title" to (pageTitle ?: ""),
            "nav_path" to pathString,
            "nav_depth" to depth.toString(),
            "nav_index" to result.changedIndex.toString(),
            "activity_class" to (className ?: "")
        ) + levelMap, summary = summary)
    }

    // ========== Title Extraction ==========

    private fun extractTitleFromNode(source: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (source == null) return null
        val texts = mutableListOf<String>()
        try {
            traverseForTitle(source, texts, max = 3)
        } catch (_: Exception) { /* 安全忽略 */ }
        return texts.firstOrNull()
    }

    private fun traverseForTitle(
        node: android.view.accessibility.AccessibilityNodeInfo,
        texts: MutableList<String>,
        max: Int,
        depth: Int = 0
    ) {
        if (depth > 3 || texts.size >= max) return
        node.text?.toString()?.takeIf { it.length in 4..50 && !texts.contains(it) }?.let {
            texts.add(it)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseForTitle(it, texts, max, depth + 1) }
        }
    }

    // ========== Description Helpers ==========

    private fun describeActivity(shortClass: String, packageName: String): String {
        return when {
            shortClass.contains("ChattingUI", ignoreCase = true) -> "聊天界面"
            shortClass.contains("Conversation", ignoreCase = true) ||
                shortClass.contains("LauncherUI", ignoreCase = true) -> "聊天列表"
            shortClass.contains("SnsTimeLineUI", ignoreCase = true) ||
                shortClass.contains("SnsComment", ignoreCase = true) -> "朋友圈"
            shortClass.contains("BizTimeLineUI", ignoreCase = true) ||
                shortClass.contains("BizChattingUI", ignoreCase = true) -> "公众号"
            shortClass.contains("AppBrandUI", ignoreCase = true) ||
                shortClass.contains("MiniProgram", ignoreCase = true) -> "小程序"
            shortClass.contains("Wallet", ignoreCase = true) ||
                shortClass.contains("PayUI", ignoreCase = true) -> "支付页面"
            shortClass.contains("Finder", ignoreCase = true) ||
                shortClass.contains("VideoCollection", ignoreCase = true) -> "视频号"
            shortClass.contains("AddressUI", ignoreCase = true) ||
                shortClass.contains("Contact", ignoreCase = true) -> "通讯录"
            shortClass.contains("Video", ignoreCase = true) ||
                shortClass.contains("Player", ignoreCase = true) -> "视频播放"
            shortClass.contains("Search", ignoreCase = true) -> "搜索页面"
            shortClass.contains("Setting", ignoreCase = true) -> "设置页面"
            shortClass.contains("Album", ignoreCase = true) ||
                shortClass.contains("Gallery", ignoreCase = true) -> "相册"
            shortClass.contains("Browser", ignoreCase = true) ||
                shortClass.contains("WebView", ignoreCase = true) -> {
                if (packageName == "com.tencent.mm") "阅读文章"
                else "网页浏览"
            }
            shortClass.contains("Order", ignoreCase = true) ||
                shortClass.contains("Confirm", ignoreCase = true) -> "订单页面"
            shortClass.contains("Detail", ignoreCase = true) &&
                packageName.contains("taobao", ignoreCase = true) -> "商品详情"
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
