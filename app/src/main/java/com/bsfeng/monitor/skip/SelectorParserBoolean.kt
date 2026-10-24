package com.bsfeng.monitor.skip

// 来源：原 skip/SelectorParser.kt 的 Parser；职责：`[...]` 内的布尔表达式层（|| / && / ! 与比较表达式）。

import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.BoolExpr
import com.bsfeng.monitor.domain.model.CompareExpr
import com.bsfeng.monitor.domain.model.NotExpr
import com.bsfeng.monitor.domain.model.OrExpr

internal fun Parser.parseBoolOr(): BoolExpr {
    val items = mutableListOf(parseBoolAnd())
    while (matchAfterWhitespace("||")) items.add(parseBoolAnd())
    return if (items.size == 1) items[0] else OrExpr(items)
}

internal fun Parser.parseBoolAnd(): BoolExpr {
    val items = mutableListOf(parseBoolUnary())
    while (matchAfterWhitespace("&&")) items.add(parseBoolUnary())
    return if (items.size == 1) items[0] else AndExpr(items)
}

internal fun Parser.parseBoolUnary(): BoolExpr {
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

private fun Parser.parseCompare(): BoolExpr {
    val left = parseValue()
    skipWs()
    val op = readCompareOp() ?: throw IllegalArgumentException("缺少比较操作符: " + src.substring(pos))
    skipWs()
    val right = parseValue()
    return CompareExpr(left, op, right)
}
