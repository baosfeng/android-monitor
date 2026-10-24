package com.bsfeng.monitor.skip

// 来源：原 skip/SelectorParser.kt 的 Parser；职责：基础扫描原语（peek/空白/记号匹配/标识符与数字字符串读取）与字符集合常量。

import com.bsfeng.monitor.domain.model.CompareOp

internal fun Parser.peek(offset: Int = 0): Char =
    if (pos + offset < src.length) src[pos + offset] else '\u0000'

internal fun Parser.skipWs(): Int {
    val start = pos
    while (pos < src.length && src[pos].isWhitespace()) pos++
    return pos - start
}

/**
 * 跳过空白后匹配记号（失败则回退位置）。
 *
 * GKD 选择器允许逻辑运算符两侧带空白（如 `[width<500 && height<300]`、
 * `A || B`），订阅规则里普遍如此书写。
 */
internal fun Parser.matchAfterWhitespace(token: String): Boolean {
    val save = pos
    skipWs()
    if (!src.startsWith(token, pos)) {
        pos = save
        return false
    }
    pos += token.length
    return true
}

internal fun Parser.expect(c: Char) {
    if (pos >= src.length || src[pos] != c) {
        throw IllegalArgumentException("期望字符 " + c + "，实际: " + src.substring(pos))
    }
    pos++
}

/** 标识符（属性名/方法名/变量名）：GKD 标识符规则为 `^[_a-zA-Z][a-zA-Z0-9_]*$`，不含 `$` */
internal fun Parser.readName(): String {
    val start = pos
    while (pos < src.length) {
        val c = src[pos]
        if (c.isLetterOrDigit() || c == '_') pos++ else break
    }
    if (start == pos) throw IllegalArgumentException("期望标识符: " + src.substring(pos))
    return src.substring(start, pos)
}

/** 类名（属性段开头的类名简写/全限定名）：允许 `.` 与内部类 `$` */
internal fun Parser.readTagName(): String {
    val start = pos
    while (pos < src.length) {
        val c = src[pos]
        if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '.') pos++ else break
    }
    if (start == pos) throw IllegalArgumentException("期望类名: " + src.substring(pos))
    return src.substring(start, pos)
}

internal fun Parser.readInt(): Int {
    val start = pos
    if (peek() == '-') pos++
    while (pos < src.length && src[pos].isDigit()) pos++
    if (start == pos || (pos == start + 1 && src[start] == '-')) {
        throw IllegalArgumentException("非法整数: " + src.substring(start))
    }
    return src.substring(start, pos).toInt()
}

internal fun Parser.readString(): String {
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

private fun Parser.readEscape(): String {
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

private fun Parser.readHex(length: Int): String {
    if (pos + length > src.length) throw IllegalArgumentException("非法十六进制转义")
    val hex = src.substring(pos, pos + length)
    pos += length
    return hex.toInt(16).toChar().toString()
}

internal fun Parser.isBoundary(index: Int): Boolean {
    if (index >= src.length) return true
    val c = src[index]
    return !(c.isLetterOrDigit() || c == '_')
}

internal val CHAIN_END_CHARS = setOf(')', ']', '|', '&')
internal val RANGE_CHARS = setOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'n', '+', '-')
internal val QUOTE_CHARS = setOf('\'', '"', '`')
internal val FAST_QUERY_TEXT_OPS = setOf(
    CompareOp.EQ, CompareOp.STARTS_WITH, CompareOp.CONTAINS, CompareOp.ENDS_WITH
)

/** 按符号长度倒序，保证 `!~=` 先于 `~=` 匹配 */
internal val COMPARE_OPS = CompareOp.entries.sortedByDescending { it.symbol.length }
