package com.bsfeng.monitor.domain

import com.bsfeng.monitor.domain.model.NavChangeResult
import com.bsfeng.monitor.domain.model.NavigationNode

/**
 * 导航追踪器 — 为每个 App 维护独立的导航栈。
 *
 * 工作方式：
 * 1. App 切换 → 重置栈为 [app_name]
 * 2. 同 App 内发生 Activity 切换时：
 *    - className 匹配「主页/首页」模式 → 重置到根
 *    - className 已在栈中（返回导航）→ 退栈到该位置
 *    - 否则 → 压入新节点
 * 3. 返回 [NavChangeResult] 供外部处理
 *
 * ⚠️ 这是通用模型，不依赖任何特定 App 的包名/类名规则。
 *    App 特有的「tab 识别」等逻辑由调用方（MonitorAccessibilityService）
 *    通过传参控制。
 */
class NavigationTracker {

    private val stacks = mutableMapOf<String, MutableList<NavigationNode>>()

    // ==================== 公开 API ====================

    /**
     * App 切换时调用 → 重置栈。
     */
    fun onAppSwitch(packageName: String, appName: String, className: String? = null): NavChangeResult {
        val now = System.currentTimeMillis()
        val stack = mutableListOf(
            NavigationNode(
                name = appName,
                type = "app",
                className = className,
                nodeTitle = null,
                timestampMs = now
            )
        )
        stacks[packageName] = stack
        return NavChangeResult(
            path = stack.toList(),
            isNewPath = true,
            changedIndex = 0
        )
    }

    /**
     * 同 App 内 Activity 变化时调用。
     */
    fun onActivityChange(
        packageName: String,
        appName: String,
        className: String,
        pageTitle: String?
    ): NavChangeResult {
        val stack = stacks[packageName]
            ?: return onAppSwitch(packageName, appName)

        val now = System.currentTimeMillis()
        val shortClass = className.substringAfterLast('.')

        // ---- 0. 过滤：跳过通用 Android 布局/视图类（非导航节点） ----
        if (isGenericLayoutName(shortClass)) {
            return NavChangeResult(stack.toList(), false, -1)
        }

        // ---- 1. 主页/首页 → 重置到根 ----
        if (isHomeScreen(className)) {
            if (stack.size == 1) {
                return NavChangeResult(stack.toList(), false, -1)
            }
            val root = stack.first().copy(timestampMs = now)
            stack.clear()
            stack.add(root)
            return NavChangeResult(stack.toList(), true, 0)
        }

        // ---- 1.5 去重 ----
        val lastNode = stack.lastOrNull()
        if (lastNode != null && (lastNode.className == null || lastNode.className.substringAfterLast('.') == shortClass)) {
            return NavChangeResult(stack.toList(), false, -1)
        }

        // ---- 2. 返回导航 ----
        val existingIdx = findInStack(stack, className, pageTitle)
        if (existingIdx >= 0) {
            while (stack.size > existingIdx + 1) {
                stack.removeAt(stack.lastIndex)
            }
            return NavChangeResult(stack.toList(), true, existingIdx)
        }

        // ---- 2.5 根节点保护 ----
        if (stack.size == 1) {
            val nodeName = resolveNodeName(pageTitle, className, packageName)
            if (nodeName == appName || nodeName == "主页" || nodeName == "首页") {
                return NavChangeResult(stack.toList(), false, -1)
            }
            if (nodeName == shortClass || (pageTitle != null && isUiMetadata(pageTitle))) {
                return NavChangeResult(stack.toList(), false, -1)
            }
        }

        // ---- 3. 深层次导航 → 压栈 ----
        val nodeName = resolveNodeName(pageTitle, className, packageName)
        val nodeType = resolveNodeType(className, packageName)

        stack.add(
            NavigationNode(
                name = nodeName,
                type = nodeType,
                className = className,
                nodeTitle = pageTitle,
                timestampMs = now
            )
        )
        return NavChangeResult(
            path = stack.toList(),
            isNewPath = true,
            changedIndex = stack.size - 1
        )
    }

    /**
     * 获取指定 App 的当前导航路径（列表）。
     */
    fun getCurrentPath(packageName: String): List<NavigationNode> {
        return stacks[packageName]?.toList() ?: emptyList()
    }

