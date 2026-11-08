package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.util.KeystoreAesGcmCipher
import com.bsfeng.monitor.util.SecretBox
import com.bsfeng.monitor.util.SecretCipher
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 「传送」的持久化设置（协议 §5）：本端身份、配对密钥、提示音开关。
 *
 * - `deviceId` 首次生成后固定（`and-` + 6 位 hex），与协议示例 `and-7f3a9c` 同形；
 * - 配对密钥按 `deviceId` 持久化（32 字节随机 hex），落盘前整体走 Keystore AES-GCM 加密 ——
 *   它命中即免 PIN 直批，明文存等于「拿到文件＝拿到通行证」，可在设置里移除；
 * - 提示音三项默认值按协议 §7：完成开、失败关、新设备关；
 * - 发现开关 / 免配对窗口 / 体积阈值：新增的「少打断」设置，策略判定在
 *   [PairingPolicy]（纯函数），本类只负责持久化。
 */
class TransferSettings(
    context: Context,
    private val cipher: SecretCipher = resolveCipher(),
) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 本机设备 id（首次访问时生成并持久化）。 */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: newDeviceId().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    /**
     * 本机别名（对外广播的 `alias`）。
     *
     * 优先级：用户在设置页显式改过的名字（[KEY_ALIAS_MANUAL]）→ 系统「设备名称」→ 厂商 + 型号 → 兜底。
     * 未手动改名时**每次读取都重新解析**，因此用户在系统设置里改了设备名、重启 App 后立即跟随；
     * 解析结果同时写一份缓存，仅用于系统读取失败时保留上次显示，不参与优先级判定。
     */
    var alias: String
        get() {
            prefs.getString(KEY_ALIAS_MANUAL, null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val resolved = DeviceAliasResolver.resolveDeviceAlias(appContext)
            prefs.edit { putString(KEY_ALIAS_CACHE, resolved) }
            return resolved
        }
        set(value) {
            val trimmed = value.trim()
            prefs.edit {
                if (trimmed.isBlank()) remove(KEY_ALIAS_MANUAL) else putString(KEY_ALIAS_MANUAL, trimmed)
            }
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

    /**
     * 「后台接收」：默认关闭。
     *
     * 关闭时只在 App 可见期间运行服务（见 [ServiceModePolicy]），开启才常驻前台服务 + 常驻通知。
     */
    var backgroundReceive: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_RECEIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_BACKGROUND_RECEIVE, value) }

    /**
     * 「自动关闭接收」：接收服务启动后多久自动停止（分钟；0 = 关闭，默认）。
     *
     * 只持久化**时长**，不持久化计时起点：进程重启后倒计时不恢复（见 [ReceivingAutoClose]）。
     */
    var autoCloseMinutes: Int
        get() = prefs.getInt(KEY_AUTO_CLOSE_MINUTES, ReceivingAutoClose.DEFAULT_MINUTES)
        set(value) = prefs.edit { putInt(KEY_AUTO_CLOSE_MINUTES, ReceivingAutoClose.normalize(value)) }

    /** 暂停接收的到期时刻（epoch ms；0 = 未暂停）。 */
    var pauseOffersUntilMs: Long
        get() = prefs.getLong(KEY_PAUSE_OFFERS_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_PAUSE_OFFERS_UNTIL, value) }

    /** 暂停接收（默认 5 分钟；期间 offer 直接拒绝、不弹 PIN）。 */
    fun pauseOffers(
        minutes: Int = PAUSE_MINUTES,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        pauseOffersUntilMs = nowMs + minutes.coerceAtLeast(0) * WINDOW_MINUTE_MS
    }

    /** 立即恢复接收。 */
    fun resumeOffers() {
        pauseOffersUntilMs = 0L
    }

    /** 当前是否处于暂停窗口。 */
    fun isOffersPaused(nowMs: Long = System.currentTimeMillis()): Boolean =
        pauseOffersUntilMs > nowMs

    /** 暂停剩余毫秒（未暂停返回 0）。 */
    fun pauseRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        (pauseOffersUntilMs - nowMs).coerceAtLeast(0L)

    /** 是否参与发现（关闭后停止 UDP 公告与应答；HTTP 端口仍常驻）。 */
    var discoverable: Boolean
        get() = prefs.getBoolean(KEY_DISCOVERABLE, true)
        set(value) = prefs.edit { putBoolean(KEY_DISCOVERABLE, value) }

    /** 免配对窗口开关（到期后由 [isOpenPairingActive] 判定失效，不需要改开关）。 */
    var openPairingEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPEN_PAIRING_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_OPEN_PAIRING_ENABLED, value) }

    /** 窗口时长（分钟，5 / 10 / 30）。 */
    var openPairingMinutes: Int
        get() = prefs.getInt(KEY_OPEN_PAIRING_MINUTES, DEFAULT_WINDOW_MINUTES)
        set(value) = prefs.edit {
            putInt(KEY_OPEN_PAIRING_MINUTES, value.takeIf { it in PairingPolicy.WINDOW_MINUTES } ?: DEFAULT_WINDOW_MINUTES)
        }

    /** 窗口到期时刻（epoch ms；0 = 未开启）。 */
    var openPairingExpiresAtMs: Long
        get() = prefs.getLong(KEY_OPEN_PAIRING_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_OPEN_PAIRING_EXPIRES_AT, value) }

    /** 体积阈值（MB；0 = 关闭，一律需要 PIN）。 */
    var autoApproveThresholdMb: Int
        get() = prefs.getInt(KEY_AUTO_APPROVE_MB, PairingPolicy.THRESHOLD_OFF_MB)
        set(value) = prefs.edit { putInt(KEY_AUTO_APPROVE_MB, value.coerceAtLeast(0)) }

    /** 窗口当前是否生效。 */
    fun isOpenPairingActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        openPairingEnabled && PairingPolicy.isWindowActive(openPairingExpiresAtMs, nowMs)

    /** 窗口剩余毫秒（未开启 / 已过期返回 0）。 */
    fun openPairingRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        if (!openPairingEnabled) 0L else PairingPolicy.remainingMs(openPairingExpiresAtMs, nowMs)

    /** 开启窗口：从 [nowMs] 起算 [minutes] 分钟（同时打开开关）。 */
    fun startOpenPairingWindow(
        nowMs: Long = System.currentTimeMillis(),
        minutes: Int = openPairingMinutes,
    ) {
        val duration = minutes.takeIf { it in PairingPolicy.WINDOW_MINUTES } ?: DEFAULT_WINDOW_MINUTES
        prefs.edit {
            putInt(KEY_OPEN_PAIRING_MINUTES, duration)
            putBoolean(KEY_OPEN_PAIRING_ENABLED, true)
            putLong(KEY_OPEN_PAIRING_EXPIRES_AT, nowMs + duration * WINDOW_MINUTE_MS)
        }
    }

    /** 关闭窗口（立即回到需配对）。 */
    fun stopOpenPairingWindow() {
        prefs.edit {
            putBoolean(KEY_OPEN_PAIRING_ENABLED, false)
            putLong(KEY_OPEN_PAIRING_EXPIRES_AT, 0L)
        }
    }

    /**
     * 本次 offer 是否免确认（窗口 或 体积阈值，OR 关系）。
     *
     * @param totalBytes 本次 offer 的文件总大小。
     */
    fun shouldAutoApprove(totalBytes: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        PairingPolicy.isAutoApproved(
            windowExpiresAtMs = if (openPairingEnabled) openPairingExpiresAtMs else 0L,
            thresholdMb = autoApproveThresholdMb,
            totalBytes = totalBytes,
            nowMs = nowMs,
        )

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

    /**
     * 全部配对密钥（`deviceId` → key）。
     *
     * 读口径（fail-closed）：密文解不开——Keystore 被系统重置、值被改坏——一律按空表处理。
     * 这张表是「命中即免 PIN 直批」的凭据，宁可让对端重新配一次，也不能拿半截数据去比。
     */
    fun pairingKeys(): Map<String, String> = PairingCodec.decode(readPairingJson())

    /**
     * 读 `pairing_keys` 的 JSON 正文。
     *
     * 新格式是 `v1:` + Keystore 密文；老版本直接存明文 JSON，读到即就地加密回写（升级一次，
     * 之后盘上不再有明文）。明文迁移失败（加密不可用）仍返回旧正文，配对关系不丢。
     */
    private fun readPairingJson(): String? {
        val stored = prefs.getString(KEY_PAIRING, null) ?: return null
        if (!SecretBox.isEncoded(stored)) {
            if (stored.isNotBlank()) writePairingCiphertext(stored)
            return stored
        }
        return runCatching { String(SecretBox.decode(cipher, stored), Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "配对密钥解密失败，按未配对处理：" + it.javaClass.simpleName) }
            .getOrNull()
    }

    private fun writePairingKeys(map: Map<String, String>) {
        if (map.isEmpty()) {
            // 清空时删键而不是留一密文空表：让「没有配对」在盘上也看得出来
            prefs.edit { remove(KEY_PAIRING) }
            return
        }
        writePairingCiphertext(PairingCodec.encode(map))
    }

    /** 加密后落盘；加密不可用只记日志、不退回明文。 */
    private fun writePairingCiphertext(json: String) {
        runCatching { SecretBox.encode(cipher, json.toByteArray(Charsets.UTF_8)) }
            .onSuccess { encoded -> prefs.edit { putString(KEY_PAIRING, encoded) } }
            .onFailure { Log.w(TAG, "配对密钥加密失败（未落盘）：" + it.javaClass.simpleName) }
    }

    private fun newDeviceId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(3)
        random.nextBytes(bytes)
        return "and-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private val defaultAlias: String
        get() = DeviceAliasResolver.resolveDeviceAlias(appContext)

    companion object {
        const val PREFS_NAME = "lan_transfer"
        private const val DEFAULT_WINDOW_MINUTES = 10
        private const val WINDOW_MINUTE_MS = 60_000L
        private const val KEY_DISCOVERABLE = "discoverable"
        private const val KEY_BACKGROUND_RECEIVE = "background_receive"
        private const val KEY_AUTO_CLOSE_MINUTES = "auto_close_minutes"
        private const val KEY_PAUSE_OFFERS_UNTIL = "pause_offers_until"
        private const val PAUSE_MINUTES = 5
        private const val KEY_OPEN_PAIRING_ENABLED = "open_pairing_enabled"
        private const val KEY_OPEN_PAIRING_MINUTES = "open_pairing_minutes"
        private const val KEY_OPEN_PAIRING_EXPIRES_AT = "open_pairing_expires_at"
        private const val KEY_AUTO_APPROVE_MB = "auto_approve_threshold_mb"
        private const val KEY_DEVICE_ID = "device_id"

        /** 用户手动设置的本机名称（最高优先级；清空即回落系统设备名）。 */
        private const val KEY_ALIAS_MANUAL = "alias_manual"

        /** 上次解析出的本机名称，仅作展示缓存。 */
        private const val KEY_ALIAS_CACHE = "alias"
        private const val KEY_PAIRING = "pairing_keys"

        /** 配对密钥的 Keystore 别名（独立于 auth_token_key / quota_credential_key）。 */
        private const val KEY_ALIAS = "lan_transfer_pairing_key"

        /**
         * 默认 cipher 的替身口子：Robolectric 沙箱没有 AndroidKeyStore，引擎级用例
         * （TransferEngineAutoApproveTest）经由 TransferEngine 内部构造设置，没法从外面传参。
         * 生产恒为 null → Keystore；显式传入 cipher 的构造永远优先于这里。
         */
        @Volatile
        internal var overrideCipherForTesting: SecretCipher? = null

        private fun resolveCipher(): SecretCipher =
            overrideCipherForTesting ?: KeystoreAesGcmCipher(KEY_ALIAS)

        private const val TAG = "TransferSettings"
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
 * 配对密钥表的序列化（`pairing_keys` 的 JSON 正文；整段在落盘前已加密，见 [TransferSettings]）。
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
