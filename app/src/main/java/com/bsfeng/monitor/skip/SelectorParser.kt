package com.bsfeng.monitor.skip

// 来源：原 skip/SelectorParser.kt；职责：选择器解析入口与「链式选择器 + 逻辑组合 + 关系算子」层。

import com.bsfeng.monitor.domain.model.Relation
import com.bsfeng.monitor.domain.model.RelationOp
import com.bsfeng.monitor.domain.model.RelationRange
import com.bsfeng.monitor.domain.model.SelectorAnd
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorExpr
import com.bsfeng.monitor.domain.model.SelectorNot
import com.bsfeng.monitor.domain.model.SelectorOr
import com.bsfeng.monitor.domain.model.SelectorPart

/**
 * 选择器解析器（GKD 选择器子集）。
 *
 * 语法见 [SelectorExpr] 的文档。解析失败抛 [IllegalArgumentException]，
 * 调用方应使用 [tryParse] 做容错（单条规则解析失败不应影响其它规则）。
 *
 * 实现按语法层分文件：本文件是入口与链式/逻辑层，属性段见 `SelectorParserAttribute.kt`，
 * 布尔表达式层见 `SelectorParserBoolean.kt`，值表达式层见 `SelectorParserValue.kt`，
 * 基础扫描与字符集合见 `SelectorParserScanner.kt`。
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
internal class Parser(internal val src: String) {

    internal var pos = 0

    fun parseAll(): SelectorExpr {
        if (src.isBlank()) throw IllegalArgumentException("选择器不能为空")
        val expr = parseSelectorOr()
        skipWs()
        if (pos != src.length) throw IllegalArgumentException("选择器存在多余内容: " + src.substring(pos))
        return expr
    }

    // ==================== 选择器层（链式 + 逻辑） ====================

    internal fun parseSelectorOr(): SelectorExpr {
        val items = mutableListOf(parseSelectorAnd())
        while (matchAfterWhitespace("||")) items.add(parseSelectorAnd())
        return if (items.size == 1) items[0] else SelectorOr(items)
    }

    internal fun parseSelectorAnd(): SelectorExpr {
        val items = mutableListOf(parseSelectorUnary())
        while (matchAfterWhitespace("&&")) items.add(parseSelectorUnary())
        return if (items.size == 1) items[0] else SelectorAnd(items)
    }

    internal fun parseSelectorUnary(): SelectorExpr {
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
}
