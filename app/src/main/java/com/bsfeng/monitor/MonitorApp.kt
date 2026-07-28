package com.bsfeng.monitor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.bsfeng.monitor.core.di.ServiceContainer
import com.bsfeng.monitor.record.RecordReceiver

/**
 * 应用入口 — 初始化 DI 容器并注册系统广播接收器。
 */
class MonitorApp : Application() {

    lateinit var container: ServiceContainer
        private set

    private lateinit var recordReceiver: RecordReceiver

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = ServiceContainer(this)
        registerRecordReceiver()
        registerWifiNetworkCallback()
    }

    private fun registerRecordReceiver() {
        recordReceiver = RecordReceiver()
        val filters = RecordReceiver.createFilters()
        registerReceiver(recordReceiver, filters[0], Context.RECEIVER_EXPORTED)
        registerReceiver(recordReceiver, filters[1], Context.RECEIVER_EXPORTED)
    }

    /**
     * 使用 ConnectivityManager.NetworkCallback 监听 WiFi 实际连接状态，
     * 替代 WIFI_STATE_CHANGED_ACTION（后者监听的是 WiFi 硬件开关，在 HyperOS 上可能频繁误触发）。
     */
    private fun registerWifiNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiCallback = object : ConnectivityManager.NetworkCallback() {
            private var lastConnected = false

            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !lastConnected) {
                    lastConnected = true
                    val ssid = getWifiSsid()
                    container.recordCollector.onWifiChanged("CONNECTED", ssid, System.currentTimeMillis())
                }
            }

            override fun onLost(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && lastConnected) {
                    lastConnected = false
                    container.recordCollector.onWifiChanged("DISCONNECTED", null, System.currentTimeMillis())
                }
            }

            private fun getWifiSsid(): String? {
                return try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiManager.connectionInfo?.ssid?.trim('"')
                } catch (_: Exception) { null }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(wifiCallback)
    }

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