    /**
     * 获取指定 App 的当前导航路径字符串（面包屑格式）。
     */
    fun getPathAsString(packageName: String, separator: String = " > "): String {
        return stacks[packageName]?.joinToString(separator) { it.name } ?: ""
    }

    // ==================== 状态保存/恢复 ====================

    /**
     * 保存指定 App 的导航栈（深拷贝）。
     */
    fun saveState(packageName: String): List<NavigationNode> {
        return stacks[packageName]?.toList() ?: emptyList()
    }

    /**
     * 恢复指定 App 的导航栈。
     */
    fun restoreState(packageName: String, state: List<NavigationNode>) {
        if (state.isEmpty()) return
        stacks[packageName] = state.toMutableList()
    }

    /**
     * 获取指定 App 的当前导航层级（从 1 开始）。
     */
    fun getCurrentDepth(packageName: String): Int {
        return stacks[packageName]?.size ?: 0
    }

    fun getLevelName(packageName: String, level: Int): String {
        val stack = stacks[packageName] ?: return ""
        return stack.getOrNull(level - 1)?.name ?: ""
    }

    fun clearAll() {
        stacks.clear()
    }

    // ==================== 内部启发式规则 ====================

    private val genericLayoutNames = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout",
        "ScrollView", "HorizontalScrollView", "NestedScrollView",
        "View", "ViewGroup", "SurfaceView", "TextureView",
        "Dialog", "PopupWindow", "PopupMenu",
        "RecyclerView", "ListView", "GridView", "ViewPager",
        "Toolbar", "ActionBar",
        "CoordinatorLayout", "AppBarLayout", "CollapsingToolbarLayout",
        "CardView", "ImageButton", "ImageView",
        "TextView", "EditText", "Button",
        "Switch", "ToggleButton", "CheckBox", "RadioButton",
        "ProgressBar", "SeekBar", "RatingBar",
        "Spinner", "AutoCompleteTextView",
        "DrawerLayout", "SwipeRefreshLayout",
        "TabLayout", "TabWidget",
        "BottomNavigationView", "NavigationView",
        "FragmentContainerView",
        "Toast", "Snackbar"
    )

    private fun isGenericLayoutName(shortClass: String): Boolean {
        return shortClass in genericLayoutNames
    }

    private fun isHomeScreen(className: String): Boolean {
        val homePatterns = listOf(
            "LauncherUI", "HomeActivity", ".Home.", "MainActivity",
            "SplashActivity", "TabActivity", ".Main."
        )
        return homePatterns.any { className.contains(it, ignoreCase = true) }
    }

    private fun findInStack(
        stack: List<NavigationNode>,
        className: String,
        pageTitle: String?
    ): Int {
        val shortClass = className.substringAfterLast('.')
        for (i in (stack.size - 1) downTo 1) {
            val node = stack[i]
            val nodeCls = node.className ?: continue
            if (nodeCls == className) return i
            if (nodeCls.substringAfterLast('.') == shortClass) return i
        }
        return -1
    }

    private fun resolveNodeName(
        pageTitle: String?,
        className: String,
        packageName: String
    ): String {
        if (!pageTitle.isNullOrBlank() && !isUiMetadata(pageTitle)) return pageTitle
        val shortClass = className.substringAfterLast('.')
        return describeShortClass(shortClass) ?: shortClass
    }

    private fun isUiMetadata(text: String): Boolean {
        val lower = text.lowercase()
        if (text.contains("，") || text.contains(",") || text.contains(" | ")) return true
        if (lower.contains("按钮") || lower.contains("已选中") || lower.contains("未选中")
            || lower.contains("tab") || lower.contains("标签")) return true
        if (lower.contains("progress") || lower.contains("loading")
            || lower.contains("toast") || lower.contains("dialog")
            || lower.contains("popup")) return true
        if (text.startsWith("第") && (text.contains("页") || text.contains("个"))) return true
        return false
    }

    private fun resolveNodeType(className: String, packageName: String): String {
        val cls = className.substringAfterLast('.')
        return when {
            cls.contains("LauncherUI", ignoreCase = true) ||
                cls.contains("Home", ignoreCase = true) ||
                cls.contains("Main", ignoreCase = true) -> "app"
            cls.contains("Detail", ignoreCase = true) ||
                cls.contains("Player", ignoreCase = true) ||
                cls.contains("Playback", ignoreCase = true) ||
                cls.contains("ChattingUI", ignoreCase = true) ||
                cls.contains("Article", ignoreCase = true) -> "content"
            cls.contains("Search", ignoreCase = true) ||
                cls.contains("Setting", ignoreCase = true) -> "page"
            cls.contains("Dialog", ignoreCase = true) ||
                cls.contains("Popup", ignoreCase = true) ||
                cls.contains("Alert", ignoreCase = true) -> "overlay"
            else -> "page"
        }
    }

    private fun describeShortClass(shortClass: String): String? {
        return when {
            shortClass.contains("ChattingUI", ignoreCase = true) -> "聊天"
            shortClass.contains("Conversation", ignoreCase = true) ||
                shortClass.contains("LauncherUI", ignoreCase = true) -> "主页"
            shortClass.contains("SnsTimeLineUI", ignoreCase = true) ||
                shortClass.contains("ImproveSnsTimeline", ignoreCase = true) -> "朋友圈"
            shortClass.contains("SnsBrowseUI", ignoreCase = true) ||
                shortClass.contains("SnsComment", ignoreCase = true) -> "详情"
            shortClass.contains("BizTimeLineUI", ignoreCase = true) ||
                shortClass.contains("BizChattingUI", ignoreCase = true) -> "公众号"
            shortClass.contains("BizFlutterTLFlutterView", ignoreCase = true) -> "公众号"
            shortClass.contains("TmplWebViewMMUI", ignoreCase = true) ||
                shortClass.contains("WebView", ignoreCase = true) -> "文章"
            shortClass.contains("AppBrandUI", ignoreCase = true) ||
                shortClass.contains("MiniProgram", ignoreCase = true) -> "小程序"
            shortClass.contains("Wallet", ignoreCase = true) ||
                shortClass.contains("PayUI", ignoreCase = true) -> "支付"
            shortClass.contains("Finder", ignoreCase = true) ||
                shortClass.contains("VideoCollection", ignoreCase = true) -> "视频号"
            shortClass.contains("AddressUI", ignoreCase = true) ||
                shortClass.contains("Contact", ignoreCase = true) -> "通讯录"
            shortClass.contains("Video", ignoreCase = true) ||
                shortClass.contains("Player", ignoreCase = true) -> "视频"
            shortClass.contains("Search", ignoreCase = true) -> "搜索"
            shortClass.contains("Setting", ignoreCase = true) ||
                shortClass.contains("Settings", ignoreCase = true) -> "设置"
            shortClass.contains("Album", ignoreCase = true) ||
                shortClass.contains("Gallery", ignoreCase = true) -> "相册"
            shortClass.contains("Browser", ignoreCase = true) -> "网页"
            shortClass.contains("Reader", ignoreCase = true) ||
                shortClass.contains("ReadUI", ignoreCase = true) ||
                shortClass.contains("Article", ignoreCase = true) -> "文章"
            shortClass.contains("Order", ignoreCase = true) ||
                shortClass.contains("Confirm", ignoreCase = true) -> "订单"
            shortClass.contains("Cart", ignoreCase = true) -> "购物车"
            shortClass.contains("Feed", ignoreCase = true) -> "动态"
            shortClass.contains("Live", ignoreCase = true) -> "直播"
            shortClass.contains("Progress", ignoreCase = true) ||
                shortClass.contains("Loading", ignoreCase = true) -> "加载"
            shortClass.contains("Toast", ignoreCase = true) ||
                shortClass.contains("Snackbar", ignoreCase = true) -> "提示"
            shortClass.contains("Permission", ignoreCase = true) ||
                shortClass.contains("Rationale", ignoreCase = true) -> "权限"
            shortClass.contains("Share", ignoreCase = true) ||
                shortClass.contains("Send", ignoreCase = true) ||
                shortClass.contains("Forward", ignoreCase = true) -> "分享"
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
                shortClass.contains("Selector", ignoreCase = true) -> "选择"
            shortClass.contains("Detail", ignoreCase = true) -> "详情"
            shortClass.contains("Home", ignoreCase = true) ||
                shortClass.contains("Main", ignoreCase = true) -> "首页"
            else -> null
        }
    }
}
