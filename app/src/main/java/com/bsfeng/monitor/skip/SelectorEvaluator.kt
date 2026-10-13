package com.bsfeng.monitor.skip

import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.FastAttr
import com.bsfeng.monitor.domain.model.FastHint
import com.bsfeng.monitor.domain.model.Relation
import com.bsfeng.monitor.domain.model.RelationOp
import com.bsfeng.monitor.domain.model.RelationRange
import com.bsfeng.monitor.domain.model.SelectorAnd
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorExpr
import com.bsfeng.monitor.domain.model.SelectorNot
import com.bsfeng.monitor.domain.model.SelectorOr

/** 选择器匹配的最大遍历深度（防止异常节点树导致的无限递归） */
internal const val MAX_SELECTOR_DEPTH = 60

/**
 * 选择器求值上下文（一次匹配过程）。
 *
 * 负责：链式选择器的从右往左匹配、关系算子求解、快速查询，以及本次匹配产生
 * 的 [AccessibilityNodeInfo] 包装的所有权管理（除结果节点外全部回收）。
 */
internal class SelectorEvaluator {

    /** 本次匹配创建（需回收）的节点包装 */
    private val owned = mutableListOf<AccessibilityNodeInfo>()

    /** 需要保留（作为结果返回）的节点 */
    private val keep = mutableSetOf<AccessibilityNodeInfo>()

    private val expressions = ExpressionEvaluator { owned.add(it) }

    /** 求值选择器，返回目标节点列表（未回收，调用方负责 [NodeMatcher.recycleAll]） */
    fun eval(scope: AccessibilityNodeInfo, selector: SelectorExpr): List<AccessibilityNodeInfo> = when (selector) {
        is SelectorChain -> evalChain(scope, selector)
        is SelectorOr -> {
            var result: List<AccessibilityNodeInfo> = emptyList()
            for (item in selector.items) {
                result = eval(scope, item)
                if (result.isNotEmpty()) break
            }
            result
        }
        is SelectorAnd -> {
            if (eval(scope, selector.items.first()).isEmpty()) {
                emptyList()
            } else {
                eval(scope, selector.items.last())
            }
        }
        // 取反表达式没有可点击的目标节点
        is SelectorNot -> emptyList()
    }

    /** 单节点是否匹配属性段 */
    fun matchSegment(node: AccessibilityNodeInfo, segment: AttrSegment): Boolean {
        val expr = segment.expr ?: return true
        return expressions.evalBool(node, expr, null)
    }

    private fun evalChain(scope: AccessibilityNodeInfo, chain: SelectorChain): List<AccessibilityNodeInfo> {
        val lastIndex = chain.parts.lastIndex
        val candidates = querySegment(scope, chain.parts[lastIndex].segment)
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (candidate in candidates) {
            val matched = arrayOfNulls<AccessibilityNodeInfo>(chain.parts.size)
            if (!matchBackwards(chain, lastIndex, candidate, matched)) continue
            val target = matched[chain.targetIndex] ?: continue
            keep.add(target)
            for (node in matched) {
                if (node != null) keep.add(node)
            }
            if (results.none { it == target }) results.add(target)
        }
        return results
    }

    /** 从右往左逐段校验关系 */
    private fun matchBackwards(
        chain: SelectorChain,
        index: Int,
        current: AccessibilityNodeInfo,
        matched: Array<AccessibilityNodeInfo?>
    ): Boolean {
        matched[index] = current
        if (index == 0) return true
        val relation = chain.parts[index].relation ?: return false
        val leftSegment = chain.parts[index - 1].segment
        for (candidate in relatedNodes(current, relation, leftSegment)) {
            if (matchBackwards(chain, index - 1, candidate, matched)) return true
        }
        return false
    }

