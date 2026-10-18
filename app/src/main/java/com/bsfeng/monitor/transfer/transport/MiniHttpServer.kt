package com.bsfeng.monitor.transfer.transport

import android.util.Log
import com.bsfeng.monitor.domain.transfer.API_PREFIX
import com.bsfeng.monitor.domain.transfer.HTTP_PORT_BASE
import com.bsfeng.monitor.domain.transfer.HTTP_PORT_MAX
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/** 路由处理契约（由 `TransferEngine` 落地，服务器只负责解析与分发）。 */
interface TransferRoutes {
    /** `GET /info`：设备信息与能力。 */
    suspend fun info(): ApiResponse

    /** `POST /offer`：提交文件清单，建立会话。 */
    suspend fun offer(body: JSONObject, pairKey: String?): ApiResponse

    /** `GET /offer/status?session=`：查询会话确认状态。 */
    suspend fun offerStatus(sessionId: String): ApiResponse

    /** `PUT /upload?session=&file=&offset=`：单文件裸字节流上传。 */
    suspend fun upload(
        sessionId: String,
        fileId: String,
        offset: Long,
        token: String?,
        body: InputStream,
        contentLength: Long,
    ): ApiResponse

    /** `POST /cancel?session=`：中止会话。 */
    suspend fun cancel(sessionId: String): ApiResponse
}

/**
 * 极简 HTTP/1.1 服务器（协议 §4）。
 *
 * - 只实现本协议需要的 GET / POST / PUT，请求体按 `Content-Length` 读取，响应 `Connection: close`；
 * - 端口在 [portRange] 内顺延，实际端口通过 [bind] 返回并由发现公告上报（协议 §2）；
 * - 解析与路由是纯函数（[HttpRequestParser] / [TransferRouter]），不依赖 socket，可直接单测。
 */
class MiniHttpServer(
    private val routes: TransferRoutes,
    private val portRange: IntRange = HTTP_PORT_BASE..HTTP_PORT_MAX,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** 实际绑定端口；未启动返回 -1。 */
    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * 启动监听（幂等）。
     *
     * @return 实际端口。
     * @throws IOException [portRange] 内所有端口都被占用时抛出。
     */
    fun start(scope: CoroutineScope): Int {
        if (running.get()) return port
        val bound = bind() ?: throw IOException("端口 ${portRange.first}~${portRange.last} 全部被占用")
        serverSocket = bound
        running.set(true)
        acceptJob = scope.launch { acceptLoop(bound, scope) }
        return bound.localPort
    }

    /** 停止监听（关闭监听 socket 打断 accept）。 */
    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 当前是否在监听。 */
    fun isRunning(): Boolean = running.get()

    private fun bind(): ServerSocket? {
        for (p in portRange) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (e: IOException) {
                Log.w(TAG, "端口 $p 不可用: ${e.message}")
            }
        }
        return null
    }

    private suspend fun acceptLoop(server: ServerSocket, scope: CoroutineScope) =
        withContext(Dispatchers.IO) {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    if (running.get()) Log.w(TAG, "accept 失败: ${e.message}")
                    break
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept 失败: ${e.message}")
                    continue
                }
                scope.launch { serve(socket) }
            }
        }

    private suspend fun serve(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val head = HttpRequestParser.parseHead(input)
            if (head == null) {
                writeResponse(output, HttpStatus.BAD_REQUEST, INVALID_REQUEST)
                return
            }
            val body = LimitedInputStream(input, head.contentLength)
            val response = handle(head, body)
            writeResponse(output, response.first, response.second)
        } catch (e: IOException) {
            Log.w(TAG, "连接处理失败: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 路由分发（[head] 已解析，[body] 只含 `Content-Length` 内的字节）。
     *
     * @return HTTP 状态码 + 响应包。
     */
    suspend fun handle(head: HttpHead, body: InputStream): Pair<Int, ApiResponse> {
        val route = TransferRouter.match(head.path)
            ?: return HttpStatus.NOT_FOUND to ApiResponse.error(NOT_FOUND_CODE, "未知路由：${head.path}")
        return when (route) {
            TransferRoute.INFO -> onlyMethod(head, "GET") ?: (HttpStatus.OK to routes.info())
            TransferRoute.OFFER -> onlyMethod(head, "POST") ?: (
                HttpStatus.OK to routes.offer(readJson(body, head.contentLength) ?: return INVALID_JSON, head.header(HEADER_PAIR_KEY))
                )
            TransferRoute.OFFER_STATUS -> onlyMethod(head, "GET") ?: (
                HttpStatus.OK to routes.offerStatus(head.queryParam(QUERY_SESSION, ""))
                )
            TransferRoute.UPLOAD -> onlyMethod(head, "PUT") ?: (
                HttpStatus.OK to routes.upload(
                    sessionId = head.queryParam(QUERY_SESSION, ""),
                    fileId = head.queryParam(QUERY_FILE, ""),
                    offset = head.queryParam(QUERY_OFFSET, "0").toLongOrNull()
                        ?: return HttpStatus.BAD_REQUEST to ApiResponse.error(
                            TransferErrorCode.OFFSET_MISMATCH,
                            "offset 参数非法",
                        ),
                    token = head.header(HEADER_TOKEN),
                    body = body,
                    contentLength = head.contentLength,
                )
                )
            TransferRoute.CANCEL -> onlyMethod(head, "POST") ?: (
                HttpStatus.OK to routes.cancel(head.queryParam(QUERY_SESSION, ""))
                )
        }
    }

    /** 方法不匹配时返回 405，否则 null（表示可继续处理）。 */
    private fun onlyMethod(head: HttpHead, expected: String): Pair<Int, ApiResponse>? =
        if (head.method == expected) null
        else HttpStatus.METHOD_NOT_ALLOWED to ApiResponse.error(NOT_FOUND_CODE, "仅支持 $expected")

    private fun readJson(body: InputStream, contentLength: Long): JSONObject? {
        if (contentLength <= 0L || contentLength > MAX_BODY_BYTES) return null
        val bytes = ByteArray(contentLength.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = body.read(bytes, read, bytes.size - read)
            if (n <= 0) return null
            read += n
        }
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            null
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, response: ApiResponse) {
        val payload = response.toJsonString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        HttpStatus.OK -> "OK"
        HttpStatus.BAD_REQUEST -> "Bad Request"
        HttpStatus.NOT_FOUND -> "Not Found"
        HttpStatus.METHOD_NOT_ALLOWED -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    companion object {
        const val TAG = "TransferHttp"
        const val HEADER_TOKEN = "X-Transfer-Token"
        const val HEADER_PAIR_KEY = "X-Pair-Key"
        const val QUERY_SESSION = "session"
        const val QUERY_FILE = "file"
        const val QUERY_OFFSET = "offset"

        /** 请求体上限（清单报文，非文件字节）。 */
        const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

        private const val SOCKET_TIMEOUT_MS = 30_000

        /** 协议层错误（非 §4.1 业务码）：未知路由 / 方法不支持。 */
        private const val NOT_FOUND_CODE = -1

        private val INVALID_REQUEST = ApiResponse.error(NOT_FOUND_CODE, "请求报文非法")
        private val INVALID_JSON = HttpStatus.BAD_REQUEST to ApiResponse.error(NOT_FOUND_CODE, "请求体不是合法 JSON")
    }
}

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
 * HTTP/1.1 请求头解析（纯函数，可直接对 [ByteArrayInputStream] 单测）。
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
