package com.bsfeng.monitor.network

import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.util.AppUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 从 network/ApiService.kt 拆出：HttpURLConnection 的收发与资源释放。
// 负责「怎么发一个请求并读回 JSON」，不含任何业务语义与 DTO 解析。

/**
 * 系统内置 [HttpURLConnection] 的薄封装：发起请求、读回响应体、保证连接断开。
 *
 * 异常路径也走 `finally { disconnect() }`，避免 socket 泄漏；
 * 非 2xx 时从 errorStream 读，避免 FileNotFoundException。
 */
internal object ApiHttpTransport {
    private const val TIMEOUT_MS = 10_000

    fun post(path: String, body: JSONObject): Result<ApiService.JsonResponse> = request("POST", path) { conn ->
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.writer().use { writer ->
            writer.write(body.toString())
            writer.flush()
        }
    }

    fun get(path: String): Result<ApiService.JsonResponse> = request("GET", path)

    fun delete(path: String): Result<ApiService.JsonResponse> = request("DELETE", path)

    private inline fun request(
        method: String,
        path: String,
        configure: (HttpURLConnection) -> Unit = {},
    ): Result<ApiService.JsonResponse> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${AppConfig.serverBaseUrl.trimEnd('/')}/$path")
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                AppUtils.getToken()?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
                configure(this)
            }
            readResponse(conn)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 异常路径也必须断开连接，防止 socket 泄漏
            conn?.disconnect()
        }
    }

    /** 读响应体：2xx 走 inputStream，其余走 errorStream；空体按失败处理。 */
    private fun readResponse(conn: HttpURLConnection): Result<ApiService.JsonResponse> {
        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            // 非 2xx 响应: 从 errorStream 读取，避免 FileNotFoundException
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
        }
        if (responseBody.isBlank()) {
            return Result.failure(Exception("HTTP $responseCode (empty body)"))
        }
        val json = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            JSONObject().apply {
                put("code", -1)
                put("message", "Non-JSON response (HTTP $responseCode): ${responseBody.take(200)}")
            }
        }
        return Result.success(ApiService.JsonResponse(responseCode, json))
    }
}
