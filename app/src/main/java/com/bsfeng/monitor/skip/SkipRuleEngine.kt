package com.bsfeng.monitor.skip

import android.os.Handler
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

    companion object {
        private const val TAG = "SkipRuleEngine"
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
        // 包名变化时重置状态
        if (packageName != currentPackageName) {
            resetAppState(packageName)
            currentPackageName = packageName
        }

        // 查找匹配的规则
        val skipRule = ruleManager.findEnabledRule(packageName) ?: return false

        // 查找匹配的规则组
        val matchedGroups = skipRule.groups.filter { group ->
            group.activityIds.isEmpty() || group.activityIds.any { className.contains(it) }
        }

        if (matchedGroups.isEmpty()) {
            DebugConfig.log("SkipEngine: $packageName 无匹配规则组")
            return false
        }

        var executed = false
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
                    executed = true
                    updateCooldown(matchRule)
                    incrementCounter(matchRule, skipRule.id)
                    Log.d(TAG, "✅ 跳过广告成功: ${skipRule.name} <- ${matchRule.matches.firstOrNull()}")
                    break
                }
            }
            if (executed) break
        }

        return executed
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

        // matchDelay：等待延迟（在 UI 线程上做延迟）
        if (matchRule.matchDelay > 0) {
            // 使用 handler 延迟执行
            val delayExecuted = BooleanArray(1) { false }
            val lock = Object()
            handler.postDelayed({
                synchronized(lock) {
                    delayExecuted[0] = performMatchAndClick(ruleId, matchRule, rootNode)
                    lock.notify()
                }
            }, matchRule.matchDelay)

            // 同步等待（最多 matchDelay + 500ms）
            synchronized(lock) {
                try {
                    lock.wait(matchRule.matchDelay + 500)
                } catch (_: InterruptedException) {}
                return delayExecuted[0]
            }
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

    /**
     * 重置某个 App 的状态（包名变化时或 Activity 重置时）。
     */
    fun resetAppState(packageName: String) {
        actionCounters.clear()
        actionCooldowns.clear()
        DebugConfig.log("SkipEngine: 状态已重置 for $packageName")
    }

    private fun MatchRule.toCooldownKey(): String = "cd_${key}"
    private fun MatchRule.toCounterKey(ruleId: String): String = "${ruleId}_${key}"
}
