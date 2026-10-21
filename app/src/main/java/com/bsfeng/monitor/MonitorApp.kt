package com.bsfeng.monitor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bsfeng.monitor.autobook.AutoBookConfig
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.HealOutcome
import com.bsfeng.monitor.core.di.ServiceContainer
import com.bsfeng.monitor.record.RecordReceiver
import com.bsfeng.monitor.service.scheduleAlarmCheck
import com.bsfeng.monitor.skip.GkdSubscription
import com.bsfeng.monitor.skip.SkipConfig
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
        // 上次还原若中断（restore.marker 还在），先回滚到 pre-restore 包再打开任何数据库句柄
        healInterruptedRestore()
        // 跳过配置需在规则管理器创建（读取内置规则时间闸门）之前装载
        SkipConfig.init(this)
        // 自动记账开关（默认开启）：无障碍服务会在支付结果页上读取它
        AutoBookConfig.init(this)
        container = ServiceContainer(this)
        registerRecordReceiver()
        registerWifiNetworkCallback()
        // 每分钟保活检查 + 周期上报(设备注册/心跳),开机后由 AlarmReceiver 自行恢复调度
        scheduleAlarmCheck(this)
        checkSubscriptionUpdate()
    }

    /**
     * 启动自愈：还原文件被中途打断时，用 pre-restore 包把数据回滚，避免带着半新半旧的库启动。
     *
     * 无 marker 时只做一次文件存在性检查；回滚失败只记日志，不阻塞启动（用户仍可手动还原）。
     */
    private fun healInterruptedRestore() {
        runCatching {
            when (val outcome = BackupManager(this).healIfNeeded()) {
                is HealOutcome.RolledBack -> Log.w(TAG, "还原中断，已回滚到 pre-restore：" + outcome.preRestoreName)
                is HealOutcome.NoBackup -> Log.w(TAG, "还原中断但无可用回滚包：" + outcome.reason)
                is HealOutcome.Failed -> Log.w(TAG, "还原中断且回滚失败：" + outcome.reason)
                HealOutcome.NothingToDo -> Unit
            }
        }.onFailure { Log.w(TAG, "还原自愈检查失败", it) }
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
        // ContextCompat 内部按版本分支：API 33+ 用 RECEIVER_EXPORTED，旧版本忽略该标志
        ContextCompat.registerReceiver(this, recordReceiver, filters[0], ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, recordReceiver, filters[1], ContextCompat.RECEIVER_EXPORTED)
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
