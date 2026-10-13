package com.bsfeng.monitor.skip

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.util.DebugConfig

/**
 * 开屏跳过规则引擎（GKD 语义）。
 *
 * ## 触发模型
 * `MonitorAccessibilityService` 在窗口/内容变化时调用本引擎；引擎以 **App 切换时刻** 为锚点
 * 开启一个匹配窗口（[SkipConfig.watchWindowMs]，默认 10 秒，设置页可选 5/10/15 秒），窗口内：
 * - `TYPE_WINDOW_CONTENT_CHANGED` 事件到达即扫描（100ms 节流）——广告渲染完成的第一时间命中
 * - 兜底轮询每 [BACKUP_SCAN_INTERVAL_MS] 一次，防止事件丢失
 * - 根节点为 null（启动瞬间很常见）时不放弃，等下轮扫描重试
 *
 * ## 防误触
 * - 只执行规则命中的节点动作，**不再有"坐标连点"或"返回键"兜底**（业界调研结论见
 *   `docs/开屏跳过/业界调研与改进方案.md` §4 P0-2）
 * - `matchTime` 限定规则参与匹配的时间窗口；`actionMaximum` + `resetMatch` 限定执行次数；
 *   `actionCd` 限制最小间隔；`excludeMatches` 排除误触场景
 * - 熄屏（Dozing）时不做任何点击
 * - 白名单 App 完全不处理
 *
 * ## 与旧实现的差异（修复的缺陷）
 * - `matchDelay` 改为"到期标记"（[pendingDelays]），不再用 Handler 取消/重投，避免被
 *   CONTENT_CHANGED 风暴无限推迟导致规则永不执行
 * - 匹配起点固定为当前活动窗口根节点，选择器快速查询由 [SelectorCache]/[NodeMatcher] 完成
 */
class SkipRuleEngine(private val ruleManager: SkipRuleManager) {

    /** 一次成功跳过 */
    data class SkipResult(
        val packageName: String,
        val ruleName: String,
        val selector: String,
        val action: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var rootNodeProvider: (() -> AccessibilityNodeInfo?)? = null

    /** 跳过成功回调（服务层用于记录事件与提示） */
    var onSkipSuccess: ((SkipResult) -> Unit)? = null

    // ==================== 运行状态 ====================

    private var currentPackage: String? = null
    private var currentClassName: String = ""
    private var enterTimeMs = 0L
    private var watching = false
    private var scanCount = 0
    private var lastContentTriggerMs = 0L

    /** 规则计数（key = ruleId#groupKey#ruleKey）→ 已执行次数 */
    private val counters = mutableMapOf<String, Int>()

    /** 规则冷却（key 同上）→ 允许执行的时间点 */
    private val cooldowns = mutableMapOf<String, Long>()

    /** matchDelay 到期时间（key 同上）：到期前该规则不参与执行 */
    private val pendingDelays = mutableMapOf<String, Long>()

    /** actionDelay 确认时间（key 同上）：到期且**再次命中**才真正执行动作（GKD `actionDelay`） */
    private val actionConfirmDelays = mutableMapOf<String, Long>()

    /** 各规则组最近一次执行的规则 key（map key = ruleId#groupKey），用于 `preKeys` 顺序闸门 */
    private val lastExecuted = mutableMapOf<String, Int>()

    /** 时钟与屏幕状态提供器（测试注入） */
    internal var currentTimeMillis: () -> Long = { System.currentTimeMillis() }
    internal var screenInteractive: () -> Boolean = { defaultScreenInteractive() }

    /** 匹配窗口提供器（毫秒；默认取用户配置 [SkipConfig.watchWindowMs]，测试可注入） */
    internal var watchWindowProvider: () -> Long = { SkipConfig.watchWindowMs }

    private val scanTask = Runnable { performScan() }

    // ==================== 外部接口 ====================

    /** 设置根节点提供器（服务层提供 `rootInActiveWindow`） */
    fun setRootNodeProvider(provider: () -> AccessibilityNodeInfo?) {
        rootNodeProvider = provider
    }

    /**
     * 窗口状态变化（前台 App 或 Activity 变化）。
     *
     * 新 App：重置计数/冷却并开启匹配窗口；同 App 内 Activity 变化：按 [ResetMatch.ACTIVITY] 重置计数。
     */
    fun onWindowChanged(packageName: String, className: String) {
        if (packageName == MonitorApp.instance.packageName) {
            stopWatching()
            return
        }
        if (isWhitelisted(packageName)) {
            DebugConfig.log("SkipEngine: 白名单跳过 " + packageName)
            return
        }
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，忽略窗口变化 " + packageName)
            return
        }
        val now = currentTimeMillis()
        if (packageName != currentPackage) {
            resetAppState(packageName)
            currentPackage = packageName
            enterTimeMs = now
            DebugConfig.log("SkipEngine: 进入 App " + packageName + " / " + className)
        } else if (className != currentClassName) {
            resetActivityScopedCounters(packageName)
            DebugConfig.log("SkipEngine: Activity 变化 " + className)
        }
        currentClassName = className
        startWatching(now)
    }

