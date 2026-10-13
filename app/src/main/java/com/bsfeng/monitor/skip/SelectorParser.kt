package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.BoolExpr
import com.bsfeng.monitor.domain.model.CallValue
import com.bsfeng.monitor.domain.model.CompareExpr
import com.bsfeng.monitor.domain.model.CompareOp
import com.bsfeng.monitor.domain.model.FastAttr
import com.bsfeng.monitor.domain.model.FastHint
import com.bsfeng.monitor.domain.model.IdentValue
import com.bsfeng.monitor.domain.model.LiteralValue
import com.bsfeng.monitor.domain.model.MemberValue
import com.bsfeng.monitor.domain.model.NotExpr
import com.bsfeng.monitor.domain.model.OrExpr
import com.bsfeng.monitor.domain.model.Relation
import com.bsfeng.monitor.domain.model.RelationOp
import com.bsfeng.monitor.domain.model.RelationRange
import com.bsfeng.monitor.domain.model.SelectorAnd
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorExpr
import com.bsfeng.monitor.domain.model.SelectorNot
import com.bsfeng.monitor.domain.model.SelectorOr
import com.bsfeng.monitor.domain.model.SelectorPart
import com.bsfeng.monitor.domain.model.ValueExpr

/**
 * 选择器解析器（GKD 选择器子集）。
 *
 * 语法见 [SelectorExpr] 的文档。解析失败抛 [IllegalArgumentException]，
 * 调用方应使用 [tryParse] 做容错（单条规则解析失败不应影响其它规则）。
 */
object SelectorParser {

    /** 解析选择器；失败抛 [IllegalArgumentException] */
    fun parse(input: String): SelectorExpr = Parser(input).parseAll()

    /** 容错解析：失败返回 null（不抛异常），供规则加载/引擎执行使用 */
    fun tryParse(input: String): SelectorExpr? = try {
        parse(input)
    } catch (_: Exception) {
        null
    }
}

/** 单遍扫描的递归下降解析器 */
private class Parser(private val src: String) {

    private var pos = 0

    fun parseAll(): SelectorExpr {
        if (src.isBlank()) throw IllegalArgumentException("选择器不能为空")
        val expr = parseSelectorOr()
        skipWs()
        if (pos != src.length) throw IllegalArgumentException("选择器存在多余内容: " + src.substring(pos))
        return expr
    }

    // ==================== 选择器层（链式 + 逻辑） ====================

    private fun parseSelectorOr(): SelectorExpr {
        val items = mutableListOf(parseSelectorAnd())
        while (matchAfterWhitespace("||")) items.add(parseSelectorAnd())
        return if (items.size == 1) items[0] else SelectorOr(items)
    }

    private fun parseSelectorAnd(): SelectorExpr {
        val items = mutableListOf(parseSelectorUnary())
        while (matchAfterWhitespace("&&")) items.add(parseSelectorUnary())
        return if (items.size == 1) items[0] else SelectorAnd(items)
    }

    private fun parseSelectorUnary(): SelectorExpr {
        skipWs()
        if (peek() == '!' && peek(1) == '(') {
            pos += 2
            val inner = parseSelectorOr()
            expect(')')
            return SelectorNot(inner)
        }
        if (peek() == '(') {
            pos++
            val inner = parseSelectorOr()
            expect(')')
            return inner
        }
        return parseChain()
    }

    /** 链式选择器：`A > B`、`A + B`、`A B`、单段 `A` */
    private fun parseChain(): SelectorExpr {
        val parts = mutableListOf(SelectorPart(parseAttrSegment(), null))
        while (true) {
            val save = pos
            val hadWhitespace = skipWs() > 0
            if (isChainEnd()) {
                pos = save
                break
            }
            val relation = parseRelation(hadWhitespace)
            if (relation == null) {
                pos = save
                break
            }
            skipWs()
            parts.add(SelectorPart(parseAttrSegment(), relation))
        }
        return SelectorChain(parts)
    }

    private fun isChainEnd(): Boolean {
        if (pos >= src.length) return true
        return peek() in CHAIN_END_CHARS
    }

