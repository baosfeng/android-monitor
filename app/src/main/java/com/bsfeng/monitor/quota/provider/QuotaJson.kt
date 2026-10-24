package com.bsfeng.monitor.quota.provider

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 极简 JSON 值树 + 取值扩展。
 *
 * 为什么不用 `org.json`：AGP 的 mockable android.jar 会把 `org.json` 打桩，普通 JVM 单测调用即抛
 * `Method ... not mocked`（实测），而这层解析必须能在 JVM 单测里跑到真实代码。本文件零依赖，
 * 只做「读」不做「写」，覆盖额度接口用到的 JSON 子集（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。
 *
 * 容错口径：类型不符或字段缺失一律返回 null（调用方按默认值兜底），不抛异常。
 */
sealed interface JsonNode {
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode
    data class Arr(val items: List<JsonNode>) : JsonNode
    data class Str(val value: String) : JsonNode
    data class Num(val value: BigDecimal) : JsonNode
    data class Bool(val value: Boolean) : JsonNode
    data object Null : JsonNode
}

/** 解析 JSON 文本；非法输入返回 null。 */
object QuotaJson {
    fun parse(text: String): JsonNode? = try {
        JsonParser(text).parseDocument()
    } catch (_: MalformedJson) {
        null
    }

    /** 把任意文本压成单行并截断，用于错误提示（避免 HTML 墙 / 多行堆栈刷屏）。 */
    fun oneLine(text: String?, limit: Int = 160): String? {
        val flat = text?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return flat.take(limit).ifEmpty { null }
    }

    private val WHITESPACE = Regex("\\s+")
}

// ----------------------------- 取值扩展 -----------------------------

internal fun JsonNode?.field(name: String): JsonNode? = (this as? JsonNode.Obj)?.fields?.get(name)

internal fun JsonNode?.obj(name: String): JsonNode.Obj? = field(name) as? JsonNode.Obj

internal fun JsonNode?.items(name: String): List<JsonNode> =
    (field(name) as? JsonNode.Arr)?.items.orEmpty()

/** 字符串字段；数字按十进制原样转字符串（DeepSeek 金额/时间有字符串也有数字两种形态）。 */
internal fun JsonNode?.str(name: String): String? = when (val value = field(name)) {
    is JsonNode.Str -> value.value
    is JsonNode.Num -> value.value.toPlainString()
    is JsonNode.Bool -> value.value.toString()
    else -> null
}

/** 金额 / 小数：接受数字与「数字字符串」（DeepSeek 金额是字符串，不能用 Double 累加）。 */
internal fun JsonNode?.dec(name: String): BigDecimal? = field(name).asDecimal()

internal fun JsonNode?.asDecimal(): BigDecimal? = when (this) {
    is JsonNode.Num -> value
    is JsonNode.Str -> runCatching { value.trim().toBigDecimal() }.getOrNull()
    else -> null
}

internal fun JsonNode?.long(name: String): Long? = dec(name)?.toLongSafely()

internal fun JsonNode?.int(name: String): Int? = long(name)?.let { value ->
    if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) null else value.toInt()
}

internal fun JsonNode?.bool(name: String): Boolean? = when (val value = field(name)) {
    is JsonNode.Bool -> value.value
    is JsonNode.Str -> when (value.value.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

/** 时间字段 → epoch 秒（UTC）：支持数字（秒或毫秒）与 ISO-8601 字符串。 */
internal fun JsonNode?.epochSecond(name: String): Long? = field(name).epochSecondOrNull()

internal fun JsonNode?.epochSecondOrNull(): Long? = when (this) {
    is JsonNode.Num -> normalizeEpochSecond(value)
    is JsonNode.Str -> {
        val text = value.trim()
        runCatching { text.toLong() }.getOrNull()?.let { normalizeEpochSecond(BigDecimal(it)) }
            ?: parseIsoSecond(text)
    }
    else -> null
}

private fun normalizeEpochSecond(raw: BigDecimal): Long? {
    val millis = raw >= MILLIS_THRESHOLD || raw <= MILLIS_THRESHOLD.negate()
    val seconds = if (millis) raw.divide(THOUSAND) else raw
    return runCatching { seconds.toLong() }.getOrNull()
}

private fun parseIsoSecond(text: String): Long? {
    if (text.isEmpty()) return null
    runCatching { Instant.parse(text).epochSecond }.getOrNull()?.let { return it }
    return runCatching { OffsetDateTime.parse(text).toInstant().epochSecond }.getOrNull()
}

private fun BigDecimal.toLongSafely(): Long? = runCatching { toLong() }.getOrNull()

private val MILLIS_THRESHOLD: BigDecimal = BigDecimal.valueOf(100_000_000_000L)
private val THOUSAND: BigDecimal = BigDecimal.valueOf(1000L)

// ----------------------------- 解析器 -----------------------------

private class MalformedJson : Exception()

private class JsonParser(private val text: String) {
    private var position = 0

    fun parseDocument(): JsonNode {
        val value = parseValue()
        skipWhitespace()
        if (position != text.length) throw MalformedJson()
        return value
    }

    private fun parseValue(): JsonNode {
        skipWhitespace()
        if (position >= text.length) throw MalformedJson()
        return when (val c = text[position]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseString())
            't' -> literal("true", JsonNode.Bool(true))
            'f' -> literal("false", JsonNode.Bool(false))
            'n' -> literal("null", JsonNode.Null)
            else -> if (c == '-' || c.isDigit()) parseNumber() else throw MalformedJson()
        }
    }

    private fun parseObject(): JsonNode.Obj {
        position++ // '{'
        val fields = LinkedHashMap<String, JsonNode>()
        skipWhitespace()
        if (consume('}')) return JsonNode.Obj(fields)
        while (true) {
            skipWhitespace()
            if (position >= text.length || text[position] != '"') throw MalformedJson()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return JsonNode.Obj(fields)
                else -> throw MalformedJson()
            }
        }
    }

    private fun parseArray(): JsonNode.Arr {
        position++ // '['
        val items = mutableListOf<JsonNode>()
        skipWhitespace()
        if (consume(']')) return JsonNode.Arr(items)
        while (true) {
            items += parseValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> return JsonNode.Arr(items)
                else -> throw MalformedJson()
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (position >= text.length) throw MalformedJson()
            when (val c = text[position++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(c)
            }
        }
    }

    private fun parseEscape(): Char {
        if (position >= text.length) throw MalformedJson()
        return when (val escape = text[position++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw MalformedJson()
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (position + 4 > text.length) throw MalformedJson()
        val hex = text.substring(position, position + 4)
        position += 4
        val code = hex.toIntOrNull(16) ?: throw MalformedJson()
        return code.toChar()
    }

    private fun parseNumber(): JsonNode.Num {
        val start = position
        if (position < text.length && text[position] == '-') position++
        while (position < text.length && (text[position].isDigit() || text[position] in NUMBER_MARKS)) {
            position++
        }
        val raw = text.substring(start, position)
        val value = runCatching { raw.toBigDecimal() }.getOrNull() ?: throw MalformedJson()
        return JsonNode.Num(value)
    }

    private fun literal(word: String, node: JsonNode): JsonNode {
        if (!text.startsWith(word, position)) throw MalformedJson()
        position += word.length
        return node
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private fun consume(expected: Char): Boolean {
        if (position < text.length && text[position] == expected) {
            position++
            return true
        }
        return false
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) throw MalformedJson()
    }

    private companion object {
        const val NUMBER_MARKS = "+.eE"
    }
}
