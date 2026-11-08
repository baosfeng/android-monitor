package com.bsfeng.monitor.record

/**
 * WiFi 连接状态机（纯逻辑，不碰 Android API）。
 *
 * [com.bsfeng.monitor.MonitorApp] 的 `NetworkCallback` 只做「取 capability / 取 SSID」的搬运，
 * 「该不该写 DISCONNECTED」全在这里判定，因此能纯 JVM 断言（[WifiConnectionTrackerTest]）。
 *
 * 关键约束：**onLost 不许再查 `getNetworkCapabilities`**。网络被移除时该调用常返回 null，
 * 原来的实现据此判 WiFi 会永久静默：不写 DISCONNECTED、也不复位 `lastConnected`，
 * 于是重连时 `!lastConnected` 恒为假，CONNECTED 一起丢，直到进程重启。
 * 现在 WiFi 身份由 [onCapabilities] 缓存，[onLost] 只依赖缓存 + `lastConnected`。
 */
internal class WifiConnectionTracker<N : Any>(
    private val onConnected: (ssid: String?) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var lastConnected = false

    /** 跳过注册后的首次状态同步回调（防止 force-stop 后误记录「连接 WiFi」）。 */
    private var isInitialSync = true

    /** 已知是 WIFI 承载的 network；onLost 时 `getNetworkCapabilities` 可能已经是 null。 */
    private val wifiNetworks = mutableSetOf<N>()

    /** 默认网络可用：`isWifi` 由调用方从 capabilities 判好。首次同步只更新状态、不写记录。 */
    fun onAvailable(network: N, isWifi: Boolean, ssid: () -> String?) {
        if (isWifi) {
            wifiNetworks += network
            if (!lastConnected) {
                lastConnected = true
                val firstSync = isInitialSync
                isInitialSync = false
                if (!firstSync) onConnected(ssid())
                return
            }
        }
        isInitialSync = false
    }

    /** capabilities 变化：这是「这条 network 是不是 WiFi」唯一可靠的来源（此时网络一定还在）。 */
    fun onCapabilities(network: N, isWifi: Boolean) {
        if (isWifi) wifiNetworks += network else wifiNetworks -= network
    }

    /**
     * 默认网络丢失。
     *
     * 只认 `lastConnected`：注册的是 default network callback，此刻丢的就是我们跟踪的那条连接。
     * 缓存里有这条 network 只能用来确认身份，不能作为「要不要写事件的必要条件」——
     * 那正是原来断开事件永久静默的原因。
     */
    fun onLost(network: N) {
        wifiNetworks -= network
        if (lastConnected) {
            lastConnected = false
            onDisconnected()
        }
        isInitialSync = false
    }
}