    /** 解析关系算子（含隐式空白=任意祖先）；无关系返回 null */
    private fun parseRelation(hadWhitespace: Boolean): Relation? {
        val c = peek()
        return when {
            c == '>' -> {
                pos++
                Relation(RelationOp.ANCESTOR, parseRange())
            }
            c == '<' -> {
                pos++
                if (peek() == '<') {
                    pos++
                    Relation(RelationOp.DESCENDANT, parseRange())
                } else {
                    Relation(RelationOp.CHILD, parseRange())
                }
            }
            c == '+' -> {
                pos++
                Relation(RelationOp.PRECEDING_SIBLING, parseRange())
            }
            c == '-' -> {
                if (peek(1) == '>') throw IllegalArgumentException("暂不支持关系算子 ->")
                pos++
                Relation(RelationOp.FOLLOWING_SIBLING, parseRange())
            }
            hadWhitespace -> Relation(RelationOp.ANCESTOR, RelationRange.Any)
            else -> null
        }
    }

    /** 解析关系表达式：`>n`、`<<2`、`+(1,2,3)`；缺省为单步 */
    private fun parseRange(): RelationRange {
        if (peek() == '(') {
            pos++
            val start = pos
            while (pos < src.length && peek() != ')') pos++
            val text = src.substring(start, pos)
            expect(')')
            return RelationRange.parse(text)
        }
        val start = pos
        while (pos < src.length && peek() in RANGE_CHARS) pos++
        if (start == pos) return RelationRange.Tuple(setOf(1))
        return RelationRange.parse(src.substring(start, pos))
    }

    // ==================== 属性段 ====================

    /** 属性段：可选 `@` + 可选类名 + 若干 `[表达式]` */
    private fun parseAttrSegment(): AttrSegment {
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

    // ==================== 布尔表达式层 ====================

    private fun parseBoolOr(): BoolExpr {
        val items = mutableListOf(parseBoolAnd())
        while (matchAfterWhitespace("||")) items.add(parseBoolAnd())
        return if (items.size == 1) items[0] else OrExpr(items)
    }

    private fun parseBoolAnd(): BoolExpr {
        val items = mutableListOf(parseBoolUnary())
        while (matchAfterWhitespace("&&")) items.add(parseBoolUnary())
        return if (items.size == 1) items[0] else AndExpr(items)
    }

    private fun parseBoolUnary(): BoolExpr {
        skipWs()
        if (peek() == '!' && peek(1) == '(') {
            pos += 2
            val inner = parseBoolOr()
            expect(')')
            return NotExpr(inner)
        }
        if (peek() == '(') {
            pos++
            val inner = parseBoolOr()
            expect(')')
            return inner
        }
        return parseCompare()
    }

    private fun parseCompare(): BoolExpr {
        val left = parseValue()
        skipWs()
        val op = readCompareOp() ?: throw IllegalArgumentException("缺少比较操作符: " + src.substring(pos))
        skipWs()
        val right = parseValue()
        return CompareExpr(left, op, right)
    }

    // ==================== 值表达式层 ====================

    private fun parseValue(): ValueExpr {
        skipWs()
        var value: ValueExpr = when {
            peek() in QUOTE_CHARS -> LiteralValue(readString())
            peek() == '-' || peek().isDigit() -> LiteralValue(readInt())
            src.startsWith("null", pos) && isBoundary(pos + 4) -> {
                pos += 4
                LiteralValue(null)
            }
            src.startsWith("true", pos) && isBoundary(pos + 4) -> {
                pos += 4
                LiteralValue(true)
            }
            src.startsWith("false", pos) && isBoundary(pos + 5) -> {
                pos += 5
                LiteralValue(false)
            }
            peek().isLetter() || peek() == '_' -> {
                val name = readName()
                if (peek() == '(') CallValue(null, name, readArgs()) else IdentValue(name)
            }
            else -> throw IllegalArgumentException("非法值表达式: " + src.substring(pos))
        }
        while (peek() == '.') {
            pos++
            val member = readName()
            value = if (peek() == '(') CallValue(value, member, readArgs()) else MemberValue(value, member)
        }
        return value
    }

    private fun readArgs(): List<ValueExpr> {
        expect('(')
        val args = mutableListOf<ValueExpr>()
        skipWs()
        if (peek() == ')') {
            pos++
            return args
        }
        while (true) {
            args.add(parseValue())
            skipWs()
            if (peek() == ',') {
                pos++
                continue
            }
            expect(')')
            return args
        }
    }

    private fun readCompareOp(): CompareOp? {
        for (op in COMPARE_OPS) {
            if (src.startsWith(op.symbol, pos)) {
                pos += op.symbol.length
                return op
            }
        }
        return null
    }

    private fun isBoundary(index: Int): Boolean {
        if (index >= src.length) return true
        val c = src[index]
        return !(c.isLetterOrDigit() || c == '_')
    }

    // ==================== 基础扫描 ====================

    private fun peek(offset: Int = 0): Char =
        if (pos + offset < src.length) src[pos + offset] else '\u0000'

    private fun skipWs(): Int {
        val start = pos
        while (pos < src.length && src[pos].isWhitespace()) pos++
        return pos - start
    }

    private fun match(token: String): Boolean {
        if (!src.startsWith(token, pos)) return false
        pos += token.length
        return true
    }

    /**
     * 跳过空白后匹配记号（失败则回退位置）。
     *
     * GKD 选择器允许逻辑运算符两侧带空白（如 `[width<500 && height<300]`、
     * `A || B`），订阅规则里普遍如此书写。
     */
    private fun matchAfterWhitespace(token: String): Boolean {
        val save = pos
        skipWs()
        if (!src.startsWith(token, pos)) {
            pos = save
            return false
        }
        pos += token.length
        return true
    }

    private fun expect(c: Char) {
        if (pos >= src.length || src[pos] != c) {
            throw IllegalArgumentException("期望字符 " + c + "，实际: " + src.substring(pos))
        }
        pos++
    }

    /** 标识符（属性名/方法名/变量名）：GKD 标识符规则为 `^[_a-zA-Z][a-zA-Z0-9_]*$`，不含 `$` */
    private fun readName(): String {
        val start = pos
        while (pos < src.length) {
            val c = src[pos]
            if (c.isLetterOrDigit() || c == '_') pos++ else break
        }
        if (start == pos) throw IllegalArgumentException("期望标识符: " + src.substring(pos))
        return src.substring(start, pos)
    }

    /** 类名（属性段开头的类名简写/全限定名）：允许 `.` 与内部类 `$` */
    private fun readTagName(): String {
        val start = pos
        while (pos < src.length) {
            val c = src[pos]
            if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '.') pos++ else break
        }
        if (start == pos) throw IllegalArgumentException("期望类名: " + src.substring(pos))
        return src.substring(start, pos)
    }