    /**
     * 窗口内容变化（事件驱动扫描）。
     *
     * 仅处理正在监控的 App；100ms 节流以吸收 CONTENT_CHANGED 风暴。
     */
    fun onContentChanged(packageName: String) {
        val pkg = currentPackage ?: return
        if (!watching || packageName != pkg) return
        if (!screenInteractive()) return
        val now = currentTimeMillis()
        if (now - lastContentTriggerMs < CONTENT_TRIGGER_INTERVAL_MS) return
        lastContentTriggerMs = now
        handler.removeCallbacks(scanTask)
        performScan()
    }

    /** 停止监控并清理规则状态 */
    fun stopWatching() {
        watching = false
        handler.removeCallbacks(scanTask)
        scanCount = 0
    }

    /** 当前是否处于匹配窗口内 */
    fun isWatching(): Boolean = watching

    /** 正在监控的包名 */
    fun watchingPackage(): String? = if (watching) currentPackage else null

    /** 重置某个 App 的状态（进入本 App 或用户手动重置时调用） */
    fun resetAppState(packageName: String?) {
        counters.clear()
        cooldowns.clear()
        pendingDelays.clear()
        actionConfirmDelays.clear()
        lastExecuted.clear()
        stopWatching()
        if (packageName != null && packageName != currentPackage) {
            currentPackage = null
        }
    }

    // ==================== 匹配窗口 ====================

    private fun startWatching(now: Long) {
        watching = true
        scanCount = 0
        handler.removeCallbacks(scanTask)
        handler.post(scanTask)
        DebugConfig.log("SkipEngine: 开启匹配窗口 " + watchWindowProvider() + "ms, 当前 " + currentPackage +
            " / " + currentClassName + ", now=" + now)
    }

    private fun schedule(delayMs: Long) {
        if (!watching) return
        handler.removeCallbacks(scanTask)
        handler.postDelayed(scanTask, delayMs)
    }

    // ==================== 扫描 ====================

