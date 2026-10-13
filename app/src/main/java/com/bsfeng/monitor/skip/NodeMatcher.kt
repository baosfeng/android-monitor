package com.bsfeng.monitor.skip

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.SelectorExpr

/**
 * 无障碍节点匹配器（GKD 选择器求值入口）。
 *
 * - 求值规则见 [SelectorExpr]：链式选择器从右往左匹配，返回 `@` 标记（缺省最右段）的目标节点
 * - 属性段满足 GKD「查询优化」格式时使用 `findAccessibilityNodeInfosByViewId/ByText` 快速查询
 * - 具体求值实现见 [SelectorEvaluator] 与 [ExpressionEvaluator]
 *
 * 线程约束：必须在无障碍服务主线程调用（AccessibilityNodeInfo 不可跨线程使用）。
 */
object NodeMatcher {

    private const val TAG = "NodeMatcher"

    /** 在无障碍树中查找选择器目标节点（调用方负责 [recycleAll]） */
    fun find(root: AccessibilityNodeInfo?, selector: SelectorExpr): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val evaluator = SelectorEvaluator()
        return try {
            val nodes = evaluator.eval(root, selector)
            evaluator.recycleDiscarded()
            nodes
        } catch (e: Exception) {
            Log.w(TAG, "选择器求值失败: " + selector, e)
            evaluator.releaseAll()
            emptyList()
        }
    }

    /** 单节点是否匹配属性段 */
    fun matchNode(node: AccessibilityNodeInfo, segment: AttrSegment): Boolean =
        SelectorEvaluator().matchSegment(node, segment)

    /** 安全回收节点列表（幂等） */
    fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                node.recycle()
            } catch (_: Exception) {
                // 已回收的节点再次回收会抛异常，忽略
            }
        }
    }

    /**
     * 页面结构诊断摘要：节点总数/最大深度/可点击节点数/文本节点数。
     * 规则未命中时输出到日志，便于排查选择器写错的情况。
     */
    fun diagnosePageStructure(root: AccessibilityNodeInfo?): String {
        if (root == null) return "root=null"
        var total = 0
        var maxDepth = 0
        var clickable = 0
        var withText = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_SELECTOR_DEPTH) return
            total++
            if (depth > maxDepth) maxDepth = depth
            if (node.isClickable) clickable++
            if (!node.text.isNullOrEmpty()) withText++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        walk(root, 0)
        return "节点=" + total + " 最大深度=" + maxDepth + " 可点击=" + clickable + " 有文本=" + withText
    }
}
