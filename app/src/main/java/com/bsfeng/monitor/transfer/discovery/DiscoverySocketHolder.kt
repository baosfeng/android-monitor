package com.bsfeng.monitor.transfer.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import com.bsfeng.monitor.domain.transfer.DISCOVERY_PORT
import com.bsfeng.monitor.domain.transfer.MULTICAST_GROUP

// 从 transfer/discovery/UdpDiscoveryService.kt 拆出：UDP socket 的绑定/关闭、组播锁、网卡地址。
// 负责「句柄从哪来、怎么保证释放」，不含公告/应答的业务时序。

/**
 * 发现层的 socket 与组播锁持有者。
 *
 * 失败路径全部走 `runCatching` 并降级（组播不可用 → 单播 socket → 仅发送），
 * [close] 幂等，重复调用不会抛异常。Android 特有约束：不持 [WifiManager.MulticastLock]
 * 时多数 ROM 收不到组播。
 */
internal class DiscoverySocketHolder(private val appContext: Context) {

    var socket: DatagramSocket? = null
        private set

    private var multicastSocket: MulticastSocket? = null
    private var fallbackSender: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 当前绑定的本地端口（绑定失败为 -1）。 */
    fun localPort(): Int = socket?.localPort ?: -1

    /**
     * 打开接收 socket：优先组播（加入组播组失败时仍可收单播），
     * 整体失败再退到纯单播 socket。返回是否成功拿到 socket。
     */
    fun open(): Boolean {
        return try {
            val multicast = MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = RECEIVE_TIMEOUT_MS
            }
            val group = InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), DISCOVERY_PORT)
            runCatching {
                multicast.joinGroup(group, preferredInterface())
            }.onFailure { Log.w(TAG, "加入组播组失败，改用广播/单播: ${it.message}") }
            multicastSocket = multicast
            socket = multicast
            true
        } catch (e: IOException) {
            runCatching { unicastSocket() }
                .onSuccess { socket = it }
                .onFailure { Log.w(TAG, "发现层 socket 全部打开失败: ${it.message}") }
            socket != null
        }
    }

    /** 组播不可用时的单播接收 socket（仍绑定发现端口，可收单播 offer）。 */
    private fun unicastSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(DISCOVERY_PORT))
        soTimeout = RECEIVE_TIMEOUT_MS
    }

    /** 主 socket 不可用时的发送兜底（不绑定端口，复用同一个实例避免句柄泄漏）。 */
    fun fallbackSender(): DatagramSocket =
        fallbackSender ?: DatagramSocket().also {
            it.broadcast = true
            fallbackSender = it
        }

    /** 关闭全部句柄并退出组播组；幂等。 */
    fun close() {
        val multicast = multicastSocket
        if (multicast != null) {
            val group = InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), DISCOVERY_PORT)
            runCatching { multicast.leaveGroup(group, preferredInterface()) }
                .onFailure { Log.w(TAG, "退出组播组失败: ${it.message}") }
        }
        runCatching { socket?.close() }
        runCatching { fallbackSender?.close() }
        socket = null
        multicastSocket = null
        fallbackSender = null
    }

    /** 获取 [WifiManager.MulticastLock]（先释放旧的，保证不叠加持有）。 */
    fun acquireMulticastLock() {
        releaseMulticastLock()
        multicastLock = runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching null
            wifi.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "MulticastLock 获取失败: ${it.message}") }
            .getOrNull()
    }

    fun releaseMulticastLock() {
        val held = multicastLock ?: return
        multicastLock = null
        runCatching { if (held.isHeld) held.release() }
            .onFailure { Log.w(TAG, "MulticastLock 释放失败: ${it.message}") }
    }

    private fun preferredInterface(): NetworkInterface? =
        runCatching { localIpv4Addresses().firstOrNull()?.let { NetworkInterface.getByInetAddress(it) } }
            .getOrNull()

    /** 本机 IPv4 地址（排除回环 / 链路本地）。 */
    fun localIpv4Addresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "TransferDiscovery"
        const val LOCK_TAG = "lan-transfer-multicast"
        const val RECEIVE_TIMEOUT_MS = 1_000
    }
}
