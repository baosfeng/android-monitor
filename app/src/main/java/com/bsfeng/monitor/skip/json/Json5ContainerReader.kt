package com.bsfeng.monitor.skip.json

// 来源：原 skip/json/Json5Parser.kt 的 Json5Reader；职责：读取对象/数组容器与字面量（布尔、null、NaN、Infinity）。

/** 读取对象，返回保持插入顺序的 [LinkedHashMap]。 */
internal fun Json5Reader.readObject(depth: Int): LinkedHashMap<String, Any?> {
    val start = pos
    pos++
    val result = LinkedHashMap<String, Any?>()
    skipSpace()
    if (!atEnd && text[pos] == '}') {
        pos++
        return result
    }
    while (true) {
        skipSpace()
        val key = readKey()
        skipSpace()
        if (atEnd || text[pos] != ':') {
            throw IllegalArgumentException("偏移 $pos 处期望 ':'，实际为 ${describeChar()}")
        }
        pos++
        result[key] = readValue(depth + 1)
        skipSpace()
        if (atEnd) throw IllegalArgumentException("偏移 $start 处的对象未闭合")
        when (text[pos]) {
            ',' -> {
                pos++
                skipSpace()
                if (!atEnd && text[pos] == '}') {
                    pos++
                    return result
                }
            }
            '}' -> {
                pos++
                return result
            }
            else -> throw IllegalArgumentException("偏移 $pos 处期望 ',' 或 '}'，实际为 '${text[pos]}'")
        }
    }
}

/** 读取数组，返回 [ArrayList]。 */
internal fun Json5Reader.readArray(depth: Int): ArrayList<Any?> {
    val start = pos
    pos++
    val result = ArrayList<Any?>()
    skipSpace()
    if (!atEnd && text[pos] == ']') {
        pos++
        return result
    }
    while (true) {
        result.add(readValue(depth + 1))
        skipSpace()
        if (atEnd) throw IllegalArgumentException("偏移 $start 处的数组未闭合")
        when (text[pos]) {
            ',' -> {
                pos++
                skipSpace()
                if (!atEnd && text[pos] == ']') {
                    pos++
                    return result
                }
            }
            ']' -> {
                pos++
                return result
            }
            else -> throw IllegalArgumentException("偏移 $pos 处期望 ',' 或 ']'，实际为 '${text[pos]}'")
        }
    }
}

/** 读取对象键：带引号字符串或未加引号的标识符。 */
internal fun Json5Reader.readKey(): String {
    if (atEnd) throw IllegalArgumentException("偏移 $pos 处期望对象键，但已到文本结尾")
    return when (text[pos]) {
        '"', '\'', '`' -> readString()
        else -> readIdentifier()
    }
}

/** 读取未加引号的标识符作为对象键。 */
internal fun Json5Reader.readIdentifier(): String {
    val start = pos
    while (!atEnd && isIdentifierPart(text[pos])) pos++
    if (pos == start) {
        throw IllegalArgumentException("偏移 $start 处期望对象键，实际为 '${text[start]}'")
    }
    return text.substring(start, pos)
}

/** 读取字面量：布尔、null、NaN、Infinity 或数字。 */
internal fun Json5Reader.readLiteral(): Any? {
    val start = pos
    val ch = text[pos]
    if (ch == '+' || ch == '-' || ch == '.' || isDigitChar(ch)) return readNumber()
    while (!atEnd && isIdentifierPart(text[pos])) pos++
    val word = text.substring(start, pos)
    return when (word) {
        "true" -> true
        "false" -> false
        "null" -> null
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "" -> throw IllegalArgumentException("偏移 $start 处存在非法字符 '$ch'")
        else -> throw IllegalArgumentException("偏移 $start 处无法识别的字面量 '$word'")
    }
}
