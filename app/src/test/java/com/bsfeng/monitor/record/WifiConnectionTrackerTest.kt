package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WiFi 连接状态机（[WifiConnectionTracker]）。
 *
 * 回归重点是 **R3：断开 / 重连事件永久静默**。原实现在 `onLost` 里查
 * `getNetworkCapabilities(network)?.hasTransport(WIFI)`，网络已移除时该调用常返回 null，
 * 于是既不写 DISCONNECTED、也不复位 `lastConnected`；重连时 `!lastConnected` 恒为假，
 * CONNECTED 同样丢，直到进程重启。
 *
 * 纯 JVM：network 用 String 充当，SSID 用 lambda 注入，不碰 Android API。
 */
class WifiConnectionTrackerTest {

    private val events = mutableListOf<String>()

    private val tracker = WifiConnectionTracker<String>(
        onConnected = { ssid -> events += "CONNECTED:" + (ssid ?: "-") },
        onDisconnected = { events += "DISCONNECTED" },
    )

    private val wifi = "wifi-network"
    private val cellular = "cellular-network"

    @Test
    fun `注册后的首次同步只更新状态 不写连接事件`() {
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }

        assertTrue("首次同步不该写事件：$events", events.isEmpty())
    }

    @Test
    fun `断开后重连产生 DISCONNECTED 加 CONNECTED 两条事件`() {
        // 开机后首同步：WiFi 已连着，只更新状态
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }

        // 飞行模式：网络被移除。旧实现在这里因为取不到 capabilities 而静默
        tracker.onLost(wifi)
        assertEquals(listOf("DISCONNECTED"), events)

        // 关掉飞行模式：WiFi 重连
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }
        assertEquals(
            "断开必须复位 lastConnected，否则重连的 CONNECTED 会被当成重复信号丢掉",
            listOf("DISCONNECTED", "CONNECTED:MyWifi"),
            events,
        )
    }

    @Test
    fun `onLost 不需要 capabilities 缓存也能判定 WiFi`() {
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }
        // 缓存里没有这条 network（注册后没等到 onCapabilitiesChanged）也必须落 DISCONNECTED
        tracker.onLost("never-seen-network")

        assertEquals(listOf("DISCONNECTED"), events)
    }

    @Test
    fun `未连接时 onLost 不写断开事件`() {
        // 手机没连过 WiFi，移动数据默认网络丢失 → 与 WiFi 事件无关
        tracker.onAvailable(cellular, isWifi = false) { null }
        tracker.onLost(cellular)

        assertTrue("非 WiFi 的上下文不得产生 WiFi 事件：$events", events.isEmpty())
    }

    @Test
    fun `WiFi 断掉后切移动数据 不重复写断开事件`() {
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }
        tracker.onLost(wifi)
        // 系统移除 WiFi 后默认网络换成移动数据：这条 onAvailable 不是 WiFi，不得再写事件
        tracker.onAvailable(cellular, isWifi = false) { null }
        tracker.onLost(cellular)

        assertEquals(listOf("DISCONNECTED"), events)
    }

    @Test
    fun `capabilities 变化只更新缓存 不产生事件`() {
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }
        tracker.onCapabilities(wifi, isWifi = true)
        tracker.onCapabilities(wifi, isWifi = false)

        assertTrue("capabilities 回调只用于缓存身份：$events", events.isEmpty())
    }

    @Test
    fun `重复 onAvailable 同一条网络不重复写连接事件`() {
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }   // 首同步
        tracker.onAvailable(wifi, isWifi = true) { "MyWifi" }   // 已连着，仍是同一状态

        assertTrue("没断开就不该出现第二次 CONNECTED：$events", events.isEmpty())
    }
}
