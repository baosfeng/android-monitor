package com.bsfeng.monitor.transfer.transport

import com.bsfeng.monitor.domain.transfer.API_PREFIX
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

// 从 transfer/transport/MiniHttpServer.kt 拆出：HTTP 协议报文（请求头 / 请求行 / query / 路由 / 状态码）。
// 全是纯函数，可直接对 ByteArrayInputStream 单测；socket 与分发在 MiniHttpServer。

/** 协议 §4 路由表。 */
enum class TransferRoute(val path: String) {
    INFO("$API_PREFIX" + "info"),
    OFFER("$API_PREFIX" + "offer"),
    OFFER_STATUS("$API_PREFIX" + "offer/status"),
    UPLOAD("$API_PREFIX" + "upload"),
    CANCEL("$API_PREFIX" + "cancel"),
}

/** 路由匹配（纯函数）。 */
object TransferRouter {
    /** 路径 → 路由；不在协议表内返回 null。容忍末尾多余的 `/`。 */
    fun match(path: String): TransferRoute? {
        if (path.isEmpty() || !path.startsWith("/")) return null
        val normalized = path.trimEnd('/')
        return TransferRoute.entries.firstOrNull { it.path == normalized }
    }
}

/** HTTP 状态码（只用到这几个）。 */
object HttpStatus {
    const val OK = 200
    const val BAD_REQUEST = 400
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val INTERNAL_ERROR = 500
}

/** 已解析的请求头。 */
data class HttpHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val contentLength: Long,
) {
    /** 读单个请求头（大小写不敏感）。 */
    fun header(name: String): String? = headers[name.lowercase()]

    /** 读 query 参数（缺失或空白回落 [fallback]）。 */
    fun queryParam(name: String, fallback: String): String =
        query[name]?.takeIf { it.isNotBlank() } ?: fallback
}

/** 请求行。 */
data class RequestLine(val method: String, val target: String, val version: String)

/**
 * HTTP/1.1 请求头解析（纯函数，可直接对 [java.io.ByteArrayInputStream] 单测）。
 *
 * 未定义行为：
 * - 超过 [MAX_HEAD_BYTES] 未读到空行 → null；
 * - 请求行不是「方法 目标 HTTP/1.x」→ null；
 * - `Content-Length` 非数字或为负 → null；
 * - 重复请求头取首次出现值。
 */
object HttpRequestParser {
    /** 请求头上限。 */
    const val MAX_HEAD_BYTES: Int = 16 * 1024

    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD")
    private val REQUEST_LINE_LIMIT = 8 * 1024

    /** 从输入流读一个请求头（读到空行为止），不消费请求体。 */
    fun parseHead(input: InputStream): HttpHead? {
        val headBytes = readHeadBytes(input) ?: return null
        val text = String(headBytes, Charsets.UTF_8)
        val lines = text.split("\r\n").ifEmpty { text.split("\n") }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = parseRequestLine(lines.first()) ?: return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isNotEmpty() && !headers.containsKey(name)) headers[name] = value
        }
        val contentLength = when (val raw = headers["content-length"]) {
            null -> 0L
            else -> raw.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        val target = requestLine.target
        return HttpHead(
            method = requestLine.method,
            path = target.substringBefore('?').ifEmpty { "/" },
            query = parseQuery(target),
            headers = headers,
            contentLength = contentLength,
        )
    }

    /** 解析请求行：`GET /api/v1/info HTTP/1.1`。 */
    fun parseRequestLine(line: String): RequestLine? {
        if (line.length > REQUEST_LINE_LIMIT) return null
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val method = parts[0].uppercase()
        if (method !in METHODS) return null
        if (!parts[2].startsWith("HTTP/1.")) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        return RequestLine(method, target, parts[2])
    }

    /** 解析 query 串（百分号解码，`+` 视为空格）。 */
    fun parseQuery(rawTarget: String): Map<String, String> {
        val raw = rawTarget.substringAfter('?', "")
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = decode(pair.substringBefore('='))
            if (key.isEmpty() || result.containsKey(key)) return@forEach
            result[key] = decode(pair.substringAfter('=', ""))
        }
        return result
    }

    /** 百分号解码（解码失败回落原串）。 */
    fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    /** 逐字节读到 `\r\n\r\n`，返回去掉空行前的头部字节；超限或流结束返回 null。 */
    private fun readHeadBytes(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(512)
        var state = 0
        while (buffer.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> return buffer.toByteArray().copyOf(buffer.size() - 4)
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }
}

/** 只允许读 [limit] 字节的包装流（用于把请求体与 socket 后续数据分开）。 */
class LimitedInputStream(
    private val delegate: InputStream,
    limit: Long,
) : InputStream() {
    private var remaining: Long = limit.coerceAtLeast(0L)

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val want = minOf(len.toLong(), remaining).toInt()
        val read = delegate.read(b, off, want)
        if (read > 0) remaining -= read.toLong()
        return read
    }

    /** 剩余未读字节。 */
    fun remaining(): Long = remaining
}
