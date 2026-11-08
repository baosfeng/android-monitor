package com.bsfeng.monitor.transfer.transport

// HTTP 服务器本体：端口绑定、accept 循环、路由分发、响应写回。
// 报文解析与路由表在 HttpProtocol.kt（纯函数，可单测）。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.HTTP_PORT_BASE
import com.bsfeng.monitor.domain.transfer.HTTP_PORT_MAX
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.transfer.policy.ConnectionBudget
import com.bsfeng.monitor.transfer.policy.InboundLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 路由处理口径（由 `TransferEngine` 落地，服务器只负责解析与分发）。 */
interface TransferRoutes {
    /** `GET /info`：设备信息与能力。 */
    suspend fun info(): ApiResponse

    /**
     * `POST /offer`：提交文件清单，建立会话。
     *
     * @param source 来源标识（来源 IP，取不到时为空串）：接收侧按它做每来源冷却窗（SEC-04）。
     */
    suspend fun offer(body: JSONObject, pairKey: String?, source: String = ""): ApiResponse

    /** `GET /offer/status?session=`：查询会话确认状态（需 `X-Transfer-Token`，与 `/upload` 同口径）。 */
    suspend fun offerStatus(sessionId: String, token: String?): ApiResponse

    /** `PUT /upload?session=&file=&offset=`：单文件裸字节流上传。 */
    suspend fun upload(
        sessionId: String,
        fileId: String,
        offset: Long,
        token: String?,
        body: InputStream,
        contentLength: Long,
    ): ApiResponse

    /**
     * `POST /cancel?session=&sender=`：中止会话。
     *
     * 鉴权：`X-Transfer-Token` 必须等于本次会话 token，且 `sender` 必须等于
     * `session.senderDeviceId` —— 否则明文 HTTP 下嗅到会话 id 的人能删光 `.part`、把任务打成取消态。
     */
    suspend fun cancel(sessionId: String, token: String?, senderDeviceId: String?): ApiResponse
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
    /** 当前服务中的连接数（并发上限见 [InboundLimits.MAX_CONCURRENT_CONNECTIONS]）。 */
    private val activeConnections = AtomicInteger(0)

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
                // 并发连接上限（SEC-04）：超限直接关 socket，不读请求 —— 读一次报文就等于
                // 让对方占用一条连接与一次解析，几十条并发就能把 IO 线程与任务表打爆。
                if (!ConnectionBudget.admit(activeConnections.get())) {
                    Log.w(TAG, "并发连接已达上限 ${InboundLimits.MAX_CONCURRENT_CONNECTIONS}，拒绝新连接")
                    runCatching { socket.close() }
                    continue
                }
                activeConnections.incrementAndGet()
                scope.launch {
                    try {
                        serve(socket)
                    } finally {
                        activeConnections.decrementAndGet()
                    }
                }
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
            val response = handle(head, body, sourceOf(socket))
            writeResponse(output, response.first, response.second)
        } catch (e: IOException) {
            Log.w(TAG, "连接处理失败: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 连接来源标识（来源 IP）。
     *
     * 取不到地址时回落空串 —— 限流器把空串当成「同一个来源」，宁可收紧也不放行。
     */
    private fun sourceOf(socket: Socket): String =
        runCatching { socket.inetAddress?.hostAddress.orEmpty() }.getOrDefault("")

    /**
     * 路由分发（[head] 已解析，[body] 只含 `Content-Length` 内的字节）。
     *
     * @return HTTP 状态码 + 响应包。
     */
    suspend fun handle(head: HttpHead, body: InputStream, source: String = ""): Pair<Int, ApiResponse> {
        val route = TransferRouter.match(head.path)
            ?: return HttpStatus.NOT_FOUND to ApiResponse.error(NOT_FOUND_CODE, "未知路由：${head.path}")
        return when (route) {
            TransferRoute.INFO -> onlyMethod(head, "GET") ?: (HttpStatus.OK to routes.info())
            TransferRoute.OFFER -> onlyMethod(head, "POST") ?: (
                HttpStatus.OK to routes.offer(
                    readJson(body, head.contentLength) ?: return INVALID_JSON,
                    head.header(HEADER_PAIR_KEY),
                    source,
                )
                )
            TransferRoute.OFFER_STATUS -> onlyMethod(head, "GET") ?: (
                HttpStatus.OK to routes.offerStatus(
                    sessionId = head.queryParam(QUERY_SESSION, ""),
                    token = head.header(HEADER_TOKEN),
                )
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
                HttpStatus.OK to routes.cancel(
                    sessionId = head.queryParam(QUERY_SESSION, ""),
                    token = head.header(HEADER_TOKEN),
                    senderDeviceId = head.queryParam(QUERY_SENDER, ""),
                )
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

        /** `/cancel` 的来源设备 id（协议 §4：与 token 一起证明「这是本次会话的发送方」）。 */
        const val QUERY_SENDER = "sender"

        /** 请求体上限（清单报文，非文件字节）。 */
        const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

        private const val SOCKET_TIMEOUT_MS = 30_000

        /** 协议层错误（非 §4.1 业务码）：未知路由 / 方法不支持。 */
        private const val NOT_FOUND_CODE = -1

        private val INVALID_REQUEST = ApiResponse.error(NOT_FOUND_CODE, "请求报文非法")
        private val INVALID_JSON = HttpStatus.BAD_REQUEST to ApiResponse.error(NOT_FOUND_CODE, "请求体不是合法 JSON")
    }
}