package com.bsfeng.monitor.skip

import android.os.Handler
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.util.DebugConfig

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
 * 监控最长持续 15 秒后自动停止。
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
    private val genericCooldowns = mutableMapOf<String, Long>()
    /** 通用兜底：每个 App 的执行次数 */
    private val genericCounters = mutableMapOf<String, Int>()

    /** 跳过成功后冷却：包名 → 上次成功跳过时间 */
    private val skipCooldowns = mutableMapOf<String, Long>()

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
        private const val GENERIC_CD_MS = 60_000L
        /** 通用兜底最大执行次数（每个 App 生命周期内） */
        private const val GENERIC_MAX_EXECUTIONS = 3
        /** 通用兜底延迟（毫秒，等待 UI 渲染完成） */
        private const val GENERIC_DELAY_MS = 100L
        /** 手势确认超时（毫秒，dispatchGesture 回调超时后视为失败） */
        private const val PENDING_CONFIRM_TIMEOUT_MS = 2000L
        /** 验证阶段：连续几次未匹配到跳过按钮即视为成功 */
        private const val VERIFY_SUCCESS_MISSES = 3
        /** 验证阶段：最大重试点击次数 */
        private const val VERIFY_MAX_RETRIES = 3
        /** 监控扫描间隔（毫秒） */
        private const val WATCH_INTERVAL_MS = 100L
        /** 监控超时（毫秒，超过此时间仍未检测到广告则停止） */
        private const val WATCH_TIMEOUT_MS = 15_000L
        /** 同一 App 跳过成功后冷却期（避免后续窗口事件重复触发） */
        private const val SKIP_COOLDOWN_MS = 30_000L
        /** 通用兜底选择器（按优先级排序，从精确到宽泛） */
        private val GENERIC_SELECTORS = listOf(
            "[id$=\"tt_splash_skip_btn\"]",
            "[vid*=\"skip\"]",
            "[clickable=true][text*=\"跳过\"]",
            "[text*=\"跳过\"]",
            "[desc*=\"跳过\"]",
            "[clickable=true][text*=\"skip\"]",
            "[text*=\"skip\"]",
            "[text*=\"关闭广告\"]",
            "[id*=\"skip\"]"
        )
    }

    /**
     * 处理窗口变化事件。
     *
     * @return true 表示已执行跳过操作
     */
    fun onWindowChanged(
        packageName: String,
        className: String,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        // 跳过成功冷却期内，不再处理
        val cooldownEnd = skipCooldowns[packageName] ?: 0L
        if (System.currentTimeMillis() < cooldownEnd) {
            DebugConfig.log("SkipEngine: $packageName 跳过冷却中")
            return false
        }

        // 包名变化时重置状态
        if (packageName != currentPackageName) {
            resetAppState(packageName)
            currentPackageName = packageName
        }

        // ---- 有特定规则时走精确匹配 ----
        val skipRule = ruleManager.findEnabledRule(packageName)
        if (skipRule != null) {
            // 查找匹配的规则组
            val matchedGroups = skipRule.groups.filter { group ->
                group.activityIds.isEmpty() || group.activityIds.any { className.contains(it) }
            }

            if (matchedGroups.isEmpty()) {
                DebugConfig.log("SkipEngine: $packageName 无匹配规则组")
            } else {
                for (group in matchedGroups) {
                    for (matchRule in group.rules) {
                        // 检查冷却
                        if (isOnCooldown(matchRule)) {
                            DebugConfig.log("SkipEngine: 规则 ${matchRule.key} 冷却中")
                            continue
                        }

                        // 检查最大执行次数
                        if (matchRule.actionMaximum > 0 &&
                            (actionCounters[matchRule.toCounterKey(skipRule.id)] ?: 0) >= matchRule.actionMaximum) {
                            DebugConfig.log("SkipEngine: 规则 ${matchRule.key} 已达最大执行次数")
                            continue
                        }

                        // 执行规则
                        if (executeRule(skipRule.id, matchRule, rootNode)) {
                            updateCooldown(matchRule)
                            incrementCounter(matchRule, skipRule.id)
                            Log.d(TAG, "✅ 跳过广告成功: ${skipRule.name} <- ${matchRule.matches.firstOrNull()}")
                            skipCooldowns[packageName] = System.currentTimeMillis() + SKIP_COOLDOWN_MS
                            return true
                        }
                    }
                }
            }
        }

        // ---- 无特定规则或特定规则未命中，尝试全局兜底 ----
        return tryGenericFallback(packageName, rootNode)
    }

    /**
     * 执行单条规则：尝试匹配选择器中的任意一个表达式，匹配成功则点击。
     */
    private fun executeRule(
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

                // 过滤不可见的节点
                val visibleNodes = nodes.filter { it.isVisibleToUser }

                if (visibleNodes.isNotEmpty()) {
                    // 取第一个可见节点执行点击
                    val target = visibleNodes.first()
                    Log.d(TAG, "找到匹配节点: ${target.text} (${target.viewIdResourceName})")

                    if (ActionExecutor.perform(target, matchRule.action)) {
                        Log.d(TAG, "✅ 点击成功: $selectorExpr")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "选择器匹配失败: $selectorExpr", e)
            }
        }
        return false
    }

    /**
     * 检查规则是否在冷却中。
     */
    private fun isOnCooldown(matchRule: MatchRule): Boolean {
        val cooldownEnd = actionCooldowns[matchRule.toCooldownKey()] ?: return false
        return System.currentTimeMillis() < cooldownEnd
    }

    /**
     * 更新冷却时间。
     */
    private fun updateCooldown(matchRule: MatchRule) {
        if (matchRule.actionCd > 0) {
            actionCooldowns[matchRule.toCooldownKey()] =
                System.currentTimeMillis() + matchRule.actionCd
        }
    }

    /**
     * 增加执行计数器。
     */
    private fun incrementCounter(matchRule: MatchRule, ruleId: String) {
        val key = matchRule.toCounterKey(ruleId)
        actionCounters[key] = (actionCounters[key] ?: 0) + 1
    }

    // ========== 全局兜底 ==========

    /**
     * 尝试使用通用选择器匹配跳过按钮。
     *
     * 适用于没有内置规则的 App，使用常见文案/ID 进行试探性匹配。
     * 内置防误触：每个 App 生命周期内最多执行 1 次，冷却期 60 秒。
     */
    private fun tryGenericFallback(
        packageName: String,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (rootNode == null) {
            Log.d(TAG, "通用兜底: rootNode 为空 for $packageName")
            return false
        }

        // 跳过本 App 自身
        if (packageName == "com.bsfeng.monitor") {
            return false
        }

        Log.d(TAG, "通用兜底: 尝试匹配 $packageName")

        // 冷却检查
        val now = System.currentTimeMillis()
        val lastTime = genericCooldowns[packageName] ?: 0L
        if (now - lastTime < GENERIC_CD_MS) {
            Log.d(TAG, "通用兜底: $packageName 冷却中 (剩余 ${GENERIC_CD_MS - (now - lastTime)}ms)")
            return false
        }

        // 执行次数检查
        val count = genericCounters[packageName] ?: 0
        if (count >= GENERIC_MAX_EXECUTIONS) {
            Log.d(TAG, "通用兜底: $packageName 已达最大次数 $count")
            return false
        }

        // 延迟匹配，等 UI 渲染完成后使用新鲜根节点执行
        if (GENERIC_DELAY_MS > 0) {
            handler.postDelayed({
                val freshRoot = rootNodeProvider?.invoke() ?: return@postDelayed
                try {
                    if (performGenericMatch(packageName, freshRoot)) {
                        genericCooldowns[packageName] = System.currentTimeMillis()
                        genericCounters[packageName] = (genericCounters[packageName] ?: 0) + 1
                        Log.d(TAG, "✅ 通用兜底延迟匹配跳过成功: $packageName")
                    }
                } finally {
                    freshRoot.recycle()
                }
            }, GENERIC_DELAY_MS)
            return false
        }

        return performGenericMatch(packageName, rootNode)
    }

    /**
     * 执行通用选择器匹配和点击。
     *
     * 两轮匹配：
     * 1. 首选：过滤 isVisibleToUser=true 的节点（安全）
     * 2. 兜底：不过滤可见性（覆盖广告 SDK 隐藏按钮的情况）
     */
    private fun performGenericMatch(
        packageName: String,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        var foundAnyNodes = false
        for (selectorExpr in GENERIC_SELECTORS) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                if (nodes.isEmpty()) continue
                foundAnyNodes = true

                // 第一轮：只取可见节点
                val visibleNodes = nodes.filter { it.isVisibleToUser }
                if (visibleNodes.isNotEmpty()) {
                    val target = visibleNodes.first()
                    Log.d(TAG, "通用兜底: 可见节点 ${target.text}(${target.viewIdResourceName}) for $packageName")
                    if (ActionExecutor.perform(target, "click")) {
                        Log.d(TAG, "✅ 通用兜底跳过成功: $packageName <- $selectorExpr")
                        return true
                    }
                }

                // 第二轮：如果可见节点点击失败，尝试不可见节点（开屏广告高风险场景）
                val invisibleNodes = nodes.filter { !it.isVisibleToUser }
                if (invisibleNodes.isNotEmpty()) {
                    val target = invisibleNodes.first()
                    Log.d(TAG, "通用兜底: 尝试不可见节点 ${target.text}(${target.viewIdResourceName}) for $packageName")
                    if (ActionExecutor.perform(target, "click")) {
                        Log.d(TAG, "✅ 通用兜底跳过成功(不可见节点): $packageName <- $selectorExpr")
                        return true
                    }
                }
            } catch (e: Exception) {
                DebugConfig.log("SkipEngine: 通用兜底选择器失败: $selectorExpr")
            }
        }

        // 最终兜底：找到过匹配节点但点击失败（如 bounds 为空），尝试全局返回键
        if (foundAnyNodes) {
            Log.d(TAG, "通用兜底: 找到节点但点击均失败，尝试全局返回键: $packageName")
            if (ActionExecutor.performGlobalBack()) {
                Log.d(TAG, "✅ 通用兜底全局返回成功: $packageName")
                return true
            }
        }

        return false
    }

    /**
     * 重置某个 App 的状态（包名变化时或 Activity 重置时）。
     */
    fun resetAppState(packageName: String) {
        actionCounters.clear()
        actionCooldowns.clear()
        genericCooldowns.remove(packageName)
        genericCounters.remove(packageName)
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
     * 每 500ms 扫描一次屏幕无障碍树，一旦检测到"跳过""关闭"等关键词立即点击。
     * 最多监控 15 秒后自动停止。
     *
     * @param packageName 当前前台 App 包名
     */
    fun startWatching(packageName: String, className: String = "") {
        // 跳过冷却期内不重复扫描
        val cooldownEnd = skipCooldowns[packageName] ?: 0L
        if (System.currentTimeMillis() < cooldownEnd) {
            Log.d(TAG, "跳过冷却中，不启动监控: $packageName")
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
            stopWatching()
            return
        }

        // ======== 阶段1：点击后验证 ========
        if (pendingConfirmPkg != null) {
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

        // ======== 阶段2：正常扫描匹配 ========
        val freshRoot = rootNodeProvider?.invoke()
        if (freshRoot == null) {
            Log.v(TAG, "监控扫描: freshRoot 为 null，等待下次扫描: $pkg")
            watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
            return
        }

        try {
            val skipRule = ruleManager.findEnabledRule(pkg)
            if (skipRule != null && !isUnskippableContext(pkg)) {
                if (tryExactRuleScan(skipRule, freshRoot)) {
                    // 已匹配并执行 dispatchGesture，等待手势确认阶段
                    watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                    return
                }
            }

            if (!isUnskippableContext(pkg)) {
                if (tryGenericScan(pkg, freshRoot)) {
                    // 已匹配并执行 dispatchGesture，等待手势确认阶段
                    watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
                    return
                }
            }
        } finally {
            freshRoot.recycle()
        }

        // 未匹配，继续下次扫描
        Log.v(TAG, "监控扫描: 本次未匹配任何节点: $pkg")
        watchHandler.postDelayed(watchScanTask, WATCH_INTERVAL_MS)
    }

    /**
     * 在监控扫描中执行精确规则匹配。
     */
    private fun tryExactRuleScan(
        skipRule: SkipRule,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val cls = watchClassName ?: ""
        val matchedGroups = skipRule.groups.filter { group ->
            group.activityIds.isEmpty() || group.activityIds.any { cls.contains(it) }
        }
        for (group in matchedGroups) {
            for (matchRule in group.rules) {
                if (isOnCooldown(matchRule)) continue
                if (matchRule.actionMaximum > 0 &&
                    (actionCounters[matchRule.toCounterKey(skipRule.id)] ?: 0) >= matchRule.actionMaximum) {
                    continue
                }
                if (executeRuleDirect(skipRule.id, matchRule, rootNode)) {
                    updateCooldown(matchRule)
                    incrementCounter(matchRule, skipRule.id)
                    return true
                }
            }
        }
        return false
    }

    /**
     * 在监控扫描中执行通用规则匹配。
     */
    private fun tryGenericScan(
        packageName: String,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        var hadEmptyBounds = false
        for (selectorExpr in GENERIC_SELECTORS) {
            try {
                val selector = SelectorParser.parse(selectorExpr)
                val nodes = NodeMatcher.find(rootNode, selector)
                if (nodes.isEmpty()) continue

                val target = (nodes.firstOrNull { it.isVisibleToUser }
                    ?: nodes.firstOrNull { !it.isVisibleToUser }) ?: continue

                // 过滤空边界节点
                val bounds = android.graphics.Rect()
                target.getBoundsInScreen(bounds)
                if (bounds.isEmpty) {
                    Log.d(TAG, "通用兜底跳过空边界节点: $selectorExpr")
                    hadEmptyBounds = true
                    continue
                }

                // 小程序用坐标点击命中覆盖层，普通 App 用标准点击（含父节点遍历兜底）
                val clickAction = if (MiniProgramSkipHandler.isMiniProgram(watchClassName))
                    MiniProgramSkipHandler.getClickAction() else "click"
                ActionExecutor.perform(target, clickAction)
                Log.d(TAG, "⏳ 点击已执行，进入验证: $packageName <- $selectorExpr bounds=$bounds")
                pendingConfirmPkg = packageName
                verifyMissCount = 0
                verifyRetryCount = 0
                lastClickSelector = selectorExpr
                return true
            } catch (_: Exception) {}
        }

        // 找到过节点但 bounds 为空，可能是开屏广告页，尝试全局返回键
        if (hadEmptyBounds) {
            Log.d(TAG, "通用兜底: 找到空边界节点，尝试全局返回键: $packageName")
            if (ActionExecutor.performGlobalBack()) {
                Log.d(TAG, "✅ 通用兜底全局返回成功(监控): $packageName")
                return true
            }
        }

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
            } catch (e: Exception) {
                Log.w(TAG, "监控选择器匹配失败: $selectorExpr", e)
            }
        }
        return false
    }

    /**
     * 判断当前上下文是否无法可靠跳过广告。
     */
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
                if (nodes.isEmpty()) continue
                // 只要有一个匹配节点（无论是否可见）就认为广告还在
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /** 记录 App 跳过冷却时间，防止短时间内重复触发 */
    fun recordSkipCooldown(packageName: String) {
        skipCooldowns[packageName] = System.currentTimeMillis() + SKIP_COOLDOWN_MS
    }

    private fun MatchRule.toCooldownKey(): String = "cd_${key}"
    private fun MatchRule.toCounterKey(ruleId: String): String = "${ruleId}_${key}"
}
