package com.bsfeng.monitor.skip.json

// 来源：原 skip/json/Json5Parser.kt；职责：JSON5 单遍扫描的光标状态与空白/注释跳过、值分派。

/** JSON5 最大嵌套深度；超过该层级直接报错，避免递归过深导致栈溢出。 */
private const val MAX_JSON5_DEPTH = 200

/** JSON5 单遍扫描读取器；[pos] 始终指向原始文本的偏移。 */
internal class Json5Reader(internal val text: String) {

    /** 当前扫描偏移。 */
    var pos = 0
        internal set

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

    /** 描述当前位置的字符，用于异常信息。 */
    fun describeChar(): String = if (atEnd) "文本结尾" else "'${text[pos]}'"
}

/** 是否为 ASCII 十进制数字。 */
internal fun isDigitChar(ch: Char): Boolean = ch in '0'..'9'

/** 是否为十六进制数字。 */
internal fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

/** 十六进制字符对应的数值，仅对合法十六进制字符调用。 */
internal fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    else -> ch - 'A' + 10
}

/** 是否为 JSON5 标识符字符（用于未加引号的对象键）。 */
internal fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'