    /** 按关系算子求左侧属性段的候选节点 */
    private fun relatedNodes(
        current: AccessibilityNodeInfo,
        relation: Relation,
        segment: AttrSegment
    ): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        when (relation.op) {
            RelationOp.ANCESTOR -> collectAncestors(current, relation.range, segment, out)
            RelationOp.CHILD -> collectChildren(current, relation.range, segment, out)
            RelationOp.DESCENDANT -> {
                if (relation.range is RelationRange.Any) {
                    out.addAll(querySegment(current, segment))
                } else {
                    collectDescendants(current, relation.range, segment, out)
                }
            }
            RelationOp.PRECEDING_SIBLING -> collectSiblings(current, relation.range, segment, true, out)
            RelationOp.FOLLOWING_SIBLING -> collectSiblings(current, relation.range, segment, false, out)
        }
        return out
    }

    private fun collectAncestors(
        current: AccessibilityNodeInfo,
        range: RelationRange,
        segment: AttrSegment,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        var node: AccessibilityNodeInfo? = current
        var depth = 0
        while (node != null && depth < MAX_SELECTOR_DEPTH) {
            val parent = try {
                node.getParent()
            } catch (_: Exception) {
                null
            } ?: break
            owned.add(parent)
            depth++
            if (range.contains(depth) && matchSegment(parent, segment)) out.add(parent)
            node = parent
        }
    }

    private fun collectChildren(
        current: AccessibilityNodeInfo,
        range: RelationRange,
        segment: AttrSegment,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        for (i in 0 until current.childCount) {
            val child = current.getChild(i) ?: continue
            owned.add(child)
            if (range.contains(i + 1) && matchSegment(child, segment)) out.add(child)
        }
    }

    private fun collectDescendants(
        scope: AccessibilityNodeInfo,
        range: RelationRange,
        segment: AttrSegment,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        var order = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_SELECTOR_DEPTH) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                owned.add(child)
                order++
                if (range.contains(order) && matchSegment(child, segment)) out.add(child)
                visit(child, depth + 1)
            }
        }
        visit(scope, 0)
    }

    private fun collectSiblings(
        current: AccessibilityNodeInfo,
        range: RelationRange,
        segment: AttrSegment,
        preceding: Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val parent = try {
            current.getParent()
        } catch (_: Exception) {
            null
        } ?: return
        owned.add(parent)
        val index = indexOfChild(parent, current)
        if (index < 0) return
        for (i in 0 until parent.childCount) {
            if (preceding && i >= index) continue
            if (!preceding && i <= index) continue
            val delta = if (preceding) index - i else i - index
            if (!range.contains(delta)) continue
            val sibling = parent.getChild(i) ?: continue
            owned.add(sibling)
            if (matchSegment(sibling, segment)) out.add(sibling)
        }
    }

    private fun indexOfChild(parent: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val same = child == target
            child.recycle()
            if (same) return i
        }
        return -1
    }

    /** 查询（含快速查询优化）并过滤出真正满足属性段的节点 */
    private fun querySegment(scope: AccessibilityNodeInfo, segment: AttrSegment): List<AccessibilityNodeInfo> {
        val fast = fastQueryCandidates(scope, segment) ?: dfsCollect(scope, segment)
        return fast.filter { matchSegment(it, segment) }
    }

    /** 快速查询候选集；返回 null 表示该属性段不适合快速查询 */
    private fun fastQueryCandidates(scope: AccessibilityNodeInfo, segment: AttrSegment): List<AccessibilityNodeInfo>? {
        if (segment.fastHints.isEmpty()) return null
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (hint in segment.fastHints) {
            val found = try {
                searchByHint(scope, hint)
            } catch (_: Exception) {
                return null
            }
            out.addAll(found)
        }
        return out
    }

    private fun searchByHint(scope: AccessibilityNodeInfo, hint: FastHint): List<AccessibilityNodeInfo> {
        val nodes = when (hint.attr) {
            FastAttr.ID -> scope.findAccessibilityNodeInfosByViewId(hint.value)
            FastAttr.VID -> {
                val pkg = scope.packageName?.toString()
                if (pkg.isNullOrEmpty()) {
                    emptyList()
                } else {
                    scope.findAccessibilityNodeInfosByViewId(pkg + ":id/" + hint.value)
                }
            }
            FastAttr.TEXT -> scope.findAccessibilityNodeInfosByText(hint.value)
        } ?: emptyList()
        owned.addAll(nodes)
        return nodes
    }

    /** 深度优先遍历（含 scope 自身）收集匹配节点 */
    private fun dfsCollect(scope: AccessibilityNodeInfo, segment: AttrSegment): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_SELECTOR_DEPTH) return
            if (matchSegment(node, segment)) out.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                owned.add(child)
                visit(child, depth + 1)
            }
        }
        visit(scope, 0)
        return out
    }

    /** 标记结果节点（不被回收）；其余本次创建的包装全部回收 */
    fun recycleDiscarded() {
        if (owned.isEmpty()) return
        val toRecycle = owned.filter { it !in keep }
        owned.clear()
        NodeMatcher.recycleAll(toRecycle)
    }

    /** 求值异常时强制清空本次创建的所有节点 */
    fun releaseAll() {
        val all = owned.toList()
        owned.clear()
        NodeMatcher.recycleAll(all)
    }
}
