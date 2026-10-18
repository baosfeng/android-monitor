package com.bsfeng.monitor.transfer.discovery

import com.bsfeng.monitor.domain.transfer.OFFLINE_AFTER_MS
import com.bsfeng.monitor.domain.transfer.OFFER_REPLY_WINDOW_MS
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.REMOVE_AFTER_MS
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS

/**
 * 设备表与离线判定（协议 §3：15s 未收到公告标记离线，60s 未收到从列表移除）。
 *
 * 纯 Kotlin（时间由调用方传入 `nowMs`），可纯 JVM 单测。
 * 线程安全：所有公开方法 `@Synchronized`，发现线程与 UI 线程可并发访问。
 */
class DeviceRegistry(
    private val offlineAfterMs: Long = OFFLINE_AFTER_MS,
    private val removeAfterMs: Long = REMOVE_AFTER_MS,
) {
    init {
        require(removeAfterMs > offlineAfterMs) {
            "移除阈值必须大于离线阈值：offline=$offlineAfterMs remove=$removeAfterMs"
        }
    }

    private val devices = LinkedHashMap<String, PeerDevice>()

    /**
     * 收到一条公告 / 应答：记录或刷新设备（以 `deviceId` 去重，刷新 IP 与端口）。
     *
     * @return 刷新后的设备快照。
     */
    @Synchronized
    fun onSeen(message: DiscoveryMessage, hostAddress: String, nowMs: Long): PeerDevice {
        val device = PeerDevice(
            deviceId = message.deviceId,
            alias = message.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS },
            deviceType = message.deviceType,
            hostAddress = hostAddress,
            port = message.port,
            proto = message.proto,
            caps = message.caps,
            appVersion = message.appVersion,
            lastSeenMs = nowMs,
            online = true,
        )
        devices[message.deviceId] = device
        return device
    }

    /**
     * 当前设备列表快照：先按 [nowMs] 应用离线 / 移除规则。
     *
     * 排序：在线优先，其次 `lastSeenMs` 倒序（最新出现的在前）。
     */
    @Synchronized
    fun snapshot(nowMs: Long): List<PeerDevice> {
        val iterator = devices.entries.iterator()
        while (iterator.hasNext()) {
            val (id, device) = iterator.next()
            val age = nowMs - device.lastSeenMs
            when {
                age >= removeAfterMs -> iterator.remove()
                age >= offlineAfterMs -> devices[id] = device.copy(online = false)
                else -> if (!device.online) devices[id] = device.copy(online = true)
            }
        }
        return devices.values.sortedWith(
            compareByDescending<PeerDevice> { it.online }.thenByDescending { it.lastSeenMs },
        )
    }

    /** 按 id 取设备（不改变离线状态）；不存在返回 null。 */
    @Synchronized
    fun find(deviceId: String): PeerDevice? = devices[deviceId]

    /** 移除指定设备（对方 / 本端主动下线）。 */
    @Synchronized
    fun remove(deviceId: String): PeerDevice? = devices.remove(deviceId)

    /** 清空（停止发现时调用）。 */
    @Synchronized
    fun clear() {
        devices.clear()
    }

    /** 当前登记的设备数（含离线未移除的）。 */
    @Synchronized
    fun size(): Int = devices.size
}

/**
 * `discover` 应答节流：同一 IP 在 [windowMs] 内只回一次 `offer`（协议 §3）。
 *
 * 线程安全（UDP 接收线程单线程调用，仍加锁以免与测试并发）。
 */
class OfferReplyLimiter(private val windowMs: Long = OFFER_REPLY_WINDOW_MS) {

    private val lastReplyAt = HashMap<String, Long>()

    /** 是否允许对 [hostAddress] 应答（首次或窗口已过返回 true，并向前推移窗口）。 */
    @Synchronized
    fun allow(hostAddress: String, nowMs: Long): Boolean {
        val last = lastReplyAt[hostAddress]
        if (last != null && nowMs - last < windowMs) return false
        lastReplyAt[hostAddress] = nowMs
        return true
    }

    /** 清理窗口外的记录，避免长时间运行后表无限增长。 */
    @Synchronized
    fun prune(nowMs: Long) {
        val iterator = lastReplyAt.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value >= windowMs) iterator.remove()
        }
    }
}
