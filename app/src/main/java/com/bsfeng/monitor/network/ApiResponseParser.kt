package com.bsfeng.monitor.network

// 从 network/ApiService.kt 拆出：响应 JSON → DTO 的解析。
// 负责「字段怎么读、缺省怎么填」，不含网络动作，也不持有连接。

import org.json.JSONObject

/** 注册 / 心跳响应里 `data.config` 的公共解析（两处字段完全相同）。 */
internal object ApiResponseParser {

    private fun serverConfig(c: JSONObject?): ServerConfig? = c?.let {
        ServerConfig(
            uploadIntervalMs = it.optInt("uploadIntervalMs"),
            batchMaxSize = it.optInt("batchMaxSize"),
            retryMaxAttempts = it.optInt("retryMaxAttempts"),
            retryBackoffBaseMs = it.optInt("retryBackoffBaseMs"),
            cacheMaxDays = it.optInt("cacheMaxDays"),
            enableNotificationContent = it.optBoolean("enableNotificationContent")
        )
    }

    /** 上报响应 → [UploadResponse]。 */
    fun upload(resp: ApiService.JsonResponse): UploadResponse {
        val data = resp.json.optJSONObject("data")
        return UploadResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let {
                UploadData(
                    acceptedCount = it.optInt("acceptedCount"),
                    rejectedCount = it.optInt("rejectedCount"),
                    serverTimeMs = if (it.has("serverTimeMs")) it.getLong("serverTimeMs") else null
                )
            }
        )
    }

    /** 注册响应 → [DeviceRegisterResponse]。 */
    fun deviceRegister(resp: ApiService.JsonResponse): DeviceRegisterResponse {
        val data = resp.json.optJSONObject("data")
        return DeviceRegisterResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let {
                DeviceRegisterData(
                    deviceId = it.optString("deviceId"),
                    token = it.optString("token"),
                    tokenExpiresAtMs = it.optLong("tokenExpiresAtMs"),
                    config = serverConfig(it.optJSONObject("config"))
                )
            }
        )
    }

    /** 心跳响应 → [HeartbeatResponse]。 */
    fun heartbeat(resp: ApiService.JsonResponse): HeartbeatResponse {
        val data = resp.json.optJSONObject("data")
        return HeartbeatResponse(
            code = resp.json.optInt("code"),
            message = resp.json.optString("message"),
            data = data?.let {
                HeartbeatData(
                    serverTimeMs = it.optLong("serverTimeMs"),
                    config = serverConfig(it.optJSONObject("config"))
                )
            }
        )
    }
}
