package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class WifiReceiver : BroadcastReceiver() {
    private var lastWifiState = false
    private var lastVpnState = false
    private var lastMobileDataState = false
    private var lastHotspotState = false

    private var wifiConnectedTime: Long = 0L
    private var vpnConnectedTime: Long = 0L

    companion object {
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val WIFI_AP_STATE_DISABLED = 11
        /** 通过反射检测 Wifi 热点是否开启（隐藏 API fallback） */
        fun isWifiApEnabled(context: Context): Boolean {
            try {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                return method.invoke(wifiManager) as Boolean
            } catch (_: Exception) {
                return false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIFI_AP_STATE_CHANGED -> {
                val apState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED)
                val enabled = apState == WIFI_AP_STATE_ENABLED
                if (enabled != lastHotspotState) {
                    lastHotspotState = enabled
                    EventCollector.emit(EventType.HOTSPOT_CHANGED, mapOf(
                        "enabled" to enabled.toString(),
                        "ap_state" to apState.toString()
                    ))
                }
            }
        }
    }

    fun registerConnectivityCallback(context: Context, connectivityManager: ConnectivityManager) {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    // Wi-Fi 已连接
                    if (!lastWifiState) {
                        wifiConnectedTime = System.currentTimeMillis()
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager.connectionInfo
                        EventCollector.emit(EventType.WIFI_CONNECTED, mapOf(
                            "ssid" to (wifiInfo.ssid?.trim('"') ?: ""),
                            "bssid" to (wifiInfo.bssid ?: ""),
                            "signal_strength" to wifiInfo.rssi.toString()
                        ))
                        lastWifiState = true
                    }
                }
            }

            override fun onLost(network: Network) {
                if (lastWifiState) {
                    // 检查是否失去了 Wi-Fi 连接
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        // 如果无法获取能力，可能是网络已断开，当作 Wi-Fi 断开处理
                        val duration = System.currentTimeMillis() - wifiConnectedTime
                        EventCollector.emit(EventType.WIFI_DISCONNECTED, mapOf(
                            "connected_duration_ms" to duration.toString()
                        ))
                        lastWifiState = false
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                // VPN 检测
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (vpn != lastVpnState) {
                    if (vpn) {
                        vpnConnectedTime = System.currentTimeMillis()
                        EventCollector.emit(EventType.VPN_CONNECTED)
                    } else {
                        val duration = System.currentTimeMillis() - vpnConnectedTime
                        EventCollector.emit(EventType.VPN_DISCONNECTED, mapOf(
                            "connected_duration_ms" to duration.toString()
                        ))
                    }
                    lastVpnState = vpn
                }

                // 移动数据检测
                val mobileData = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (mobileData != lastMobileDataState) {
                    EventCollector.emit(EventType.MOBILE_DATA_CHANGED, mapOf(
                        "enabled" to mobileData.toString()
                    ))
                    lastMobileDataState = mobileData
                }

                // 热点检测：当 WiFi 站模式断开且没有 VPN，但 WifiManager 显示 AP 模式开启
                val wifiSta = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hotspotOn = !wifiSta && isWifiApEnabled(context)
                if (hotspotOn != lastHotspotState) {
                    lastHotspotState = hotspotOn
                    EventCollector.emit(EventType.HOTSPOT_CHANGED, mapOf(
                        "enabled" to hotspotOn.toString()
                    ))
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }
}