    private fun performScan() {
        val pkg = currentPackage ?: return
        if (!watching) return
        val now = currentTimeMillis()
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，停止扫描 " + pkg)
            stopWatching()
            return
        }
        if (now - enterTimeMs > watchWindowProvider() || scanCount >= MAX_SCANS) {
            DebugConfig.log("SkipEngine: 匹配窗口结束 " + pkg + " (扫描 " + scanCount + " 次)")
            stopWatching()
            return
        }
        scanCount++
        val root = rootNodeProvider?.invoke()
        if (root == null) {
            DebugConfig.log("SkipEngine: 根节点未就绪，稍后重试 " + pkg)
            schedule(BACKUP_SCAN_INTERVAL_MS)
            return
        }
        val result = try {
            applyRules(pkg, root, now)
        } finally {
            root.recycle()
        }
        if (result != null) {
            Log.d(TAG, "✅ 跳过成功: " + pkg + " ← " + result.selector)
            onSkipSuccess?.invoke(result)
            stopWatching()
            return
        }
        schedule(BACKUP_SCAN_INTERVAL_MS)
    }

    /** 依次尝试匹配规则；返回命中的结果 */
    private fun applyRules(packageName: String, root: AccessibilityNodeInfo, now: Long): SkipResult? {
        val rules = ruleManager.findRulesFor(packageName)
            .sortedWith(compareBy({ if (it.isGlobal) 1 else 0 }, { it.id }))
        for (rule in rules) {
            val hit = applyRule(rule, packageName, root, now) ?: continue
            return hit
        }
        DebugConfig.log("SkipEngine: 未命中任何规则 " + packageName + " / " + currentClassName)
        return null
    }

    private fun applyRule(rule: SkipRule, packageName: String, root: AccessibilityNodeInfo, now: Long): SkipResult? {
        for (group in rule.groups.sortedBy { it.order }) {
            if (!group.enabled || !activityMatches(group, packageName)) continue
            for (matchRule in group.rules) {
                val selector = applyMatchRule(rule, group, matchRule, root, now) ?: continue
                val ruleName = matchRule.name.ifEmpty { group.name }
                return SkipResult(packageName, rule.name + "/" + ruleName, selector, matchRule.action.value)
            }
        }
        return null
    }

    /** 执行单条匹配规则；返回命中的选择器 */
    private fun applyMatchRule(
        rule: SkipRule,
        group: RuleGroup,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo,
        now: Long
    ): String? {
        val key = ruleKey(rule.id, group.key, matchRule.key)
        if (!ruleAllowed(rule.id, group, matchRule, now)) return null
        if (matchRule.action == MatchAction.NONE) return null
        if (anySelectorMatches(matchRule.excludeMatches, matchRule, root)) return null
        val target = findTarget(matchRule, root) ?: return null
        if (!delaySatisfied(matchRule, key, now) || !actionDelaySatisfied(matchRule, key, now)) {
            NodeMatcher.recycleAll(target.nodes)
            return null
        }
        val executed = try {
            ActionExecutor.perform(target.node, matchRule.action, matchRule.position)
        } finally {
            NodeMatcher.recycleAll(target.nodes)
        }
        if (!executed) {
            DebugConfig.log("SkipEngine: 动作执行失败 " + key + " → " + target.selector)
            return null
        }
        recordExecution(rule.id, group, matchRule, now)
        return target.selector
    }

    /**
     * 规则是否可执行：匹配窗口、执行次数（含 [MatchRule.actionMaximumKey] 共享）、
     * 冷却（含 [MatchRule.actionCdKey] 共享）、[MatchRule.preKeys] 顺序闸门。
     */
    private fun ruleAllowed(ruleId: String, group: RuleGroup, matchRule: MatchRule, now: Long): Boolean {
        val window = matchRule.matchTime ?: group.matchTime
        if (window > 0 && now - enterTimeMs > window) return false
        val limit = matchRule.actionMaximum ?: group.actionMaximum
        if (limit > 0) {
            val used = counterKeys(ruleId, group.key, matchRule).maxOf { counters[it] ?: 0 }
            if (used >= limit) return false
        }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            val until = cooldownKeys(ruleId, group.key, matchRule).maxOf { cooldowns[it] ?: 0L }
            if (now < until) return false
        }
        if (matchRule.preKeys.isNotEmpty()) {
            val last = lastExecuted[groupScopeKey(ruleId, group.key)]
            if (last == null || last !in matchRule.preKeys) return false
        }
        return true
    }

    /** 记录一次执行：写入计数、冷却与 preKeys 游标 */
    private fun recordExecution(ruleId: String, group: RuleGroup, matchRule: MatchRule, now: Long) {
        counterKeys(ruleId, group.key, matchRule).forEach { counters[it] = (counters[it] ?: 0) + 1 }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            cooldownKeys(ruleId, group.key, matchRule).forEach { cooldowns[it] = now + cooldown }
        }
        lastExecuted[groupScopeKey(ruleId, group.key)] = matchRule.key
    }

    private fun groupScopeKey(ruleId: String, groupKey: Int): String = ruleId + "#" + groupKey

    private fun ruleKey(ruleId: String, groupKey: Int, key: Int): String = groupScopeKey(ruleId, groupKey) + "#" + key

    /** 计数键：自身 + [MatchRule.actionMaximumKey] 共享键 */
    private fun counterKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(ruleKey(ruleId, groupKey, matchRule.key))
        matchRule.actionMaximumKey?.let { keys.add(ruleKey(ruleId, groupKey, it)) }
        return keys.distinct()
    }

    /** 冷却键：自身 + [MatchRule.actionCdKey] 共享键 */
    private fun cooldownKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(ruleKey(ruleId, groupKey, matchRule.key))
        matchRule.actionCdKey?.let { keys.add(ruleKey(ruleId, groupKey, it)) }
        return keys.distinct()
    }

    /**
     * actionDelay 语义：命中后先等待，**到期且仍然命中**才执行动作（GKD `actionDelay`，抗误触）。
     * 与 matchDelay 一样以实现为时间戳，不会被后续扫描取消。
     */
    private fun actionDelaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.actionDelay <= 0) return true
        val confirmAt = actionConfirmDelays[key]
        if (confirmAt == null) {
            actionConfirmDelays[key] = now + matchRule.actionDelay
            DebugConfig.log("SkipEngine: 命中后等待 actionDelay=" + matchRule.actionDelay + "ms 复核 " + key)
            return false
        }
        if (now < confirmAt) return false
        actionConfirmDelays.remove(key)
        return true
    }

    /**
     * matchDelay 语义：首次命中后挂起 [MatchRule.matchDelay]，到期后重新命中才执行。
     * 用"到期时间戳"实现，避免被后续扫描取消（旧实现在 CONTENT_CHANGED 风暴下永不执行）。
     */
    private fun delaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.matchDelay <= 0) return true
        val readyAt = pendingDelays[key]
        if (readyAt == null) {
            pendingDelays[key] = now + matchRule.matchDelay
            DebugConfig.log("SkipEngine: 规则挂起等待 matchDelay=" + matchRule.matchDelay + "ms " + key)
            return false
        }
        return now >= readyAt
    }

    // ==================== 选择器匹配 ====================

    /** 命中的目标节点与用于命中的选择器 */
    private class Target(
        val node: AccessibilityNodeInfo,
        val selector: String,
        val nodes: List<AccessibilityNodeInfo>
    )

    private fun findTarget(matchRule: MatchRule, root: AccessibilityNodeInfo): Target? {
        if (matchRule.matches.isNotEmpty()) return findByAll(matchRule, root)
        for (expr in matchRule.anyMatches) {
            val nodes = query(expr, matchRule, root) ?: continue
            val target = pickTarget(nodes, root)
            if (target != null) return Target(target, expr, nodes)
            NodeMatcher.recycleAll(nodes)
        }
        return null
    }

    /** matches：全部选择器都要命中，点击最后一个选择器的目标节点 */
    private fun findByAll(matchRule: MatchRule, root: AccessibilityNodeInfo): Target? {
        var lastNodes: List<AccessibilityNodeInfo> = emptyList()
        var lastSelector: String? = null
        for (expr in matchRule.matches) {
            val nodes = query(expr, matchRule, root) ?: return null
            if (nodes.isEmpty()) {
                NodeMatcher.recycleAll(nodes)
                NodeMatcher.recycleAll(lastNodes)
                return null
            }
            NodeMatcher.recycleAll(lastNodes)
            lastNodes = nodes
            lastSelector = expr
        }
        val target = pickTarget(lastNodes, root)
        if (target == null || lastSelector == null) {
            NodeMatcher.recycleAll(lastNodes)
            return null
        }
        return Target(target, lastSelector, lastNodes)
    }

    private fun query(expr: String, matchRule: MatchRule, root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
        val selector = SelectorCache.get(expr, matchRule.fastQuery) ?: return null
        return NodeMatcher.find(root, selector)
    }

    private fun anySelectorMatches(
        exprs: List<String>,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo
    ): Boolean {
        for (expr in exprs) {
            val nodes = query(expr, matchRule, root) ?: continue
            val hit = nodes.isNotEmpty()
            NodeMatcher.recycleAll(nodes)
            if (hit) {
                DebugConfig.log("SkipEngine: 命中排除选择器，放弃规则 → " + expr)
                return true
            }
        }
        return false
    }

    /**
     * 选出可点击的目标节点。
     *
     * **面积守卫**：跳过按钮只可能是小块节点；面积超过根节点 1/3 的命中通常是
     * "整屏广告容器"（点中心会进落地页），必须丢弃（业界调研结论，见
     * `docs/开屏跳过/业界调研与改进方案.md` §2.4）。
     */
    private fun pickTarget(nodes: List<AccessibilityNodeInfo>, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootArea = areaOf(root)
        val usable = nodes.filter { !isOversized(it, rootArea) }
        return usable.firstOrNull { it.isVisibleToUser } ?: usable.firstOrNull()
    }

    private fun areaOf(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return if (rect.isEmpty) 0 else rect.width() * rect.height()
    }

    private fun isOversized(node: AccessibilityNodeInfo, rootArea: Int): Boolean {
        if (rootArea <= 0) return false
        val area = areaOf(node)
        // 边界为空的节点交给动作执行器判断（部分可点击节点不暴露 bounds）
        if (area <= 0) return false
        return area * 3 > rootArea
    }

    // ==================== 作用域与状态 ====================

    /** Activity 是否匹配规则组（前缀匹配，`.` 开头自动补包名；exclude 优先） */
    private fun activityMatches(group: RuleGroup, packageName: String): Boolean {
        val className = currentClassName
        if (className.isEmpty()) return group.excludeActivityIds.isEmpty()
        if (group.excludeActivityIds.any { matchesActivity(it, className, packageName) }) return false
        if (group.activityIds.isEmpty()) return true
        return group.activityIds.any { matchesActivity(it, className, packageName) }
    }

    private fun matchesActivity(pattern: String, className: String, packageName: String): Boolean {
        val full = if (pattern.startsWith(".")) packageName + pattern else pattern
        return className == full || className.startsWith(full)
    }

    /** Activity 变化时，重置 [ResetMatch.ACTIVITY] 的作用域计数 */
    private fun resetActivityScopedCounters(packageName: String) {
        val keys = ruleManager.findRulesFor(packageName).flatMap { rule ->
            rule.groups.filter { it.resetMatch == ResetMatch.ACTIVITY }.flatMap { group ->
                group.rules.map { rule.id + "#" + group.key + "#" + it.key }
            }
        }
        keys.forEach {
            counters.remove(it)
            pendingDelays.remove(it)
            actionConfirmDelays.remove(it)
        }
    }

    private fun isWhitelisted(packageName: String): Boolean = try {
        val prefs = MonitorApp.instance.getSharedPreferences(WHITELIST_PREFS, Context.MODE_PRIVATE)
        packageName in (prefs.getStringSet(WHITELIST_KEY, emptySet()) ?: emptySet())
    } catch (_: Exception) {
        false
    }

    private fun defaultScreenInteractive(): Boolean = try {
        val manager = MonitorApp.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isInteractive ?: true
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val TAG = "SkipRuleEngine"
        const val WHITELIST_PREFS = "skip_whitelist"
        const val WHITELIST_KEY = "whitelist"

        /** 兜底轮询间隔（毫秒；主驱动为 CONTENT_CHANGED 事件） */
        const val BACKUP_SCAN_INTERVAL_MS = 300L

        /** CONTENT_CHANGED 触发扫描的节流间隔 */
        const val CONTENT_TRIGGER_INTERVAL_MS = 100L

        /** 单次窗口内的扫描次数上限（护栏，防止异常场景空转） */
        const val MAX_SCANS = 80
    }
}
