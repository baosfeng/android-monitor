package com.bsfeng.monitor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.bsfeng.monitor.core.di.ServiceContainer
import com.bsfeng.monitor.record.RecordReceiver
import com.bsfeng.monitor.service.scheduleAlarmCheck
import com.bsfeng.monitor.skip.GkdSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口 — 初始化 DI 容器并注册系统广播接收器。
 */
class MonitorApp : Application() {

    lateinit var container: ServiceContainer
        private set

    private lateinit var recordReceiver: RecordReceiver

    /** 应用级协程作用域（一次性后台任务：订阅检查等） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = ServiceContainer(this)
        registerRecordReceiver()
        registerWifiNetworkCallback()
        // 每分钟保活检查 + 周期上报(设备注册/心跳),开机后由 AlarmReceiver 自行恢复调度
        scheduleAlarmCheck(this)
        checkSubscriptionUpdate()
    }

    /** 启动时后台检查一次规则订阅更新（未订阅或未到周期时立即返回，不发请求） */
    private fun checkSubscriptionUpdate() {
        appScope.launch {
            try {
                GkdSubscription.autoCheck(container.skipRuleManager)
            } catch (e: Exception) {
                Log.w(TAG, "订阅自动检查失败", e)
            }
        }
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
            /** 跳过注册后的首次状态同步回调（防止 force-stop 后误记录"连接 WiFi"） */
            private var isInitialSync = true

            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !lastConnected) {
                    lastConnected = true
                    if (isInitialSync) {
                        isInitialSync = false
                        return  // 首次同步仅更新状态，不写记录
                    }
                    val ssid = getWifiSsid()
                    container.recordCollector.onWifiChanged("CONNECTED", ssid, System.currentTimeMillis())
                }
                isInitialSync = false
            }

            override fun onLost(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && lastConnected) {
                    lastConnected = false
                    container.recordCollector.onWifiChanged("DISCONNECTED", null, System.currentTimeMillis())
                }
                isInitialSync = false
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
        private const val TAG = "MonitorApp"

        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
