package com.bsfeng.monitor.transfer.transport

import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import org.json.JSONException
import org.json.JSONObject

/**
 * 传输层统一响应包（沿用本仓库 `{code, message, data}` 约定）。
 *
 * 约定：HTTP 状态码只表达「报文是否被接收」，业务成败由 [code] 表达
 * （0 成功；4001~4007 见协议 §4.1）。`data` 缺省为空对象，便于客户端统一 `optJSONObject`。
 */
data class ApiResponse(
    val code: Int,
    val message: String,
    val data: JSONObject = JSONObject(),
) {
    /** 是否业务成功。 */
    val isOk: Boolean get() = code == TransferErrorCode.SUCCESS

    /** 编码响应体。 */
    fun toJsonString(): String = JSONObject().apply {
        put("code", code)
        put("message", message)
        put("data", data)
    }.toString()

    /** 读 `data` 里的字符串字段。 */
    fun string(key: String, fallback: String = ""): String = data.optString(key, fallback)

    /** 读 `data` 里的整数字段。 */
    fun int(key: String, fallback: Int = 0): Int = data.optInt(key, fallback)

    /** 读 `data` 里的长整数字段。 */
    fun long(key: String, fallback: Long = 0L): Long = data.optLong(key, fallback)

    companion object {
        /** 协议层错误码（不属于 §4.1 业务码，用于报文非法 / 未知路由）。 */
        const val PROTOCOL_ERROR: Int = -1

        /** 成功（`data` 可选）。 */
        fun ok(data: JSONObject = JSONObject(), message: String = "成功"): ApiResponse =
            ApiResponse(TransferErrorCode.SUCCESS, message, data)

        /** 业务失败（错误码见协议 §4.1）。 */
        fun error(code: Int, message: String = TransferErrorCode.message(code)): ApiResponse =
            ApiResponse(code, message)

        /**
         * 解析响应体。
         *
         * @return 非法 JSON / 缺 `code` 时返回 null（调用方按「对端异常」处理）。
         */
        fun parse(raw: String?): ApiResponse? {
            if (raw.isNullOrBlank()) return null
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return null
            }
            if (!json.has("code")) return null
            return ApiResponse(
                code = json.optInt("code", -1),
                message = json.optString("message", ""),
                data = json.optJSONObject("data") ?: JSONObject(),
            )
        }
    }
}
