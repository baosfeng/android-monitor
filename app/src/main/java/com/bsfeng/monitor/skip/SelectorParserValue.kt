package com.bsfeng.monitor.skip

// 来源：原 skip/SelectorParser.kt 的 Parser；职责：值表达式层（字面量、标识符、成员访问、函数调用与参数列表）。

import com.bsfeng.monitor.domain.model.CallValue
import com.bsfeng.monitor.domain.model.CompareOp
import com.bsfeng.monitor.domain.model.IdentValue
import com.bsfeng.monitor.domain.model.LiteralValue
import com.bsfeng.monitor.domain.model.MemberValue
import com.bsfeng.monitor.domain.model.ValueExpr

internal fun Parser.parseValue(): ValueExpr {
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

internal fun Parser.readArgs(): List<ValueExpr> {
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

internal fun Parser.readCompareOp(): CompareOp? {
    for (op in COMPARE_OPS) {
        if (src.startsWith(op.symbol, pos)) {
            pos += op.symbol.length
            return op
        }
    }
    return null
}
