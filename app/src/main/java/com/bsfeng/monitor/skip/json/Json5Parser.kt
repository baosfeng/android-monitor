package com.bsfeng.monitor.skip.json

import java.math.BigInteger
import kotlin.math.floor

/** JSON5 最大嵌套深度；超过该层级直接报错，避免递归过深导致栈溢出。 */
private const val MAX_JSON5_DEPTH = 200

/**
 * 纯 Kotlin 实现的 JSON5 解析器（零第三方依赖），用于解析 GKD 订阅规则文件。
 *
 * 支持的语法：
 * - 字符串：单引号、双引号、反引号三种引号，支持转义 \\ \' \" \/ \n \r \t \b \f \v \0 \uXXXX \xXX
 * - 对象键：可加引号，也可为不加引号的标识符
 * - 注释：双斜杠行注释与斜杠星号块注释
 * - 尾随逗号：对象与数组末尾均允许
 * - 数字：十进制、十六进制（0x 前缀）、科学计数法、正号前缀、NaN、Infinity
 *
 * 返回类型约定：
 * - 对象 → [LinkedHashMap]（保持键的插入顺序）
 * - 数组 → [ArrayList]
 * - 字符串 → [String]；布尔 → [Boolean]；null → null
 * - 数字按数值归一化：整数值且可用 [Int] 表示时返回 [Int]，其次 [Long]，其余返回 [Double]；
 *   因此 1.0、2e3 也返回 [Int]，而 1.5、NaN、Infinity 返回 [Double]
 *
 * 实现为单遍扫描，不使用正则做整体解析。
 */
object Json5Parser {

    /**
     * 解析 JSON5 文本。
     *
     * @param text 待解析的 JSON5 文本
     * @return 解析结果，类型约定见 [Json5Parser]
     * @throws IllegalArgumentException 语法非法时抛出，异常信息包含出错偏移
     */
    fun parse(text: String): Any? {
        val reader = Json5Reader(text)
        val value = reader.readValue(0)
        reader.skipSpace()
        if (!reader.atEnd) {
            throw IllegalArgumentException("偏移 ${reader.pos} 处存在多余内容 '${text[reader.pos]}'")
        }
        return value
    }
}

/** JSON5 单遍扫描读取器；[pos] 始终指向原始文本的偏移。 */
private class Json5Reader(private val text: String) {

    /** 当前扫描偏移。 */
    var pos = 0
        private set

    /** 是否已扫描到文本末尾。 */
    val atEnd: Boolean
        get() = pos >= text.length

    /** 跳过空白字符、行注释与块注释。 */
    fun skipSpace() {
        while (!atEnd) {
            val ch = text[pos]
            when {
                ch.isWhitespace() -> pos++
                ch == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> skipLineComment()
                ch == '/' && pos + 1 < text.length && text[pos + 1] == '*' -> skipBlockComment()
                else -> return
            }
        }
    }

    /** 跳过行注释（不消费换行符本身）。 */
    private fun skipLineComment() {
        pos += 2
        while (!atEnd && text[pos] != '\n' && text[pos] != '\r') pos++
    }

    /** 跳过块注释；未闭合时抛异常。 */
    private fun skipBlockComment() {
        val start = pos
        pos += 2
        while (pos + 1 < text.length) {
            if (text[pos] == '*' && text[pos + 1] == '/') {
                pos += 2
                return
            }
            pos++
        }
        throw IllegalArgumentException("偏移 $start 处的块注释未闭合")
    }

