package com.bsfeng.monitor.transfer.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bsfeng.monitor.domain.transfer.ANNOUNCE_BURST_COUNT
import com.bsfeng.monitor.domain.transfer.ANNOUNCE_BURST_INTERVAL_MS
import com.bsfeng.monitor.domain.transfer.ANNOUNCE_INTERVAL_MS
import com.bsfeng.monitor.domain.transfer.BROADCAST_ADDRESS
import com.bsfeng.monitor.domain.transfer.DISCOVERY_PORT
import com.bsfeng.monitor.domain.transfer.DISCOVERY_UNICAST_CONCURRENCY
import com.bsfeng.monitor.domain.transfer.DISCOVERY_UNICAST_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.MULTICAST_GROUP
import com.bsfeng.monitor.domain.transfer.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP 发现服务（协议 §3）。
 *
 * 职责：组播 + 广播公告、接收公告 / 应答、对 `discover` 单播 `offer`、兜底 `/24` 单播查询。
 * **Android 特有约束**：持有一个 [WifiManager.MulticastLock]，否则多数 ROM 上收不到组播；
 * [stop] 与 [start] 的失败路径都会 [WifiManager.MulticastLock.release]，不会泄漏。
 *
 * 生命周期：由 [com.bsfeng.monitor.transfer.service.TransferEngine] 在应用前台服务内启停，
 * [start] 可重复调用（幂等），[stop] 可重复调用。
 */
