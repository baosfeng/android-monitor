package com.bsfeng.monitor.skip

import android.os.Handler
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.util.DebugConfig
import com.bsfeng.monitor.MonitorApp

/**
 * 规则引擎核心。
 *
 * 在无障碍服务收到 WINDOW_STATE_CHANGED 事件时：
 * 1. 匹配当前前台 App 的规则
 * 2. 在无障碍树中定位目标节点（使用选择器）
 * 3. 执行点击操作跳过广告
 *
 * 如果首次匹配未命中（广告尚未加载），自动启动持续监控模式，
 * 每 500ms 扫描一次屏幕，一旦检测到跳过按钮立即点击。
 * 监控最长持续 5 秒后自动停止。
 *
 * 内置防误触机制：
 * - matchDelay：页面打开后延迟匹配
 * - actionCd：执行后冷却期
 * - actionMaximum：规则生命周期内最大执行次数
 */
class SkipRuleEngine(
    private val ruleManager: SkipRuleManager
) {
    private var currentPackageName: String? = null
    private val actionCounters = mutableMapOf<String, Int>()
    private val actionCooldowns = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    /** 通用兜底：每个 App 的最近执行时间 */
    /** 跳过成功后冷却：包名 → 上次成功跳过时间 */
    private val skipCooldowns = mutableMapOf<String, Long>()

    /** 坐标点击已尝试记录（每个包名每个 session 只尝试一次） */
    private val coordinateClickAttempted = mutableSetOf<String>()

    /** 根节点提供器（监控时获取新鲜无障碍树） */
    private var rootNodeProvider: (() -> AccessibilityNodeInfo?)? = null
    /** 跳过成功回调（供服务层显示通知） */
    var onSkipSuccess: ((packageName: String) -> Unit)? = null

    // ========== 持续监控（白屏→广告延迟加载场景） ==========

    /** 监控 Handler（独立实例，避免与 matchDelay 冲突） */
    private val watchHandler = Handler(Looper.getMainLooper())
    /** 当前正在监控的包名 */
    private var watchPackage: String? = null
    /** 当前正在监控的 Activity 类名 */
    private var watchClassName: String? = null
    /** 监控开始时间 */
    private var watchStartTime = 0L
    /** 监控扫描任务 */
    private val watchScanTask = Runnable { performWatchScan() }

    // ========== 点击后验证（扫描确认，不依赖 GestureResultCallback） ==========

    /** 等待验证的包名（非空 = 刚刚执行了点击，正在验证是否生效） */
    private var pendingConfirmPkg: String? = null
    /** 验证中连续未匹配次数（达到阈值视为跳过成功） */
    private var verifyMissCount: Int = 0
    /** 验证中重试次数（达到上限则放弃） */
    private var verifyRetryCount: Int = 0
    /** 点击后记录当前选择器，用于验证时对比 */
    private var lastClickSelector: String? = null
    /** 点击后记录当前 matchRule key，用于验证失败后加冷却 */
    private var lastMatchRuleKey: Int = 0

    companion object {
        private const val TAG = "SkipRuleEngine"
        /** 通用兜底冷却期（毫秒，防止重复尝试失败） */
        /** 手势确认超时（毫秒，dispatchGesture 回调超时后视为失败） */
        private const val PENDING_CONFIRM_TIMEOUT_MS = 2000L
        /** 验证阶段：连续几次未匹配到跳过按钮即视为成功 */
        private const val VERIFY_SUCCESS_MISSES = 3
        /** 验证阶段：最大重试点击次数 */
        private const val VERIFY_MAX_RETRIES = 3
        /** 监控扫描间隔（毫秒） */
        private const val WATCH_INTERVAL_MS = 100L
        /** 监控超时（毫秒，超过此时间仍未检测到广告则停止） */
        private const val WATCH_TIMEOUT_MS = 5_000L
        /** 同一 App 跳过成功后冷却期（避免后续窗口事件重复触发） */
        private const val SKIP_COOLDOWN_MS = 30_000L
        /** 通用兜底选择器（按优先级排序）。
         *
         * 仅保留 ID 类匹配，避免误触正常页面中的"跳过""关闭"等文本。
         * 纯文本匹配（[text*=]、[desc*=]）已移除——它们在正常页面中误触率极高。
         * 只有广告 SDK 留下的 View ID 模式（tt_splash、skip）才是可靠的通用信号。
         */
        val GENERIC_SELECTORS = listOf(
            "[id$=\"tt_splash_skip_btn\"]",
            "[vid*=\"skip\"]",
            "[id*=\"skip_btn\"]",
            "[id$=\"tt_splash_ad_close\"]"
        )

        /** 文本兜底选择器（仅在确认是开屏页时使用）。
         *
         * 匹配常见的"跳过""关闭"等文本和内容描述。
         * 这些选择器在正常页面中误触率很高，因此只能通过 [isSplashPage] 守卫后使用。
         */
        val TEXT_SELECTORS = listOf(
            "[clickable=true][text*=\"跳过\"]",
            "[text*=\"跳过\"][visibleToUser=true]",
            "[text*=\"跳过广告\"]",
            "[text*=\"关闭广告\"]",
            "[text*=\"关闭\"][visibleToUser=true]",
            "[clickable=true][desc*=\"跳过\"]",
            "[desc*=\"跳过\"][visibleToUser=true]",
            "[text*=\"skip\"][visibleToUser=true]",
            "[clickable=true][text*=\"close\"]",
            "[clickable=true][desc*=\"close\"]"
        )

        /** 策略链执行的最大延迟（总超时）。单次执行超过此时间则中断。 */
        private const val STRATEGY_CHAIN_TIMEOUT_MS = 2000L

        /** 开屏/广告页面关键词列表（className 包含任一关键词即视为开屏页） */
        private val SPLASH_KEYWORDS = listOf(
            "splash",
            "AdActivity",
            "开屏", "广告"
        )
    }

    /** 拦截器链（初始窗口变化时使用，包含所有策略） */
    private val fullInterceptors: List<AdInterceptor> = listOf(
        ExactRuleInterceptor(),
        GenericIdInterceptor(),
        TextMatchInterceptor(),
        CoordinateClickInterceptor(),
        GlobalBackInterceptor()
    )

    /** 拦截器链（监控扫描时使用，仅安全的匹配策略，不含坐标点击和全局返回） */
    private val monitoringInterceptors: List<AdInterceptor> = listOf(
        ExactRuleInterceptor(),
        GenericIdInterceptor(),
        TextMatchInterceptor()
    )

    /**
     * 处理窗口变化事件。
     *
     * 使用拦截器链逐个尝试关闭广告。
     *
     * @return true 表示已执行跳过操作
     */
    fun onWindowChanged(
        packageName: String,
        className: String,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        DebugConfig.log("onWindowChanged: $packageName / $className")

        // 白名单检查
        if (isPackageWhitelisted(packageName)) return false

        // 冷却检查
        val cooldownEnd = skipCooldowns[packageName] ?: 0L
        if (System.currentTimeMillis() < cooldownEnd) {
            DebugConfig.log("SkipEngine: $packageName 跳过冷却中")
            return false
        }

        // 包名变化时重置
        if (packageName != currentPackageName) {
            resetAppState(packageName)
            currentPackageName = packageName
        }

        if (rootNode == null) return false

        // ====== 执行拦截器链 ======
        val isSplash = isSplashPage(className)
        val skipRule = ruleManager.findEnabledRule(packageName)
        val hasMatchingRuleGroup = skipRule?.groups?.any { group ->
            group.activityIds.isEmpty() || group.activityIds.any { className.contains(it) }
        } == true
        // canRunFullChain: 仅当确定是开屏页时才允许文本匹配/坐标点击等高危策略
        // activityIds 为空的规则组（50+ 个内置 App 中绝大多数）不能触发高危策略
        val hasExplicitMatch = skipRule?.groups?.any { group ->
            group.activityIds.isNotEmpty() && group.activityIds.any { className.contains(it) }
        } == true
        val canRunFullChain = isSplash || hasExplicitMatch

        val ctx = AdContext(
            packageName = packageName,
            className = className,
            rootNode = rootNode,
            isSplash = isSplash,
            hasMatchingRuleGroup = hasMatchingRuleGroup,
            canRunFullChain = canRunFullChain,
            ruleManager = ruleManager,
            engine = this,
            actionCounters = actionCounters,
            actionCooldowns = actionCooldowns
        )

        DebugConfig.log("拦截器链: $packageName splash=$isSplash canRunFullChain=$canRunFullChain")

        for (interceptor in fullInterceptors) {
            DebugConfig.log("  执行: ${interceptor::class.simpleName}")
            if (interceptor.intercept(ctx)) {
                skipCooldowns[packageName] = System.currentTimeMillis() + SKIP_COOLDOWN_MS
                Log.d(TAG, "✅ 拦截器 ${interceptor::class.simpleName} 跳过成功: $packageName")
                return true
            }
        }

        DebugConfig.log("拦截器链: 所有拦截器均未命中")
        NodeMatcher.diagnosePageStructure(rootNode)
        return false
    }

    /**
     * 执行单条规则：尝试匹配选择器中的任意一个表达式，匹配成功则点击。
     */
    fun executeRule(
        ruleId: String,
        matchRule: MatchRule,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (rootNode == null) return false

        // matchDelay：延迟 matchDelay 毫秒后使用新鲜根节点执行匹配
        if (matchRule.matchDelay > 0) {
            handler.postDelayed({
                val freshRoot = rootNodeProvider?.invoke() ?: return@postDelayed
                try {
                    if (performMatchAndClick(ruleId, matchRule, freshRoot)) {
                        updateCooldown(matchRule)
                        incrementCounter(matchRule, ruleId)
                        Log.d(TAG, "✅ 延迟匹配跳过广告成功: $ruleId <- ${matchRule.matches.firstOrNull()}")
                    }
                } finally {
                    freshRoot.recycle()
                }
            }, matchRule.matchDelay)
            return false
        }

        return performMatchAndClick(ruleId, matchRule, rootNode)
    }

    /**
     * 执行匹配和点击。
     */
    private fun performMatchAndClick(
        ruleId: String,
        matchRule: MatchRule,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        // 依次尝试每个选择器表达式
        for (selectorExpr in matchRule.matches) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                try {
                    DebugConfig.log("规则 $ruleId 尝试选择器: $selectorExpr → 找到 ${nodes.size} 个节点")

                    // 过滤不可见的节点
                    val visibleNodes = nodes.filter { it.isVisibleToUser }

                    if (visibleNodes.isNotEmpty()) {
                        // 取第一个可见节点执行点击
                        val target = visibleNodes.first()
                        val targetText = target.text
                        val targetId = target.viewIdResourceName
                        val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
                        DebugConfig.log("点击目标: text=$targetText id=$targetId bounds=$bounds")

                        if (ActionExecutor.perform(target, matchRule.action)) {
                            Log.d(TAG, "✅ 点击成功: $selectorExpr")
                            return true
                        } else {
                            DebugConfig.log("点击失败: $selectorExpr — perform 返回 false")
                        }
                    } else if (nodes.isNotEmpty()) {
                        val first = nodes.first()
                        DebugConfig.log("找到 ${nodes.size} 个节点但全部不可见，示例: text=${first.text} id=${first.viewIdResourceName}")
                    } else {
                        DebugConfig.log("选择器无匹配: $selectorExpr")
                    }
                } finally {
                    NodeMatcher.recycleAll(nodes)
                }
            } catch (e: Exception) {
                DebugConfig.log("选择器解析/匹配异常: $selectorExpr → ${e.message}")
                Log.w(TAG, "选择器匹配失败: $selectorExpr", e)
            }
        }
        DebugConfig.log("规则 $ruleId 所有选择器均未命中")
        return false
    }

    /**
     * 检查规则是否在冷却中。
     */
    fun isOnCooldown(matchRule: MatchRule): Boolean {
        val cooldownEnd = actionCooldowns[matchRule.toCooldownKey()] ?: return false
        return System.currentTimeMillis() < cooldownEnd
    }

    /**
     * 更新冷却时间。
     */
    fun updateCooldown(matchRule: MatchRule) {
        if (matchRule.actionCd > 0) {
            actionCooldowns[matchRule.toCooldownKey()] =
                System.currentTimeMillis() + matchRule.actionCd
        }
    }

    /**
     * 增加执行计数器。
     */
    fun incrementCounter(matchRule: MatchRule, ruleId: String) {
        val key = matchRule.toCounterKey(ruleId)
        actionCounters[key] = (actionCounters[key] ?: 0) + 1
    }

    // ========== 策略链辅助方法 ==========

    /**
     * 尝试用一组选择器匹配并点击节点。
     * 对每个选择器：先找可见节点，失败后再试不可见节点。
     *
     * @return 是否成功点击
     */
    fun matchSelectors(
        packageName: String,
        rootNode: AccessibilityNodeInfo,
        selectors: List<String>
    ): Boolean {
        for (selectorExpr in selectors) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                try {
                    if (nodes.isEmpty()) continue

                    // 第一轮：可见节点
                    val visibleNodes = nodes.filter { it.isVisibleToUser }
                    if (visibleNodes.isNotEmpty()) {
                        val target = visibleNodes.first()
                        if (ActionExecutor.perform(target, "click")) {
                            DebugConfig.log("  ✅ 匹配成功: $selectorExpr → ${target.text}")
                            return true
                        }
                    }

                    // 第二轮：不可见节点
                    val invisibleNodes = nodes.filter { !it.isVisibleToUser }
                    if (invisibleNodes.isNotEmpty()) {
                        val target = invisibleNodes.first()
                        if (ActionExecutor.perform(target, "click")) {
                            DebugConfig.log("  ✅ 匹配成功(不可见): $selectorExpr → ${target.text}")
                            return true
                        }
                    }
                } finally {
                    NodeMatcher.recycleAll(nodes)
                }
            } catch (e: Exception) {
                DebugConfig.log("  $selectorExpr → 异常: ${e.message}")
            }
        }
        return false
    }

    /**
     * 右上角坐标点击（处理 WebView / Flutter 等无障碍树不暴露内部元素的场景）。
     *
     * 在屏幕右侧 10% 范围内从上到下依次点击，模拟用户点击跳过按钮。
     * 仅当确认是开屏页时使用，且是最后几种策略之一。
     */
    fun tryCoordinateClick(rootNode: AccessibilityNodeInfo): Boolean {
        val pkg = watchPackage ?: currentPackageName ?: return false
        if (pkg in coordinateClickAttempted) return false  // 每个 session 只尝试一次
        coordinateClickAttempted.add(pkg)

        val service = ActionExecutor.accessibilityService ?: return false
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 跳过按钮常见位置（右上角 + 右下角 + 中间靠右）
        val xTop = (screenWidth * 0.92).toInt()       // 右上角
        val xBottom = (screenWidth * 0.50).toInt()     // 底部中间（跳过按钮有时居中）
        val xCenterRight = (screenWidth * 0.70).toInt() // 中间靠右

        // 分三组位置：右上角纵向、底部横向、中间靠右纵向
        val topRightPoints = listOf(
            screenHeight * 0.04,
            screenHeight * 0.07,
            screenHeight * 0.10,
            screenHeight * 0.13,
            screenHeight * 0.16,
            screenHeight * 0.20
        )
        val bottomPoints = listOf(
            screenHeight * 0.75,
            screenHeight * 0.80,
            screenHeight * 0.85,
            screenHeight * 0.90,
            screenHeight * 0.95
        )
        val centerRightPoints = listOf(
            screenHeight * 0.25,
            screenHeight * 0.30,
            screenHeight * 0.35,
            screenHeight * 0.40,
            screenHeight * 0.45,
            screenHeight * 0.50
        )

        DebugConfig.log("坐标点击: 屏幕 ${screenWidth}x$screenHeight, 尝试多位置")

        // 组1: 右上角（最常见）
        for (y in topRightPoints) {
            dispatchClick(service, xTop, y.toInt())
        }
        // 组2: 右下角到底部中间
        for (y in bottomPoints) {
            dispatchClick(service, xBottom, y.toInt())
        }
        // 组3: 中间靠右
        for (y in centerRightPoints) {
            dispatchClick(service, xCenterRight, y.toInt())
        }

        Log.d(TAG, "坐标点击已执行: 16个位置")
        return true  // 已发送手势，返回 true 防止全局返回误触
    }

    /** 发送一次坐标点击手势 */
    private fun dispatchClick(service: android.accessibilityservice.AccessibilityService, x: Int, y: Int) {
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80L)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    /**
     * 重置某个 App 的状态（包名变化时或 Activity 重置时）。
     */
    fun resetAppState(packageName: String) {
        actionCounters.clear()
        actionCooldowns.clear()
        stopWatching()
        skipCooldowns.remove(packageName)
        DebugConfig.log("SkipEngine: 状态已重置 for $packageName")
    }

    // ========== 持续监控 ==========

    /**
     * 设置根节点提供器。
     */
    fun setRootNodeProvider(provider: () -> AccessibilityNodeInfo?) {
        rootNodeProvider = provider
    }

    /**
     * 启动屏幕内容持续监控。
     *
     * 当 [onWindowChanged] 首次未命中时调用（广告尚未渲染出来）。
     * 每 500ms 扫描一次屏幕无障碍树。
     * 最多监控 5 秒后自动停止。
     *
     * 无内置规则的 App，仅在当前页面疑似开屏页时才启动监控。
     *
     * @param packageName 当前前台 App 包名
     */
    /**
     * 启动屏幕内容持续监控。
     *
     * 当 [onWindowChanged] 首次未命中时调用（广告尚未渲染出来）。
     * 每 500ms 扫描一次屏幕无障碍树。
     * 最多监控 5 秒后自动停止。
     *
     * 无内置规则的 App，仅在当前页面疑似开屏页时才启动监控。
     * 有内置规则的 App 不受此限制（由精确选择器自身保证准确性）。
     *
     * @param packageName 当前前台 App 包名
     */
    fun startWatching(packageName: String, className: String = "") {
        // 白名单检查：在白名单中的 App 不做任何处理
        if (isPackageWhitelisted(packageName)) {
            return
        }

        // 跳过冷却期内不重复扫描
        val cooldownEnd = skipCooldowns[packageName] ?: 0L
        if (System.currentTimeMillis() < cooldownEnd) {
            Log.d(TAG, "跳过冷却中，不启动监控: $packageName")
            return
        }

        // 无内置规则的 App，仅当页面疑似开屏页时才启动监控
        // className="" 时 isSplashPage 返回 false，确保默认不启动
        val hasBuiltInRule = ruleManager.findEnabledRule(packageName) != null
        if (!hasBuiltInRule && !isSplashPage(className)) {
            Log.d(TAG, "无内置规则且非开屏页，不启动监控: $packageName ($className)")
            return
        }

        // 相同包名不重复启动，但更新 className（App 内页面变化，如微信→小程序）
        if (watchPackage == packageName) {
            if (className.isNotEmpty() && className != watchClassName) {
                watchClassName = className
                // 进入小程序时暂停扫描，给广告加载时间
                if (MiniProgramSkipHandler.isMiniProgram(className)) {
                    watchHandler.removeCallbacks(watchScanTask)
                    watchHandler.postDelayed(watchScanTask, 2000L)
                    Log.d(TAG, "监控进入小程序，延迟2秒后扫描: $packageName <- $className")
                } else {
                    Log.d(TAG, "监控更新 className: $packageName <- $className")
                }
            }
            Log.d(TAG, "监控已在运行: $packageName")
            return
        }
        stopWatching()

        watchPackage = packageName
        watchClassName = className
        watchStartTime = System.currentTimeMillis()
        Log.d(TAG, "🔄 启动持续监控: $packageName (最长 ${WATCH_TIMEOUT_MS / 1000}秒)")

        // 首次扫描立即执行
        watchHandler.post(watchScanTask)
    }

    /**
     * 停止持续监控。
     */
    fun stopWatching() {
        if (watchPackage != null) {
            Log.d(TAG, "⏹ 停止监控: $watchPackage")
        }
        watchHandler.removeCallbacks(watchScanTask)
        watchPackage = null
        watchClassName = null
        watchStartTime = 0L
        pendingConfirmPkg = null
        verifyMissCount = 0
        verifyRetryCount = 0
        lastClickSelector = null
        lastMatchRuleKey = 0
    }

    /**
     * 执行一次监控扫描：获取新鲜根节点，尝试匹配规则。
     */
    private fun performWatchScan() {
        val pkg = watchPackage ?: return

        // 超时检查
        if (System.currentTimeMillis() - watchStartTime >= WATCH_TIMEOUT_MS) {
            Log.d(TAG, "⏰ 监控超时（${WATCH_TIMEOUT_MS / 1000}秒），停止监控: $pkg")
            DebugConfig.log("监控超时停止: $pkg")
            stopWatching()
            return
        }

        // ======== 阶段1：点击后验证 ========
        if (pendingConfirmPkg != null) {
            DebugConfig.log("监控验证阶段: pending=$pendingConfirmPkg retry=$verifyRetryCount/$VERIFY_MAX_RETRIES miss=$verifyMissCount/$VERIFY_SUCCESS_MISSES")
            val freshRoot = rootNodeProvider?.invoke()
            if (freshRoot != null) {
                try {
                    val stillHasAd = checkAdStillPresent(freshRoot)
                    if (stillHasAd) {
                        // 广告还在 → 重试或放弃
                        verifyMissCount = 0
                        verifyRetryCount++
                        if (verifyRetryCount >= VERIFY_MAX_RETRIES) {
                            Log.w(TAG, "验证重试已达上限($VERIFY_MAX_RETRIES)，放弃验证: $pkg")
                            pendingConfirmPkg = null
                            verifyMissCount = 0
                            verifyRetryCount = 0
                            lastClickSelector = null
                            // 进入短暂冷却，避免立即重试同一个无效选择器
                            actionCooldowns["cd_$lastMatchRuleKey"] = System.currentTimeMillis() + 3000
                            // 继续下面的正常扫描
                        } else {
                            Log.d(TAG, "广告仍在，重试点击(${verifyRetryCount}/$VERIFY_MAX_RETRIES): $pkg")
                            // 不进入扫描阶段，直接继续轮询（下次扫描会重试匹配）
                            watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                            return
                        }
                    } else {
                        // 广告消失 → 确认跳过成功
                        verifyMissCount++
                        if (verifyMissCount >= VERIFY_SUCCESS_MISSES) {
                            Log.d(TAG, "✅ 连续${VERIFY_SUCCESS_MISSES}次未检测到广告，确认跳过成功: $pkg")
                            pendingConfirmPkg = null
                            verifyMissCount = 0
                            verifyRetryCount = 0
                            lastClickSelector = null
                            stopWatching()
                            onSkipSuccess?.invoke(pkg)
                            return
                        } else {
                            Log.d(TAG, "广告未检测到(${verifyMissCount}/$VERIFY_SUCCESS_MISSES)，继续确认: $pkg")
                            watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                            return
                        }
                    }
                } finally {
                    freshRoot.recycle()
                }
            } else {
                // 根节点不可用，继续等待
                Log.v(TAG, "验证阶段: freshRoot 为 null，继续等待: $pkg")
                watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                return
            }
        }

        // ======== 阶段2：正常扫描匹配（静默模式，仅在有发现时记录日志）=======
        val freshRoot = rootNodeProvider?.invoke()
        if (freshRoot == null) {
            Log.v(TAG, "监控扫描: freshRoot 为 null，等待下次扫描: $pkg")
            watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
            return
        }

        try {
            if (runMonitoringStrategyChain(pkg, freshRoot)) {
                // 进入验证阶段：后续扫描确认广告是否消失
                pendingConfirmPkg = pkg
                verifyMissCount = 0
                verifyRetryCount = 0
                watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                return
            }
        } finally {
            freshRoot.recycle()
        }

        // 未匹配，继续下次扫描（静默，不输出日志）
        watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
    }

    /**
     * 监控扫描中的拦截器链（与 [onWindowChanged] 共享相同逻辑）。
     */
    private fun runMonitoringStrategyChain(
        packageName: String,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val cls = watchClassName ?: ""
        val isSplash = isSplashPage(cls)
        val skipRule = ruleManager.findEnabledRule(packageName)
        val hasMatchingRuleGroup = skipRule?.groups?.any { group ->
            group.activityIds.isEmpty() || group.activityIds.any { cls.contains(it) }
        } == true
        // canRunFullChain: 仅当明确是开屏页时才允许文本匹配等高危策略
        val hasExplicitMatch = skipRule?.groups?.any { group ->
            group.activityIds.isNotEmpty() && group.activityIds.any { cls.contains(it) }
        } == true
        val canRunFullChain = isSplash || hasExplicitMatch

        val ctx = AdContext(
            packageName = packageName,
            className = cls,
            rootNode = rootNode,
            isSplash = isSplash,
            hasMatchingRuleGroup = hasMatchingRuleGroup,
            canRunFullChain = canRunFullChain,
            ruleManager = ruleManager,
            engine = this,
            actionCounters = actionCounters,
            actionCooldowns = actionCooldowns
        )

        for (interceptor in monitoringInterceptors) {
            if (interceptor.intercept(ctx)) {
                Log.d(TAG, "✅ 监控-${interceptor::class.simpleName} 跳过成功: $packageName")
                return true
            }
        }

        NodeMatcher.diagnosePageStructure(rootNode)
        return false
    }

    /**
     * 直接执行规则（不经过 matchDelay 的同步等待，监控场景已有时机）。
     */
    private fun executeRuleDirect(
        ruleId: String,
        matchRule: MatchRule,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        for (selectorExpr in matchRule.matches) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                try {
                    val visibleNodes = nodes.filter { it.isVisibleToUser }
                    if (visibleNodes.isNotEmpty()) {
                        val target = visibleNodes.first()

                        // 过滤空边界节点
                        val bounds = android.graphics.Rect()
                        target.getBoundsInScreen(bounds)
                        if (bounds.isEmpty) {
                            Log.d(TAG, "跳过空边界节点: ${target.text} (${target.viewIdResourceName})")
                            continue
                        }

                        Log.d(TAG, "监控找到匹配节点: ${target.text} (${target.viewIdResourceName}) " +
                                "bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")

                        // 小程序广告用坐标点击命中覆盖层，普通 App 用规则默认动作
                        val clickAction = if (MiniProgramSkipHandler.isMiniProgram(watchClassName))
                            MiniProgramSkipHandler.getClickAction() else matchRule.action
                        ActionExecutor.perform(target, clickAction)
                        Log.d(TAG, "⏳ 规则点击已执行，进入验证: $ruleId <- $selectorExpr")
                        val pkg = watchPackage ?: return false
                        pendingConfirmPkg = pkg
                        verifyMissCount = 0
                        verifyRetryCount = 0
                        lastClickSelector = selectorExpr
                        lastMatchRuleKey = matchRule.key
                        return true
                    }
                } finally {
                    NodeMatcher.recycleAll(nodes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "监控选择器匹配失败: $selectorExpr", e)
            }
        }
        return false
    }

    /**
     * 判断当前上下文是否无法可靠跳过广告。
     */
    /**
     * 判断当前 className 是否是一个开屏/广告页面。
     *
     * 当 className（通常形如 "com.example.MainActivity"）包含以下关键词时视为开屏页：
     * - Splash / splash（标准开屏页命名）
     * - AdActivity / Advertisement（广告 Activity）
     * - "开屏" / "广告"（中文关键词）
     */
    private fun isSplashPage(className: String): Boolean {
        if (className.isBlank()) return false
        return SPLASH_KEYWORDS.any { keyword ->
            className.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * 检查指定包名是否在用户白名单中。
     * 白名单中的 App 完全跳过所有开屏广告检测。
     */
    private fun isPackageWhitelisted(packageName: String): Boolean {
        return try {
            val prefs = MonitorApp.instance.getSharedPreferences("skip_whitelist", android.content.Context.MODE_PRIVATE)
            val whitelist = prefs.getStringSet("whitelist", emptySet()) ?: emptySet()
            val result = packageName in whitelist
            if (result) Log.d(TAG, "白名单命中，跳过: $packageName")
            result
        } catch (_: Exception) {
            false
        }
    }

    private fun isUnskippableContext(packageName: String): Boolean {
        return false
    }

    /**
     * 检查当前屏幕是否仍存在广告跳过按钮。
     * 使用通用选择器匹配，找到一个即视为广告仍在。
     */
    private fun checkAdStillPresent(rootNode: AccessibilityNodeInfo): Boolean {
        for (selectorExpr in GENERIC_SELECTORS) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                try {
                    if (nodes.isEmpty()) continue
                    // 只要有一个匹配节点（无论是否可见）就认为广告还在
                    return true
                } finally {
                    NodeMatcher.recycleAll(nodes)
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /** 记录 App 跳过冷却时间，防止短时间内重复触发 */
    fun recordSkipCooldown(packageName: String) {
        skipCooldowns[packageName] = System.currentTimeMillis() + SKIP_COOLDOWN_MS
    }

    /**
     * 执行精确规则匹配：遍历规则组和规则，检查冷却和最大执行次数，执行匹配和点击。
     *
     * @param ruleId 规则 ID
     * @param skipRule 要执行的规则
     * @param rootNode 无障碍树根节点
     * @return 是否成功跳过
     */
    fun executeExactRules(ruleId: String, skipRule: com.bsfeng.monitor.domain.model.SkipRule, rootNode: AccessibilityNodeInfo): Boolean {
        for (group in skipRule.groups) {
            for (matchRule in group.rules) {
                if (isOnCooldown(matchRule)) continue
                if (matchRule.actionMaximum > 0 &&
                    (actionCounters[matchRule.toCounterKey(ruleId)] ?: 0) >= matchRule.actionMaximum) continue

                if (executeRule(ruleId, matchRule, rootNode)) {
                    updateCooldown(matchRule)
                    incrementCounter(matchRule, ruleId)
                    return true
                }
            }
        }
        return false
    }

    private fun MatchRule.toCooldownKey(): String = "cd_${key}"
    private fun MatchRule.toCounterKey(ruleId: String): String = "${ruleId}_${key}"
}
