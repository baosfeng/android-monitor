package com.bsfeng.monitor.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.event.ScreenClassifier
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters

class MonitorAccessibilityService : AccessibilityService() {

    private var lastPackageName: String? = null
    private var lastActivityClass: String? = null
    private var lastSwitchTimeMs: Long = System.currentTimeMillis()
    private var lastPageTitle: String? = null  // 上一级页面标题

    // 去抖：防止高频事件刷屏
    private var lastClickTimeMs: Long = 0L
    private val DEBOUNCE_CLICK_MS = 3_000L         // 点击事件去抖 3 秒

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        DiagnosticCounters.accessibilityEventCount++
        DiagnosticCounters.lastAccessibilityEventType = event.eventType.toString()
        DiagnosticCounters.lastAccessibilityPackage = event.packageName?.toString() ?: ""
        Log.d("MonitorService", "onAccessibilityEvent: type=${event.eventType}, pkg=${event.packageName}")
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotification(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleClick(event)
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                handleLongClick(event)
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                handleSelected(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleTextSelection(event)
            }
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

        // 过滤系统界面
        if (isSystemUi(packageName)) {
            DiagnosticCounters.accessibilityFilteredCount++
            Log.d("MonitorService", "handleWindowChange: filtered as system UI: $packageName")
            return
        }

        Log.d("MonitorService", "handleWindowChange: pkg=$packageName, cls=$className, lastPkg=$lastPackageName")

        val duration = if (lastPackageName != null) now - lastSwitchTimeMs else 0L
        val appName = AppUtils.getAppName(packageName)

        // 提取页面标题（如聊天对象名、设置项名、页面标题等）
        val pageTitle = event.text?.joinToString("")?.takeIf { it.isNotBlank() }

        // app_foreground: App 切换
        if (packageName != lastPackageName) {
            val fromAppName = if (lastPackageName != null) AppUtils.getAppName(lastPackageName!!) else ""
            val durationText = if (duration >= 60_000L) formatDuration(duration) else ""
            val titleSuffix = if (pageTitle != null) "（$pageTitle）" else ""
            val summary = if (lastPackageName != null) {
                val base = "从 $fromAppName 切换到 $appName$titleSuffix"
                if (durationText.isNotBlank()) "$base（停留 $durationText）" else base
            } else {
                "打开了 $appName$titleSuffix"
            }
            Log.d("MonitorService", "  -> emitting APP_FOREGROUND for $packageName")
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
        }

        // activity_change: 同 App 内界面切换（二级/三级页面）
        if (packageName == lastPackageName && className != lastActivityClass) {
            val shortClass = className.substringAfterLast('.')
            val desc = describeActivity(shortClass, packageName)

            // 构建带页面标题的友好描述
            val base = if (pageTitle != null && pageTitle != lastPageTitle) {
                when {
                    // 微信：用页面标题作为聊天对象名或页面名
                    shortClass.contains("ChattingUI") -> "在 微信 中打开与 $pageTitle 的聊天"
                    shortClass.contains("SnsTimeLineUI") ||
                        shortClass.contains("SnsComment") -> "在 微信 中查看 $pageTitle"
                    shortClass.contains("AppBrandUI") ||
                        shortClass.contains("MiniProgram") -> "在 微信 中打开小程序：$pageTitle"
                    shortClass.contains("BizTimeLineUI") ||
                        shortClass.contains("BizChattingUI") -> "在 微信 中阅读「$pageTitle」"
                    shortClass.contains("Wallet") ||
                        shortClass.contains("PayUI") -> "在 微信 中使用「$pageTitle」"
                    else -> "在 $appName 中进入「$pageTitle」"
                }
            } else if (lastPageTitle != null && pageTitle == null) {
                "在 $appName 中$desc（$lastPageTitle）"
            } else {
                "在 $appName 中进入$desc"
            }

            val durationText = if (duration >= 10_000L) formatDuration(duration) else ""
            val summary = if (durationText.isNotBlank()) "$base（停留 $durationText）" else base
            Log.d("MonitorService", "  -> emitting ACTIVITY_CHANGE for $className")
            EventCollector.emit(EventType.ACTIVITY_CHANGE, mapOf(
                "package_name" to packageName,
                "app_name" to appName,
                "page_title" to (pageTitle ?: ""),
                "from_page_title" to (lastPageTitle ?: ""),
                "from_activity" to (lastActivityClass ?: ""),
                "to_activity" to className,
                "to_class" to className
            ), summary = summary)
        }

        // screen_content_detected: 界面内容识别（全应用覆盖）
        try {
            val content = ScreenClassifier.classify(
                packageName, className, event.text, event.source
            )
            if (content != null) {
                val screenDesc = describeScreenType(content.screenType)
                val titleSuffix = if (pageTitle != null) "（$pageTitle）" else ""
                val summary = "在 $appName 中$screenDesc$titleSuffix"
                Log.d("MonitorService", "  -> emitting SCREEN_CONTENT_DETECTED: type=${content.screenType}")
                EventCollector.emit(EventType.SCREEN_CONTENT_DETECTED, mapOf(
                    "package_name" to packageName,
                    "app_name" to appName,
                    "page_title" to (pageTitle ?: ""),
                    "screen_title" to (content.screenTitle ?: ""),
                    "screen_type" to content.screenType,
                    "activity_class" to className,
                    "confidence" to content.confidence.toString()
                ), summary = summary)
            }
        } catch (_: Exception) {
            // 提取失败不影响主流程
        }

        // 键盘检测
        checkKeyboard(packageName)

        // 页面内容摘要 + URL 提取
        extractPageContent(packageName, appName, pageTitle, className)
        extractUrlIfBrowser(packageName, appName, pageTitle, className)

        lastPackageName = packageName
        lastActivityClass = className
        lastPageTitle = pageTitle
        lastSwitchTimeMs = now
    }