class UdpDiscoveryService(
    context: Context,
    private val deviceId: String,
    private val alias: String,
    private val appVersion: String,
    private val httpPort: () -> Int,
    private val registry: DeviceRegistry,
    private val onDeviceSeen: (PeerDevice) -> Unit = {},
) {
    private val appContext: Context = context.applicationContext
    private val running = AtomicBoolean(false)
    private val limiter = OfferReplyLimiter()

    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var fallbackSender: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jobs: List<Job> = emptyList()

    /** 当前是否在发现。 */
    fun isRunning(): Boolean = running.get()

    /** 当前绑定的本地端口（绑定失败为 -1）。 */
    fun localPort(): Int = socket?.localPort ?: -1

    /** 启动发现（幂等）。绑定或组播加入失败时降级为「仅广播发送」，不抛异常。 */
    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        if (!openSocket()) {
            Log.w(TAG, "UDP 发现绑定失败，降级为仅发送模式")
        }
        jobs = listOf(
            scope.launch { receiveLoop() },
            scope.launch { announceLoop() },
        )
    }

    /** 停止发现并释放 [MulticastLock]。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            releaseMulticastLock()
            return
        }
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        closeSocket()
        releaseMulticastLock()
        registry.clear()
    }

    // ─────────────────────────── 发送 ───────────────────────────

    private suspend fun announceLoop() {
        // 启动连发 3 次（间隔 500ms）
        repeat(ANNOUNCE_BURST_COUNT) {
            if (!running.get()) return
            sendAnnounce()
            delay(ANNOUNCE_BURST_INTERVAL_MS)
        }
        while (running.get()) {
            delay(ANNOUNCE_INTERVAL_MS)
            if (!running.get()) return
            sendAnnounce()
            // 表里没有在线设备时，兜底扫一遍（组播 → 广播 → /24 单播）
            if (registry.snapshot(System.currentTimeMillis()).none { it.online }) {
                runDiscoverySweep()
            }
            limiter.prune(System.currentTimeMillis())
        }
    }

    /** 公告：组播优先，失败回落广播（协议 §3 兜底顺序）。 */
    private suspend fun sendAnnounce() = withContext(Dispatchers.IO) {
        val message = DiscoveryMessage.announce(
            deviceId = deviceId,
            alias = alias,
            port = httpPort(),
            appVersion = appVersion,
        ).toJsonString()
        val multicastOk = runCatching { sendTo(MULTICAST_GROUP, message) }.isSuccess
        if (!multicastOk) {
            runCatching { sendTo(BROADCAST_ADDRESS, message) }
                .onFailure { Log.w(TAG, "广播公告发送失败: ${it.message}") }
        }
    }

    /** 兜底发现：组播 discover → 广播 discover → 本机 /24 逐 IP 单播 discover。 */
    private suspend fun runDiscoverySweep() {
        val sweep = DiscoveryMessage.discover().toJsonString()
        val multicastOk = runCatching { sendTo(MULTICAST_GROUP, sweep) }.isSuccess
        runCatching { sendTo(BROADCAST_ADDRESS, sweep) }
        if (!multicastOk) {
            unicastSweep(sweep)
        }
    }

    private suspend fun unicastSweep(payload: String) = withContext(Dispatchers.IO) {
        val hosts = slash24Hosts()
        if (hosts.isEmpty()) return@withContext
        val permits = Semaphore(DISCOVERY_UNICAST_CONCURRENCY)
        coroutineScope {
            hosts.map { host ->
                async {
                    permits.withPermit {
                        runCatching { sendTo(host, payload) }
                    }
                }
            }.awaitAll()
        }
        // 单 IP 等待窗口：给应答留出到达时间（收包在 receiveLoop 中处理）
        delay(DISCOVERY_UNICAST_TIMEOUT_MS.toLong())
    }

    private fun sendOfferTo(hostAddress: String) {
        val payload = DiscoveryMessage.offer(
            deviceId = deviceId,
            alias = alias,
            port = httpPort(),
            appVersion = appVersion,
        ).toJsonString()
        runCatching { sendTo(hostAddress, payload) }
            .onFailure { Log.w(TAG, "offer 应答发送失败: ${it.message}") }
    }

    private fun sendTo(host: String, payload: String) {
        val target = socket ?: openFallbackSender()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), DISCOVERY_PORT)
        target.send(packet)
    }

    /** 主 socket 不可用时的发送兜底（不绑定端口，复用同一个实例避免句柄泄漏）。 */
    private fun openFallbackSender(): DatagramSocket =
        fallbackSender ?: DatagramSocket().also {
            it.broadcast = true
            fallbackSender = it
        }

    // ─────────────────────────── 接收 ───────────────────────────

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running.get() && currentCoroutineContext().isActive) {
            val source = socket
            if (source == null) {
                delay(RECONNECT_DELAY_MS)
                continue
            }
            try {
                source.receive(packet)
                handlePacket(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (!running.get()) break
                Log.w(TAG, "UDP 接收异常: ${e.message}")
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val message = DiscoveryMessage.fromJson(text) ?: return
        val host = packet.address?.hostAddress ?: return
        val now = System.currentTimeMillis()
        when (message.type) {
            DiscoveryType.ANNOUNCE, DiscoveryType.OFFER -> {
                if (message.deviceId == deviceId) return
                val device = registry.onSeen(message, host, now)
                onDeviceSeen(device)
            }

            DiscoveryType.DISCOVER -> {
                if (limiter.allow(host, now)) sendOfferTo(host)
            }
        }
    }

    // ─────────────────────────── socket / 锁 ───────────────────────────

    private fun openSocket(): Boolean {
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

    private fun closeSocket() {
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

    private fun preferredInterface(): NetworkInterface? =
        runCatching { localIpv4Addresses().firstOrNull()?.let { NetworkInterface.getByInetAddress(it) } }
            .getOrNull()

    private fun acquireMulticastLock() {
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

    private fun releaseMulticastLock() {
        val held = multicastLock ?: return
        multicastLock = null
        runCatching { if (held.isHeld) held.release() }
            .onFailure { Log.w(TAG, "MulticastLock 释放失败: ${it.message}") }
    }

    /** 本机 IPv4 地址（排除回环 / 链路本地）。 */
    private fun localIpv4Addresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** 本机所在 /24 的其余 1..254 地址（用于兜底单播）。 */
    private fun slash24Hosts(): List<String> {
        val local = localIpv4Addresses().firstOrNull() ?: return emptyList()
        val bytes = local.address
        val self = bytes[3].toInt() and 0xFF
        return (1..254)
            .filter { it != self }
            .map { "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.$it" }
    }

    private companion object {
        const val TAG = "TransferDiscovery"
        const val LOCK_TAG = "lan-transfer-multicast"
        const val MAX_PACKET_BYTES = 4096
        const val RECEIVE_TIMEOUT_MS = 1_000
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
