package com.bsfeng.monitor.receiver

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

/**
 * 网络深度监控 — 网络类型切换、信号强度变化
 * 需要 READ_PHONE_STATE 权限
 */
class NetworkMonitor(private val context: Context) {

    private var lastNetworkType: String = ""
    private var lastSignalLevel: Int = -1

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            handleSignalChange(signalStrength)
        }

        @Deprecated("旧 API 兼容")
        override fun onDataActivity(direction: Int) {}

        @Deprecated("旧 API 兼容")
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            handleNetworkTypeChange(networkType)
        }
    }

    fun register() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
        } catch (_: SecurityException) {
            Log.w("NetworkMonitor", "缺少 READ_PHONE_STATE 权限，跳过网络监控")
        }
    }

    fun unregister() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: SecurityException) {}
    }

    private fun handleSignalChange(signal: SignalStrength) {
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signal.level  // API 29+ 0-4
        } else {
            @Suppress("DEPRECATION")
            val gsm = signal.gsmSignalStrength ?: return
            if (gsm == 99) return
            gsm / 31 * 4
        }

        if (level == lastSignalLevel) return
        lastSignalLevel = level

        val descriptions = listOf("无信号", "信号弱", "信号一般", "信号良好", "信号强")
        val desc = descriptions.getOrElse(level) { "未知" }
        val summary = "信号强度变为「$desc」"
        EventCollector.emit(EventType.SIGNAL_STRENGTH_CHANGED, mapOf(
            "level" to level.toString(),
            "description" to desc
        ), summary = summary)
    }

    private fun handleNetworkTypeChange(networkType: Int) {
        val typeName = getNetworkTypeName(networkType)
        if (typeName == lastNetworkType) return
        lastNetworkType = typeName

        val summary = "网络切换为 $typeName"
        EventCollector.emit(EventType.NETWORK_TYPE_CHANGED, mapOf(
            "network_type" to typeName,
            "network_type_code" to networkType.toString()
        ), summary = summary)
    }

    private fun getNetworkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G"
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi通话"
            else -> "未知($type)"
        }
    }
}
