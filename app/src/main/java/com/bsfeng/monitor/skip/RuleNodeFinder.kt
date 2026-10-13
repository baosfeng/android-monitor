package com.bsfeng.monitor.skip

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.util.DebugConfig

/**
 * 规则命中节点的查找与筛选（选择器求值 + 面积守卫）。
 *
 * 从 [SkipRuleEngine] 抽出的**无状态**部分：只回答「这次命中了哪个节点」，
 * 不关心匹配窗口、执行次数、冷却等闸门（那些留在引擎里）。
 * 抽出的另一个好处：选择器求值与节点回收可以被单独测试。
 */
internal object RuleNodeFinder {

    /** 命中的目标节点与用于命中的选择器（[nodes] 需由调用方回收） */
    class Target(
        val node: AccessibilityNodeInfo,
        val selector: String,
        val nodes: List<AccessibilityNodeInfo>
    )

    /**
     * 找出本条匹配规则要点击的节点。
     *
     * - [MatchRule.matches] 非空：全部选择器都要命中，点击**最后一个**选择器的目标节点；
     * - 否则遍历 [MatchRule.anyMatches]，任一命中即用该节点。
     */
    fun findTarget(matchRule: MatchRule, root: AccessibilityNodeInfo): Target? {
        if (matchRule.matches.isNotEmpty()) return findByAll(matchRule, root)
        for (expr in matchRule.anyMatches) {
            val nodes = query(expr, matchRule.fastQuery, root) ?: continue
            val target = pickTarget(nodes, root)
            if (target != null) return Target(target, expr, nodes)
            NodeMatcher.recycleAll(nodes)
        }
        return null
    }

    /**
     * 单个选择器是否仍能匹配到节点（点击后确认的复核用）。
     *
     * @return true 仍匹配；false 已匹配不到；null 选择器解析失败（无法判断）
     */
    fun matches(expr: String, fastQuery: Boolean, root: AccessibilityNodeInfo): Boolean? {
        val nodes = query(expr, fastQuery, root) ?: return null
        val hit = nodes.isNotEmpty()
        NodeMatcher.recycleAll(nodes)
        return hit
    }

    /** 任一排除选择器命中即返回 true（命中则放弃本规则，防误触） */
    fun anySelectorMatches(exprs: List<String>, fastQuery: Boolean, root: AccessibilityNodeInfo): Boolean {
        for (expr in exprs) {
            if (matches(expr, fastQuery, root) != true) continue
            DebugConfig.log("SkipEngine: 命中排除选择器，放弃规则 → " + expr)
            return true
        }
        return false
    }

    /** matches：全部选择器都要命中，点击最后一个选择器的目标节点 */
    private fun findByAll(matchRule: MatchRule, root: AccessibilityNodeInfo): Target? {
        var lastNodes: List<AccessibilityNodeInfo> = emptyList()
        var lastSelector: String? = null
        for (expr in matchRule.matches) {
            val nodes = query(expr, matchRule.fastQuery, root) ?: return null
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

    /**
     * 选择器求值：解析失败（规则写错）返回 null，由调用方决定是否继续尝试。
     */
    private fun query(expr: String, fastQuery: Boolean, root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
        val selector = SelectorCache.get(expr, fastQuery) ?: return null
        return NodeMatcher.find(root, selector)
    }

    /**
     * 选出可点击的目标节点。
     *
     * **面积守卫**：跳过按钮只可能是小块节点；面积超过根节点 1/3 的命中通常是
     * 「整屏广告容器」（点中心会进落地页），必须丢弃（业界调研结论，见
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
}
