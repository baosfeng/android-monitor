package com.bsfeng.monitor.skip

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.BoolExpr
import com.bsfeng.monitor.domain.model.CallValue
import com.bsfeng.monitor.domain.model.CompareExpr
import com.bsfeng.monitor.domain.model.CompareOp
import com.bsfeng.monitor.domain.model.IdentValue
import com.bsfeng.monitor.domain.model.LiteralValue
import com.bsfeng.monitor.domain.model.MemberValue
import com.bsfeng.monitor.domain.model.NotExpr
import com.bsfeng.monitor.domain.model.OrExpr
import com.bsfeng.monitor.domain.model.ValueExpr
import java.util.regex.Pattern

/**
 * 值/布尔表达式求值器（GKD 选择器属性表达式子集）。
 *
 * 语义要点（对齐 GKD）：
 * - 除 `=`/`!=` 外的操作符在任一侧为 null 时结果为 false
 * - 字符串类操作符大小写敏感（"skip" 与 "Skip" 需分别写）
 * - `name` = 类名，`id` = 完整视图 ID，`vid` = `id` 中 `/` 之后的部分
 *
 * @param owner 回调：把新建的节点包装交给调用方统一回收
 */
internal class ExpressionEvaluator(private val owner: (AccessibilityNodeInfo) -> Unit) {

    fun evalBool(node: AccessibilityNodeInfo, expr: BoolExpr, prev: AccessibilityNodeInfo?): Boolean = when (expr) {
        is CompareExpr -> compare(
            evalValue(node, expr.left, prev),
            expr.op,
            evalValue(node, expr.right, prev)
        )
        is AndExpr -> expr.items.all { evalBool(node, it, prev) }
        is OrExpr -> expr.items.any { evalBool(node, it, prev) }
        is NotExpr -> !evalBool(node, expr.inner, prev)
    }

    fun evalValue(node: AccessibilityNodeInfo, expr: ValueExpr, prev: AccessibilityNodeInfo?): Any? = when (expr) {
        is LiteralValue -> expr.value
        is IdentValue -> when (expr.name) {
            "current" -> node
            "prev" -> prev
            else -> nodeProperty(node, expr.name)
        }
        is MemberValue -> memberOf(evalValue(node, expr.target, prev), expr.name)
        is CallValue -> callOf(node, expr, prev)
    }

    private fun memberOf(target: Any?, name: String): Any? = when (target) {
        is String -> if (name == "length") target.length else null
        is AccessibilityNodeInfo -> nodeProperty(target, name)
        else -> null
    }

    private fun callOf(node: AccessibilityNodeInfo, call: CallValue, prev: AccessibilityNodeInfo?): Any? {
        val target = call.target?.let { evalValue(node, it, prev) }
        val args = call.args.map { evalValue(node, it, prev) }
        return when {
            target is AccessibilityNodeInfo -> nodeCall(target, call.name, args)
            target is String -> stringCall(target, call.name, args)
            target is Int -> intCall(target, call.name, args)
            target is Boolean -> booleanCall(target, call.name, args)
            else -> null
        }
    }

    private fun nodeCall(target: AccessibilityNodeInfo, name: String, args: List<Any?>): Any? {
        if (name != "getChild") return null
        val index = args.firstOrNull() as? Int ?: return null
        val child = try {
            target.getChild(index)
        } catch (_: Exception) {
            null
        } ?: return null
        owner(child)
        return child
    }

    private fun stringCall(target: String, name: String, args: List<Any?>): Any? = when (name) {
        "length" -> target.length
        "get" -> (args.firstOrNull() as? Int)?.let { if (it in target.indices) target[it].toString() else null }
        "at" -> (args.firstOrNull() as? Int)?.let { target.getOrNull(if (it < 0) target.length + it else it)?.toString() }
        "substring" -> substringOf(target, args)
        "indexOf" -> {
            val needle = args.firstOrNull() as? String
            if (needle == null) null else target.indexOf(needle, args.getOrNull(1) as? Int ?: 0)
        }
        "toInt" -> target.trim().toIntOrNull()
        "toString" -> target
        else -> null
    }

    private fun substringOf(target: String, args: List<Any?>): String? {
        val from = args.firstOrNull() as? Int ?: return null
        val to = args.getOrNull(1) as? Int ?: return if (from in 0..target.length) target.substring(from) else null
        return if (from in 0..to && to <= target.length) target.substring(from, to) else null
    }

    private fun intCall(target: Int, name: String, args: List<Any?>): Any? {
        val other = args.firstOrNull() as? Int
        return when (name) {
            "plus" -> other?.let { target + it }
            "minus" -> other?.let { target - it }
            "times" -> other?.let { target * it }
            "div" -> other?.takeIf { it != 0 }?.let { target / it }
            "rem" -> other?.takeIf { it != 0 }?.let { target % it }
            "toString" -> target.toString()
            "toInt" -> target
            "more" -> other?.let { target > it }
            "moreEqual" -> other?.let { target >= it }
            "less" -> other?.let { target < it }
            "lessEqual" -> other?.let { target <= it }
            else -> null
        }
    }