    // ========== Click Event ==========

    private fun handleClick(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // 去抖：3 秒内只记一次
        if (now - lastClickTimeMs < DEBOUNCE_CLICK_MS) return
        if (isSystemUi(packageName)) return

        // 提取按钮文字
        val buttonText = extractClickText(event) ?: return
        if (buttonText.length > 50) return  // 太长的不可能是按钮文字

        lastClickTimeMs = now

        val appName = AppUtils.getAppName(packageName)
        val viewType = event.className?.toString()?.substringAfterLast('.') ?: ""
        val viewId = event.source?.viewIdResourceName?.takeIf { it.isNotBlank() } ?: ""
        val summary = "在 $appName 中点击「$buttonText」"

        Log.d("MonitorService", "  -> emitting VIEW_CLICKED: $buttonText in $packageName")
        EventCollector.emit(EventType.VIEW_CLICKED, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "button_text" to buttonText,
            "view_type" to viewType,
            "view_id" to viewId
        ), summary = summary)
    }

    /**
     * 从点击事件中提取按钮文字，跳过输入框等敏感内容
     */
    private fun extractClickText(event: AccessibilityEvent): String? {
        // 尝试从 source 节点获取
        val source = event.source
        if (source != null) {
            val className = source.className?.toString() ?: ""

            // 跳过输入框、密码框、搜索框（保护隐私）
            if (className.contains("EditText", ignoreCase = true) ||
                className.contains("SearchView", ignoreCase = true)) return null

            // 优先取 text，其次 contentDescription
            val text = source.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val result = text ?: desc
            if (!result.isNullOrBlank()) return result
        }

        // 兜底：取 event.text
        return event.text?.joinToString("")?.takeIf { it.isNotBlank() }
    }

    // ========== Long Click ==========

    private fun handleLongClick(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (isSystemUi(packageName)) return

        val buttonText = extractClickText(event) ?: return
        if (buttonText.length > 50) return

        val appName = AppUtils.getAppName(packageName)
        val summary = "在 $appName 中长按「$buttonText」"
        EventCollector.emit(EventType.VIEW_LONG_CLICKED, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "button_text" to buttonText
        ), summary = summary)
    }

    // ========== View Selected ==========

    private fun handleSelected(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (isSystemUi(packageName)) return

        val text = extractClickText(event) ?: return
        val appName = AppUtils.getAppName(packageName)
        val summary = "在 $appName 中选择了「$text」"
        EventCollector.emit(EventType.VIEW_SELECTED, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "selected_text" to text
        ), summary = summary)
    }

    // ========== Text Selection ==========

    private fun handleTextSelection(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (isSystemUi(pkg)) return
        val appName = AppUtils.getAppName(pkg)
        EventCollector.emit(EventType.TEXT_SELECTED, mapOf(
            "package_name" to pkg, "app_name" to appName
        ), summary = "在 $appName 中选中了文字")
    }

    /** 在 handleWindowChange 中调用 */
    private fun detectDialog(pkg: String, appName: String, cls: String, title: String?) {
        val isDialog = cls.contains("Dialog", ignoreCase = true) ||
                cls.contains("Popup", ignoreCase = true) ||
                cls.contains("Alert", ignoreCase = true)
        if (isDialog && title != null) {
            EventCollector.emit(EventType.DIALOG_DETECTED, mapOf(
                "package_name" to pkg, "app_name" to appName,
                "dialog_title" to title
            ), summary = "$appName 弹出「$title」")
        }
    }

    // ========== Keyboard Detection ==========

    private var imePackage: String? = null  // 缓存当前输入法包名

    /** 在 handleWindowChange 中调用 */
    private fun checkKeyboard(packageName: String) {
        if (imePackage == null) {
            imePackage = try {
                android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/')
            } catch (_: Exception) { null }
        }
        if (packageName == imePackage) {
            EventCollector.emit(EventType.KEYBOARD_SHOWN, summary = "键盘已弹出")
        } else if (lastPackageName == imePackage && packageName != imePackage) {
            EventCollector.emit(EventType.KEYBOARD_HIDDEN, summary = "键盘已收起")
        }
    }

    // ========== URL & Page Content ==========

    private var lastPageContentHash: String? = null

    private fun extractUrlIfBrowser(pkg: String, appName: String, title: String?, cls: String) {
        val isBrowser = cls.contains("Browser", ignoreCase = true) ||
                cls.contains("WebView", ignoreCase = true) ||
                pkg == "com.android.chrome" || pkg == "org.mozilla.firefox"
        if (!isBrowser || title == null) return
        val url = when {
            title.startsWith("http://") || title.startsWith("https://") -> title
            title.contains(".com/") || title.contains(".cn/") -> "https://$title"
            else -> null
        }
        if (url != null) {
            val simple = url.removePrefix("https://").removePrefix("http://").substringBefore("?").take(80)
            EventCollector.emit(EventType.URL_VISITED, mapOf(
                "package_name" to pkg, "app_name" to appName, "url" to url
            ), summary = "在 $appName 中浏览 $simple")
        }
    }

    private fun extractPageContent(pkg: String, appName: String, title: String?, cls: String) {
        if (System.currentTimeMillis() - lastSwitchTimeMs < 60_000L) return
        val texts = mutableListOf<String>()
        if (!title.isNullOrBlank()) texts.add(title)
        try { getRootInActiveWindow()?.let { r -> traverseNodeText(r, texts, 3) } } catch (_: Exception) {}
        if (texts.isEmpty()) return
        val hash = texts.joinToString("|")
        if (hash == lastPageContentHash) return
        lastPageContentHash = hash
        EventCollector.emit(EventType.PAGE_CONTENT, mapOf(
            "package_name" to pkg, "app_name" to appName, "texts" to texts.joinToString(" | ")
        ), summary = "在 $appName 中：${texts.joinToString(" | ").take(80)}")
    }

    private fun traverseNodeText(n: android.view.accessibility.AccessibilityNodeInfo,
                                  t: MutableList<String>, max: Int) {
        if (t.size >= max) return
        try {
            n.text?.toString()?.takeIf { it.length in 4..99 && !t.contains(it) }?.let { t.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { traverseNodeText(it, t, max) }
        } catch (_: Exception) {}
    }

    // ========== Notification ==========

    private fun handleNotification(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val text = event.text.joinToString("")
        val category = extractCategory(event)
        val appName = AppUtils.getAppName(packageName)

        // 无文本内容 → 通知被移除
        if (text.isBlank()) {
            EventCollector.emit(EventType.NOTIFICATION_REMOVED, mapOf(
                "package_name" to packageName,
                "app_name" to appName,
                "category" to category
            ), summary = "$appName 的通知已清除")
            return
        }

        val summary = "收到 $appName 的通知"
        EventCollector.emit(EventType.NOTIFICATION_RECEIVED, mapOf(
            "package_name" to packageName,
            "app_name" to appName,
            "category" to category,
            "content" to text.take(100)
        ), summary = summary)
    }

    // ========== Description Helpers ==========

    /**
     * 将短类名/包名转为中文描述
     */
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
                shortClass.contains("WebView", ignoreCase = true) -> "网页浏览"
            shortClass.contains("Order", ignoreCase = true) ||
                shortClass.contains("Confirm", ignoreCase = true) -> "订单页面"
            shortClass.contains("Detail", ignoreCase = true) &&
                packageName.contains("taobao", ignoreCase = true) -> "商品详情"
            packageName == "com.tencent.mm" -> "微信"
            else -> shortClass
        }
    }

    /**
     * 将屏幕类型转为中文描述
     */
    private fun describeScreenType(screenType: String): String {
        return when (screenType) {
            "chat" -> "正在聊天"
            "feed" -> "浏览动态"
            "chat_list" -> "查看聊天列表"
            "article" -> "阅读文章"
            "video" -> "观看视频"
            "search" -> "搜索内容"
            "settings" -> "调整设置"
            "mini_program" -> "使用小程序"
            "payment" -> "进行支付"
            "official_account" -> "阅读公众号文章"
            "moments_comment" -> "查看朋友圈"
            "live_stream" -> "观看直播"
            "product_detail" -> "查看商品详情"
            "checkout" -> "提交订单"
            "cart" -> "查看购物车"
            "shopping" -> "浏览商品"
            "recording" -> "拍摄视频"
            "contacts" -> "查看通讯录"
            "home" -> "浏览首页"
            else -> "浏览页面"
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

    private fun extractCategory(event: AccessibilityEvent): String {
        return when (event.packageName?.toString()) {
            "com.tencent.mm" -> "social"
            "com.google.android.gm", "com.android.email" -> "email"
            else -> "other"
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
        // 服务已连接，可以初始化
    }
}