    /**
     * 读取一个值。
     *
     * @param depth 当前嵌套层级，超过 [MAX_JSON5_DEPTH] 时抛异常
     */
    fun readValue(depth: Int): Any? {
        skipSpace()
        if (depth > MAX_JSON5_DEPTH) {
            throw IllegalArgumentException("偏移 $pos 处嵌套层级超过上限 $MAX_JSON5_DEPTH")
        }
        if (atEnd) throw IllegalArgumentException("偏移 $pos 处意外结束，期望一个值")
        return when (text[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"', '\'', '`' -> readString()
            else -> readLiteral()
        }
    }

    /** 读取对象，返回保持插入顺序的 [LinkedHashMap]。 */
    private fun readObject(depth: Int): LinkedHashMap<String, Any?> {
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
    private fun readArray(depth: Int): ArrayList<Any?> {
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
    private fun readKey(): String {
        if (atEnd) throw IllegalArgumentException("偏移 $pos 处期望对象键，但已到文本结尾")
        return when (text[pos]) {
            '"', '\'', '`' -> readString()
            else -> readIdentifier()
        }
    }

    /** 读取未加引号的标识符作为对象键。 */
    private fun readIdentifier(): String {
        val start = pos
        while (!atEnd && isIdentifierPart(text[pos])) pos++
        if (pos == start) {
            throw IllegalArgumentException("偏移 $start 处期望对象键，实际为 '${text[start]}'")
        }
        return text.substring(start, pos)
    }

    /** 读取字面量：布尔、null、NaN、Infinity 或数字。 */
    private fun readLiteral(): Any? {
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

    /** 读取数字字面量，返回 [Int]/[Long]/[Double]。 */
    private fun readNumber(): Any {
        val start = pos
        val negative = text[pos] == '-'
        if (text[pos] == '+' || negative) pos++
        if (text.startsWith("Infinity", pos)) {
            pos += 8
            return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        if (text.startsWith("NaN", pos)) {
            pos += 3
            return Double.NaN
        }
        if (atEnd) throw IllegalArgumentException("偏移 $start 处的数字缺少数字位")
        val isHex = text[pos] == '0' && pos + 1 < text.length && (text[pos + 1] == 'x' || text[pos + 1] == 'X')
        return if (isHex) readHexNumber(start, negative) else readDecimalNumber(start)
    }

    /** 读取十六进制整数（0x 前缀）。 */
    private fun readHexNumber(start: Int, negative: Boolean): Any {
        pos += 2
        val digitsStart = pos
        while (!atEnd && isHexDigit(text[pos])) pos++
        if (pos == digitsStart) throw IllegalArgumentException("偏移 $start 处的十六进制数字缺少数字位")
        val value = BigInteger(text.substring(digitsStart, pos), 16)
        return normalizeInteger(if (negative) value.negate() else value)
    }

    /** 读取十进制数字（含小数部分与科学计数法）。 */
    private fun readDecimalNumber(start: Int): Any {
        var digits = 0
        var hasFraction = false
        while (!atEnd && isDigitChar(text[pos])) {
            pos++
            digits++
        }
        if (!atEnd && text[pos] == '.') {
            hasFraction = true
            pos++
            while (!atEnd && isDigitChar(text[pos])) {
                pos++
                digits++
            }
        }
        if (digits == 0) throw IllegalArgumentException("偏移 $start 处的数字缺少数字位")
        val hasExponent = readExponent()
        val literal = text.substring(start, pos)
        if (hasFraction || hasExponent) return normalizeDouble(literal.toDouble())
        return normalizeInteger(BigInteger(literal.removePrefix("+")))
    }

    /** 读取可选指数部分（e/E 加可选符号加数字），返回是否读取到指数。 */
    private fun readExponent(): Boolean {
        if (atEnd || (text[pos] != 'e' && text[pos] != 'E')) return false
        val start = pos
        pos++
        if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
        val digitsStart = pos
        while (!atEnd && isDigitChar(text[pos])) pos++
        if (pos == digitsStart) throw IllegalArgumentException("偏移 $start 处的指数部分缺少数字位")
        return true
    }

    /** 读取带引号字符串，支持三种引号与转义。 */
    private fun readString(): String {
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
    private fun readEscape(stringStart: Int): String {
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
    private fun readHexEscape(length: Int): String {
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

    /** 描述当前位置的字符，用于异常信息。 */
    private fun describeChar(): String = if (atEnd) "文本结尾" else "'${text[pos]}'"

    /** 整数归一化：能放入 [Int] 返回 [Int]，其次 [Long]，再其次 [Double]。 */
    private fun normalizeInteger(value: BigInteger): Any = when {
        value.bitLength() < 32 -> value.toInt()
        value.bitLength() < 64 -> value.toLong()
        else -> value.toDouble()
    }

    /** 浮点归一化：整数值且能放入 [Int]/[Long] 时降级返回整型，否则保持 [Double]。 */
    private fun normalizeDouble(value: Double): Any {
        if (value.isNaN() || value.isInfinite() || value != floor(value)) return value
        if (value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()) return value.toInt()
        if (value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) return value.toLong()
        return value
    }
}

/** 是否为 ASCII 十进制数字。 */
private fun isDigitChar(ch: Char): Boolean = ch in '0'..'9'

/** 是否为十六进制数字。 */
private fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

/** 十六进制字符对应的数值，仅对合法十六进制字符调用。 */
private fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    else -> ch - 'A' + 10
}

/** 是否为 JSON5 标识符字符（用于未加引号的对象键）。 */
private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'