    private fun booleanCall(target: Boolean, name: String, args: List<Any?>): Any? = when (name) {
        "toInt" -> if (target) 1 else 0
        "or" -> target || (args.firstOrNull() as? Boolean ?: false)
        "and" -> target && (args.firstOrNull() as? Boolean ?: false)
        "not" -> !target
        "ifElse" -> if (target) args.getOrNull(0) else args.getOrNull(1)
        else -> null
    }

    private fun compare(left: Any?, op: CompareOp, right: Any?): Boolean = when (op) {
        CompareOp.EQ -> left == right
        CompareOp.NOT_EQ -> left != right
        CompareOp.GT -> (left as? Int)?.let { l -> (right as? Int)?.let { r -> l > r } } ?: false
        CompareOp.GTE -> (left as? Int)?.let { l -> (right as? Int)?.let { r -> l >= r } } ?: false
        CompareOp.LT -> (left as? Int)?.let { l -> (right as? Int)?.let { r -> l < r } } ?: false
        CompareOp.LTE -> (left as? Int)?.let { l -> (right as? Int)?.let { r -> l <= r } } ?: false
        CompareOp.STARTS_WITH -> bothStrings(left, right) { l, r -> l.startsWith(r) }
        CompareOp.NOT_STARTS_WITH -> bothStrings(left, right) { l, r -> !l.startsWith(r) }
        CompareOp.CONTAINS -> bothStrings(left, right) { l, r -> l.contains(r) }
        CompareOp.NOT_CONTAINS -> bothStrings(left, right) { l, r -> !l.contains(r) }
        CompareOp.ENDS_WITH -> bothStrings(left, right) { l, r -> l.endsWith(r) }
        CompareOp.NOT_ENDS_WITH -> bothStrings(left, right) { l, r -> !l.endsWith(r) }
        CompareOp.MATCHES -> regexMatch(left, right, negate = false)
        CompareOp.NOT_MATCHES -> regexMatch(left, right, negate = true)
    }

    private fun regexMatch(left: Any?, right: Any?, negate: Boolean): Boolean =
        bothStrings(left, right) { l, r ->
            val matched = try {
                Pattern.compile(r).matcher(l).find()
            } catch (_: Exception) {
                false
            }
            if (negate) !matched else matched
        }

    /** 字符串类操作符：任一侧为 null 时整体为 false（对齐 GKD 语义） */
    private fun bothStrings(left: Any?, right: Any?, block: (String, String) -> Boolean): Boolean {
        val l = left as? String ?: return false
        val r = right as? String ?: return false
        return block(l, r)
    }

    // ==================== 节点属性 ====================

    private fun nodeProperty(node: AccessibilityNodeInfo, name: String): Any? = when (name) {
        "id" -> node.viewIdResourceName
        "vid" -> node.viewIdResourceName?.substringAfterLast('/')
        "name" -> node.className?.toString()
        "text" -> node.text?.toString()
        "desc" -> node.contentDescription?.toString()
        "clickable" -> node.isClickable
        "longClickable" -> node.isLongClickable
        "focusable" -> node.isFocusable
        "checkable" -> node.isCheckable
        "checked" -> node.isChecked
        "editable" -> node.isEditable
        "visibleToUser" -> node.isVisibleToUser
        "visible" -> node.isVisibleToUser
        "enabled" -> node.isEnabled
        "selected" -> node.isSelected
        "password" -> node.isPassword
        "childCount" -> node.childCount
        "index" -> indexOfParent(node)
        "depth" -> depthOf(node)
        "package" -> node.packageName?.toString()
        "parent" -> parentOf(node)
        "left", "top", "right", "bottom", "width", "height" -> boundsValue(node, name)
        else -> null
    }

    private fun parentOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = try {
            node.getParent()
        } catch (_: Exception) {
            null
        } ?: return null
        owner(parent)
        return parent
    }

    private fun boundsValue(node: AccessibilityNodeInfo, name: String): Int? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null
        return when (name) {
            "left" -> rect.left
            "top" -> rect.top
            "right" -> rect.right
            "bottom" -> rect.bottom
            "width" -> rect.width()
            "height" -> rect.height()
            else -> null
        }
    }

    private fun indexOfParent(node: AccessibilityNodeInfo): Int? {
        val parent = parentOf(node) ?: return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val same = child == node
            child.recycle()
            if (same) return i
        }
        return null
    }

    private fun depthOf(node: AccessibilityNodeInfo): Int? {
        var depth = 0
        var current: AccessibilityNodeInfo? = node
        while (current != null && depth < MAX_SELECTOR_DEPTH) {
            val parent = parentOf(current) ?: return depth
            depth++
            current = parent
        }
        return depth
    }
}
