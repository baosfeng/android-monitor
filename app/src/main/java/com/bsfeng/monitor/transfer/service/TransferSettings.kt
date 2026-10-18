package com.bsfeng.monitor.transfer.service

import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 「传送」的持久化设置（协议 §5）：本端身份、配对密钥、提示音开关。
 *
 * - `deviceId` 首次生成后固定（`and-` + 6 位 hex），与协议示例 `and-7f3a9c` 同形；
 * - 配对密钥按 `deviceId` 持久化，32 字节随机 hex，可在设置里移除；
 * - 提示音三项默认值按协议 §7：完成开、失败关、新设备关。
 */
class TransferSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 本机设备 id（首次访问时生成并持久化）。 */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: newDeviceId().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    /** 本机别名（默认机型名，可改）。 */
    var alias: String
        get() = prefs.getString(KEY_ALIAS, null)?.takeIf { it.isNotBlank() } ?: defaultAlias
        set(value) {
            prefs.edit { putString(KEY_ALIAS, value.trim().ifBlank { defaultAlias }) }
        }

    /** 传输完成提示音（默认开，协议 §7）。 */
    var soundOnComplete: Boolean
        get() = prefs.getBoolean(KEY_SOUND_COMPLETE, true)
        set(value) = prefs.edit { putBoolean(KEY_SOUND_COMPLETE, value) }

    /** 传输失败提示音（默认关，协议 §7）。 */
    var soundOnFailure: Boolean
        get() = prefs.getBoolean(KEY_SOUND_FAILURE, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND_FAILURE, value) }

    /** 新设备出现提示（默认关，协议 §7，仅震动）。 */
    var soundOnNewDevice: Boolean
        get() = prefs.getBoolean(KEY_SOUND_NEW_DEVICE, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND_NEW_DEVICE, value) }

    /** 取某设备的配对密钥。 */
    fun pairingKey(deviceId: String): String? = pairingKeys()[deviceId]

    /** 保存配对密钥（自增的 32 字节 hex）。 */
    fun savePairingKey(deviceId: String, key: String) {
        if (deviceId.isBlank() || key.isBlank()) return
        val map = pairingKeys().toMutableMap()
        map[deviceId] = key
        writePairingKeys(map)
    }

    /** 移除某设备的配对（设置里的「移除配对」）。 */
    fun removePairingKey(deviceId: String) {
        val map = pairingKeys().toMutableMap()
        if (map.remove(deviceId) != null) writePairingKeys(map)
    }

    /** 全部配对密钥（`deviceId` → key）。 */
    fun pairingKeys(): Map<String, String> =
        PairingCodec.decode(prefs.getString(KEY_PAIRING, null))

    private fun writePairingKeys(map: Map<String, String>) {
        prefs.edit { putString(KEY_PAIRING, PairingCodec.encode(map)) }
    }

    private fun newDeviceId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(3)
        random.nextBytes(bytes)
        return "and-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private val defaultAlias: String
        get() = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    companion object {
        const val PREFS_NAME = "lan_transfer"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ALIAS = "alias"
        private const val KEY_PAIRING = "pairing_keys"
        private const val KEY_SOUND_COMPLETE = "sound_complete"
        private const val KEY_SOUND_FAILURE = "sound_failure"
        private const val KEY_SOUND_NEW_DEVICE = "sound_new_device"

        /** 生成 32 字节配对密钥（hex）。 */
        fun generatePairingKey(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 生成 4 位 PIN（接收方展示，协议 §5）。 */
        fun generatePin(): String = "%04d".format(SecureRandom().nextInt(10_000))
    }
}

/**
 * 配对密钥表的序列化（`pairing_keys` 一行 JSON）。
 *
 * fail-closed：非 JSON / 非对象一律解码为空表，不让坏数据阻断传输。
 */
object PairingCodec {

    /** `deviceId → key` → JSON 串。 */
    fun encode(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (deviceId, key) -> if (deviceId.isNotBlank() && key.isNotBlank()) json.put(deviceId, key) }
        return json.toString()
    }

    /** JSON 串 → `deviceId → key`；坏数据返回空表。 */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key, "")
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        return result
    }
}
