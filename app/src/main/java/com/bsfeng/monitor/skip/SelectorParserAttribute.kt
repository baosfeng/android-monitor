package com.bsfeng.monitor.skip

// 来源：原 skip/SelectorParser.kt 的 Parser；职责：属性段解析（`@` 目标标记、类名简写、`[表达式]` 分组）与快速查询提示。

import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.BoolExpr
import com.bsfeng.monitor.domain.model.CompareExpr
import com.bsfeng.monitor.domain.model.CompareOp
import com.bsfeng.monitor.domain.model.FastAttr
import com.bsfeng.monitor.domain.model.FastHint
import com.bsfeng.monitor.domain.model.IdentValue
import com.bsfeng.monitor.domain.model.LiteralValue
import com.bsfeng.monitor.domain.model.NotExpr
import com.bsfeng.monitor.domain.model.OrExpr

/** 属性段：可选 `@` + 可选类名 + 若干 `[表达式]` */
internal fun Parser.parseAttrSegment(): AttrSegment {
    var isTarget = false
    if (peek() == '@') {
        pos++
        isTarget = true
    }
    var nameTag: String? = null
    if (peek() == '*') {
        pos++
    } else if (peek().isLetter()) {
        nameTag = readTagName()
    }
    val groups = mutableListOf<BoolExpr>()
    while (peek() == '[') {
        pos++
        skipWs()
        groups.add(parseBoolOr())
        skipWs()
        expect(']')
    }
    val bracketExpr = when (groups.size) {
        0 -> null
        1 -> groups[0]
        else -> AndExpr(groups)
    }
    if (nameTag == null && bracketExpr == null) {
        throw IllegalArgumentException("属性段缺少类名或属性条件: " + src.substring(pos))
    }
    return AttrSegment(
        nameTag = nameTag,
        expr = combineTag(nameTag, bracketExpr),
        isTarget = isTarget,
        fastHints = extractFastHints(bracketExpr)
    )
}

/** `TextView` 等价于 `[name='TextView'||name$='.TextView']` */
private fun combineTag(tag: String?, expr: BoolExpr?): BoolExpr? {
    if (tag == null) return expr
    val tagExpr = OrExpr(
        listOf(
            CompareExpr(IdentValue("name"), CompareOp.EQ, LiteralValue(tag)),
            CompareExpr(IdentValue("name"), CompareOp.ENDS_WITH, LiteralValue("." + tag))
        )
    )
    return if (expr == null) tagExpr else AndExpr(listOf(tagExpr, expr))
}

/** 提取可走原生 API 的快速查询提示（GKD「查询优化」） */
private fun extractFastHints(expr: BoolExpr?): List<FastHint> = when (expr) {
    null -> emptyList()
    is CompareExpr -> listOfNotNull(hintOf(expr))
    is AndExpr -> expr.items.firstNotNullOfOrNull { extractFastHints(it).takeIf { h -> h.isNotEmpty() } }
        ?: emptyList()
    is OrExpr -> {
        val perBranch = expr.items.map { extractFastHints(it) }
        if (perBranch.all { it.size == 1 }) perBranch.flatten() else emptyList()
    }
    is NotExpr -> emptyList()
}

/**
 * 判断属性表达式能否安全地转为原生 API 快速查询。
 *
 * 安全前提：原生查询结果必须是"完整匹配结果"的超集，否则会漏匹配。
 * - `[text=]`/`[text*=]`/`[text^=]`/`[text$=]`：`findAccessibilityNodeInfosByText` 为包含匹配，均可作为超集
 * - `[vid=]`/`[id=]`：`findAccessibilityNodeInfosByViewId` 仅支持精确 ID，因此只有 `=` 可用
 * - 取反表达式一律不参与快速查询
 */
private fun hintOf(expr: CompareExpr): FastHint? {
    val left = expr.left as? IdentValue ?: return null
    val attr = when (left.name) {
        "id" -> FastAttr.ID
        "vid" -> FastAttr.VID
        "text" -> FastAttr.TEXT
        else -> return null
    }
    val allowed = if (attr == FastAttr.TEXT) FAST_QUERY_TEXT_OPS else setOf(CompareOp.EQ)
    if (expr.op !in allowed) return null
    val value = (expr.right as? LiteralValue)?.value as? String ?: return null
    if (value.length < 2) return null
    return FastHint(attr, expr.op, value)
}
