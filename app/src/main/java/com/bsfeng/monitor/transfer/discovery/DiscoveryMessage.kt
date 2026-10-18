package com.bsfeng.monitor.transfer.discovery

import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.MULTICAST_GROUP
import com.bsfeng.monitor.domain.transfer.TRANSFER_PROTOCOL_VERSION
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 发现消息（协议 §3）。
 *
 * 字段集固定为 `v / type / deviceId / alias / deviceType / port / proto / caps / appVersion / ts`，
 * 编码时**只写这些字段**（`discover` 按协议只写 `v / type`）。解码时对未定义行为 fail-closed：
 *
 * | 输入 | 结果 |
 * |------|------|
 * | 非法 JSON、`type` 缺失或未知 | `null` |
 * | `v` 缺失 | 按 1 处理（向后兼容） |
 * | `v` 不等于 1 | `null`（不认识的版本不参与发现） |
 * | `announce` / `offer` 缺 `deviceId` 或 `port` 不在 1..65535 | `null` |
 * | `discover` 其余字段缺失 | 取默认值，`deviceId` 可为空 |
 * | `caps` 非数组 | 取默认 `["send","recv"]` |
 */
data class DiscoveryMessage(
    val type: DiscoveryType,
    val deviceId: String = "",
    val alias: String = "",
    val deviceType: String = DeviceTypes.ANDROID,
    val port: Int = 0,
    val proto: String = "http",
    val caps: List<String> = listOf("send", "recv"),
    val appVersion: String = "",
    val ts: Long = 0L,
    val version: Int = TRANSFER_PROTOCOL_VERSION,
) {

    /** 编码为协议 JSON（字段顺序与协议文档一致，便于抓包比对）。 */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("v", version)
        json.put("type", type.wire)
        if (type == DiscoveryType.DISCOVER) return json
        json.put("deviceId", deviceId)
        json.put("alias", alias)
        json.put("deviceType", deviceType)
        json.put("port", port)
        json.put("proto", proto)
        json.put("caps", JSONArray(caps))
        json.put("appVersion", appVersion)
        json.put("ts", ts)
        return json
    }

    /** 编码为 UDP 载荷字符串。 */
    fun toJsonString(): String = toJson().toString()

    companion object {

        /** 本端发出的 `discover` 查询。 */
        fun discover(version: Int = TRANSFER_PROTOCOL_VERSION): DiscoveryMessage =
            DiscoveryMessage(type = DiscoveryType.DISCOVER, version = version)

        /** 对 `discover` 的单播应答（字段来自本端身份与 HTTP 端口）。 */
        fun offer(
            deviceId: String,
            alias: String,
            port: Int,
            deviceType: String = DeviceTypes.ANDROID,
            appVersion: String = "",
            ts: Long = nowSeconds(),
        ): DiscoveryMessage = DiscoveryMessage(
            type = DiscoveryType.OFFER,
            deviceId = deviceId,
            alias = alias,
            deviceType = deviceType,
            port = port,
            appVersion = appVersion,
            ts = ts,
        )

        /** 周期公告。 */
        fun announce(
            deviceId: String,
            alias: String,
            port: Int,
            deviceType: String = DeviceTypes.ANDROID,
            appVersion: String = "",
            ts: Long = nowSeconds(),
        ): DiscoveryMessage = DiscoveryMessage(
            type = DiscoveryType.ANNOUNCE,
            deviceId = deviceId,
            alias = alias,
            deviceType = deviceType,
            port = port,
            appVersion = appVersion,
            ts = ts,
        )

        /** 当前时间（秒），与协议 `ts` 对齐。 */
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

        /**
         * 解析 UDP 载荷。
         *
         * @return 解析失败 / 版本不支持 / 关键字段缺失时返回 `null`。
         */
        fun fromJson(raw: String): DiscoveryMessage? {
            if (raw.isBlank()) return null
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return null
            }
            return fromJson(json)
        }

        /** [fromJson] 的已解析重载。 */
        fun fromJson(json: JSONObject): DiscoveryMessage? {
            val version = if (json.has("v")) json.optInt("v", -1) else TRANSFER_PROTOCOL_VERSION
            if (version != TRANSFER_PROTOCOL_VERSION) return null
            val type = DiscoveryType.fromWire(json.optString("type", "")) ?: return null
            if (type == DiscoveryType.DISCOVER) {
                return DiscoveryMessage(type = type, version = version)
            }
            val deviceId = json.optString("deviceId", "")
            val port = json.optInt("port", 0)
            if (deviceId.isBlank() || port !in 1..65535) return null
            return DiscoveryMessage(
                type = type,
                deviceId = deviceId,
                alias = json.optString("alias", ""),
                deviceType = json.optString("deviceType", DeviceTypes.ANDROID)
                    .ifBlank { DeviceTypes.UNKNOWN },
                port = port,
                proto = json.optString("proto", "http"),
                caps = readCaps(json),
                appVersion = json.optString("appVersion", ""),
                ts = json.optLong("ts", 0L),
                version = version,
            )
        }

        private fun readCaps(json: JSONObject): List<String> {
            val array = json.optJSONArray("caps") ?: return listOf("send", "recv")
            val caps = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val cap = array.optString(i, "")
                if (cap.isNotBlank()) caps += cap
            }
            return caps.ifEmpty { listOf("send", "recv") }
        }

        /** 组播组地址常量（从领域常量透出，便于发现层单点引用）。 */
        val multicastGroup: String get() = MULTICAST_GROUP
    }
}

/** 发现消息类型。 */
enum class DiscoveryType(val wire: String) {
    /** 周期公告。 */
    ANNOUNCE("announce"),

    /** 主动查询。 */
    DISCOVER("discover"),

    /** 对 `discover` 的单播应答。 */
    OFFER("offer"),
    ;

    companion object {
        /** 线值 → 类型；未知返回 `null`。 */
        fun fromWire(value: String?): DiscoveryType? =
            entries.firstOrNull { it.wire == value }
    }
}
