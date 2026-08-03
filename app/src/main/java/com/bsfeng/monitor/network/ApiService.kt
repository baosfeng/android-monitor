package com.bsfeng.monitor.network

import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.util.AppUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 使用系统内置 HttpURLConnection 的网络层 — 替换 Retrofit/OkHttp
 */
object ApiService {
    private const val TIMEOUT_MS = 10_000

    /** HTTP 原始响应，携带 status code 和 JSON body */
    data class JsonResponse(val code: Int, val json: JSONObject)

    fun uploadEvents(batch: EventsBatch): Result<JsonResponse> {
        return httpPost("api/monitor/events", batch.toJson())
    }

    fun registerDevice(request: DeviceRegisterRequest): Result<JsonResponse> {
        return httpPost("api/monitor/devices", request.toJson())
    }

    fun heartbeat(): Result<JsonResponse> {
        return httpGet("api/monitor/heartbeat")
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

    // ========== Internal HTTP ==========

    private fun httpPost(path: String, body: JSONObject): Result<JsonResponse> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${AppConfig.serverBaseUrl.trimEnd('/')}/$path")
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                AppUtils.getToken()?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
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
                Result.failure(Exception("HTTP $responseCode (empty body)"))
            } else {
                val json = try {
                    JSONObject(responseBody)
                } catch (_: Exception) {
                    JSONObject().apply {
                        put("code", -1)
                        put("message", "Non-JSON response (HTTP $responseCode): ${responseBody.take(200)}")
                    }
                }
                Result.success(JsonResponse(responseCode, json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 异常路径也必须断开连接，防止 socket 泄漏
            conn?.disconnect()
        }
    }

    private fun httpGet(path: String): Result<JsonResponse> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${AppConfig.serverBaseUrl.trimEnd('/')}/$path")
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                AppUtils.getToken()?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
            }
            if (responseBody.isBlank()) {
                Result.failure(Exception("HTTP $responseCode (empty body)"))
            } else {
                val json = try {
                    JSONObject(responseBody)
                } catch (_: Exception) {
                    JSONObject().apply {
                        put("code", -1)
                        put("message", "Non-JSON response (HTTP $responseCode): ${responseBody.take(200)}")
                    }
                }
                Result.success(JsonResponse(responseCode, json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 异常路径也必须断开连接，防止 socket 泄漏
            conn?.disconnect()
        }
    }

    // ========== Typed response helpers ==========

    fun parseUploadResponse(resp: JsonResponse): UploadResponse {
        val data = resp.json.optJSONObject("data")
        return UploadResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let { UploadData(
                acceptedCount = it.optInt("acceptedCount"),
                rejectedCount = it.optInt("rejectedCount"),
                serverTimeMs = if (it.has("serverTimeMs")) it.getLong("serverTimeMs") else null
            )}
        )
    }

    fun parseDeviceRegisterResponse(resp: JsonResponse): DeviceRegisterResponse {
        val data = resp.json.optJSONObject("data")
        return DeviceRegisterResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let { DeviceRegisterData(
                deviceId = it.optString("deviceId"),
                token = it.optString("token"),
                tokenExpiresAtMs = it.optLong("tokenExpiresAtMs"),
                config = it.optJSONObject("config")?.let { c -> ServerConfig(
                    uploadIntervalMs = c.optInt("uploadIntervalMs"),
                    batchMaxSize = c.optInt("batchMaxSize"),
                    retryMaxAttempts = c.optInt("retryMaxAttempts"),
                    retryBackoffBaseMs = c.optInt("retryBackoffBaseMs"),
                    cacheMaxDays = c.optInt("cacheMaxDays"),
                    enableNotificationContent = c.optBoolean("enableNotificationContent")
                )}
            )}
        )
    }

    fun parseHeartbeatResponse(resp: JsonResponse): HeartbeatResponse {
        val data = resp.json.optJSONObject("data")
        return HeartbeatResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let { HeartbeatData(
                serverTimeMs = it.optLong("serverTimeMs"),
                config = it.optJSONObject("config")?.let { c -> ServerConfig(
                    uploadIntervalMs = c.optInt("uploadIntervalMs"),
                    batchMaxSize = c.optInt("batchMaxSize"),
                    retryMaxAttempts = c.optInt("retryMaxAttempts"),
                    retryBackoffBaseMs = c.optInt("retryBackoffBaseMs"),
                    cacheMaxDays = c.optInt("cacheMaxDays"),
                    enableNotificationContent = c.optBoolean("enableNotificationContent")
                )}
            )}
        )
    }
}

// ========== Request / Response DTOs ==========

data class EventsBatch(
    val events: List<Record>,
    val batchId: String,
    val deviceId: String,
    val batchCreatedAtMs: Long,
    val batchSize: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("events", JSONArray(events.map { record ->
            JSONObject().apply {
                put("id", record.id)
                put("type", RecordType.Companion.toString(record.type))
                put("action", record.action)
                put("timestamp", record.timestamp)
                record.duration?.let { put("duration", it) }
                put("label", record.label)
                record.detail?.let { put("detail", JSONObject(it)) }
            }
        }))
        put("batchId", batchId)
        put("deviceId", deviceId)
        put("batchCreatedAtMs", batchCreatedAtMs)
        put("batchSize", batchSize)
    }
}

data class DeviceRegisterRequest(
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val appBuild: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("manufacturer", manufacturer)
        put("model", model)
        put("androidVersion", androidVersion)
        put("appVersion", appVersion)
        put("appBuild", appBuild)
    }
}

data class UploadResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: UploadData? = null
)

data class UploadData(
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    val serverTimeMs: Long? = null
)

data class DeviceRegisterResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: DeviceRegisterData? = null
)

data class DeviceRegisterData(
    val deviceId: String = "",
    val token: String = "",
    val tokenExpiresAtMs: Long = 0L,
    val config: ServerConfig? = null
)

data class ServerConfig(
    val uploadIntervalMs: Int = 5000,
    val batchMaxSize: Int = 50,
    val retryMaxAttempts: Int = 5,
    val retryBackoffBaseMs: Int = 1000,
    val cacheMaxDays: Int = 3,
    val enableNotificationContent: Boolean = true
)

data class HeartbeatResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: HeartbeatData? = null
)

data class HeartbeatData(
    val serverTimeMs: Long = 0L,
    val config: ServerConfig? = null
)
