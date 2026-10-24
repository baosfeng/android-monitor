package com.bsfeng.monitor.skip.json

// 来源：原 skip/json/Json5Parser.kt 的 Json5Reader；职责：数字字面量（十进制/十六进制/科学计数法）与数值归一化。

import java.math.BigInteger
import kotlin.math.floor

/** 读取数字字面量，返回 [Int]/[Long]/[Double]。 */
internal fun Json5Reader.readNumber(): Any {
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
internal fun Json5Reader.readHexNumber(start: Int, negative: Boolean): Any {
    pos += 2
    val digitsStart = pos
    while (!atEnd && isHexDigit(text[pos])) pos++
    if (pos == digitsStart) throw IllegalArgumentException("偏移 $start 处的十六进制数字缺少数字位")
    val value = BigInteger(text.substring(digitsStart, pos), 16)
    return normalizeInteger(if (negative) value.negate() else value)
}

/** 读取十进制数字（含小数部分与科学计数法）。 */
internal fun Json5Reader.readDecimalNumber(start: Int): Any {
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
internal fun Json5Reader.readExponent(): Boolean {
    if (atEnd || (text[pos] != 'e' && text[pos] != 'E')) return false
    val start = pos
    pos++
    if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
    val digitsStart = pos
    while (!atEnd && isDigitChar(text[pos])) pos++
    if (pos == digitsStart) throw IllegalArgumentException("偏移 $start 处的指数部分缺少数字位")
    return true
}

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
