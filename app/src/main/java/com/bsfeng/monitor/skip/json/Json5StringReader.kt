package com.bsfeng.monitor.skip.json

// 来源：原 skip/json/Json5Parser.kt 的 Json5Reader；职责：读取字符串字面量与转义（含 \uXXXX、\xXX、续行）。

/** 读取带引号字符串，支持三种引号与转义。 */
internal fun Json5Reader.readString(): String {
    val start = pos
    val quote = text[pos]
    pos++
    val builder = StringBuilder()
    while (true) {
        if (atEnd) throw IllegalArgumentException("偏移 $start 处的字符串未闭合")
        val ch = text[pos]
        when {
            ch == quote -> {
                pos++
                return builder.toString()
            }
            ch == '\n' || ch == '\r' -> throw IllegalArgumentException("偏移 $pos 处的字符串未闭合（遇到换行）")
            ch == '\\' -> {
                pos++
                builder.append(readEscape(start))
            }
            else -> {
                builder.append(ch)
                pos++
            }
        }
    }
}

/** 读取反斜杠之后的转义内容。 */
internal fun Json5Reader.readEscape(stringStart: Int): String {
    if (atEnd) throw IllegalArgumentException("偏移 $stringStart 处的字符串未闭合")
    val ch = text[pos]
    pos++
    return when (ch) {
        'n' -> "\n"
        'r' -> "\r"
        't' -> "\t"
        'b' -> "\b"
        'f' -> "\u000C"
        'v' -> "\u000B"
        '0' -> "\u0000"
        'u' -> readHexEscape(4)
        'x' -> readHexEscape(2)
        '\n' -> ""
        '\r' -> {
            if (!atEnd && text[pos] == '\n') pos++
            ""
        }
        else -> ch.toString()
    }
}

/** 读取定长十六进制转义（4 位或 2 位）。 */
internal fun Json5Reader.readHexEscape(length: Int): String {
    val start = pos
    var code = 0
    repeat(length) {
        if (atEnd || !isHexDigit(text[pos])) {
            throw IllegalArgumentException("偏移 $start 处的转义需要 $length 位十六进制数字")
        }
        code = code * 16 + hexValue(text[pos])
        pos++
    }
    return code.toChar().toString()
}
