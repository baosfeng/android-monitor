package com.bsfeng.monitor.transfer.transport

import com.bsfeng.monitor.domain.transfer.HTTP_CONNECT_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.HTTP_READ_TIMEOUT_MS
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * `HttpURLConnection` 封装（协议 §4 的客户端侧）。
 *
 * - 每次请求 `Connection: close`，`finally` 断开连接（不泄漏）；
 * - 上传用 `setFixedLengthStreamingMode`，边读边写，内存占用与文件大小无关；
 * - 只做一次网络尝试，重试节奏（1s/2s/4s）由调用方按 [com.bsfeng.monitor.domain.transfer.RETRY_DELAYS_MS] 控制。
 */
class MiniHttpClient(
    private val connectTimeoutMs: Int = HTTP_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = HTTP_READ_TIMEOUT_MS,
) {

    /** 原始响应。 */
    data class HttpResponse(
        val httpCode: Int,
        val body: String,
    ) {
        /** 解析后的业务响应；报文非法时为 null。 */
        val api: ApiResponse? get() = ApiResponse.parse(body)
    }

    /** `GET`。 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url, "GET", headers)
            readResponse(conn)
        } finally {
            conn?.disconnect()
        }
    }

    /** `POST` JSON。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url, "POST", headers + ("Content-Type" to "application/json; charset=utf-8"))
            conn.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            readResponse(conn)
        } finally {
            conn?.disconnect()
        }
    }

    /** `POST` 空体（`/cancel` 用，会话参数走 query）。 */
    fun postEmpty(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url, "POST", headers)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
            readResponse(conn)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * `PUT` 裸字节流（协议 §4：请求体是从 `offset` 起的裸字节，不包 multipart）。
     *
     * @param contentLength 本次要发的字节数（= 文件大小 - offset）。
     * @param onProgress 已发送字节回调。
     */
    fun putStream(
        url: String,
        input: InputStream,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        onProgress: (sent: Long) -> Unit = {},
    ): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(
                url,
                "PUT",
                headers + ("Content-Type" to "application/octet-stream"),
            )
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(contentLength)
            val buffer = ByteArray(BUFFER_SIZE)
            var sent = 0L
            conn.outputStream.use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
                output.flush()
            }
            readResponse(conn)
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): HttpResponse {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { input ->
            val buffer = java.io.ByteArrayOutputStream(input.available().coerceAtLeast(64))
            val chunk = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        } ?: ""
        return HttpResponse(code, body)
    }

    companion object {
        /** 上传缓冲（与落盘一致：64 KiB）。 */
        const val BUFFER_SIZE: Int = 64 * 1024

        /** query 参数编码（`session` / `file` / `offset`）。 */
        fun query(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }

        /** 拼出 `http://ip:port/api/v1/<route>?query`。 */
        fun buildUrl(baseUrl: String, route: String, params: List<Pair<String, String>> = emptyList()): String {
            val prefix = baseUrl.trimEnd('/') + "/api/v1/" + route.trimStart('/')
            return if (params.isEmpty()) prefix else prefix + "?" + query(params)
        }

        /** 把文本写进 URL 输出流（供调试 / 测试复用）。 */
        internal fun writeText(conn: HttpURLConnection, text: String) {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(text) }
        }
    }
}