    private fun readInt(): Int {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && src[pos].isDigit()) pos++
        if (start == pos || (pos == start + 1 && src[start] == '-')) {
            throw IllegalArgumentException("非法整数: " + src.substring(start))
        }
        return src.substring(start, pos).toInt()
    }

    private fun readString(): String {
        val quote = peek()
        pos++
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            if (src[pos] == '\\') {
                pos++
                if (pos >= src.length) throw IllegalArgumentException("字符串转义未结束")
                sb.append(readEscape())
            } else {
                sb.append(src[pos])
                pos++
            }
        }
        if (pos >= src.length) throw IllegalArgumentException("字符串未闭合")
        pos++
        return sb.toString()
    }

    private fun readEscape(): String {
        val c = src[pos]
        pos++
        return when (c) {
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'b' -> "\b"
            'f' -> "\u000C"
            '0' -> "\u0000"
            'u' -> readHex(4)
            'x' -> readHex(2)
            else -> c.toString()
        }
    }

    private fun readHex(length: Int): String {
        if (pos + length > src.length) throw IllegalArgumentException("非法十六进制转义")
        val hex = src.substring(pos, pos + length)
        pos += length
        return hex.toInt(16).toChar().toString()
    }

    private companion object {
        val CHAIN_END_CHARS = setOf(')', ']', '|', '&')
        val RANGE_CHARS = setOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'n', '+', '-')
        val QUOTE_CHARS = setOf('\'', '"', '`')
        val FAST_QUERY_TEXT_OPS = setOf(
            CompareOp.EQ, CompareOp.STARTS_WITH, CompareOp.CONTAINS, CompareOp.ENDS_WITH
        )
        /** 按符号长度倒序，保证 `!~=` 先于 `~=` 匹配 */
        val COMPARE_OPS = CompareOp.entries.sortedByDescending { it.symbol.length }
    }
}
