package com.bsfeng.monitor.network

import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.util.AppUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 从 network/ApiService.kt 拆出：HTTP 请求的动作入口（上报 / 注册 / 心跳 / 连通性探测）。
// 负责「发哪个请求」，收发细节交给 ApiHttpTransport，解析交给 ApiResponseParser。

/**
 * 使用系统内置 HttpURLConnection 的网络层 — 替换 Retrofit/OkHttp
 */
object ApiService {
    /** HTTP 原始响应，携带 status code 和 JSON body */
    data class JsonResponse(val code: Int, val json: JSONObject)

    fun uploadEvents(batch: EventsBatch): Result<JsonResponse> {
        return ApiHttpTransport.post("api/monitor/events", batch.toJson())
    }

    fun registerDevice(request: DeviceRegisterRequest): Result<JsonResponse> {
        return ApiHttpTransport.post("api/monitor/devices", request.toJson())
    }

    fun heartbeat(): Result<JsonResponse> {
        return ApiHttpTransport.get("api/monitor/heartbeat")
    }

    /**
     * 检测服务器 API 是否可达（连接到实际 API 端点，而非仅 IP）
     * @return true=API 可连接（收到了任何 HTTP 响应）, false=完全无法连接
     */
    fun checkConnection(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${AppConfig.serverBaseUrl.trimEnd('/')}/api/monitor/heartbeat")
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                setRequestProperty("Connection", "close")
                AppUtils.getToken()?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            conn.connect()
            val code = conn.responseCode
            // 只要服务器回了任何响应码说明 API 请求到达了服务器
            code > 0
        } catch (_: Exception) {
            false
        } finally {
            // 异常路径也必须断开连接，防止 socket 泄漏
            conn?.disconnect()
        }
    }

    // ========== Typed response helpers（实现在 ApiResponseParser）==========

    fun parseUploadResponse(resp: JsonResponse): UploadResponse = ApiResponseParser.upload(resp)

    fun parseDeviceRegisterResponse(resp: JsonResponse): DeviceRegisterResponse =
        ApiResponseParser.deviceRegister(resp)

    fun parseHeartbeatResponse(resp: JsonResponse): HeartbeatResponse = ApiResponseParser.heartbeat(resp)
}
