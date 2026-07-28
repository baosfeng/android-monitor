package com.bsfeng.monitor.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.util.AppUtils

/**
 * 系统广播接收器 — 采集各系统状态变化事件。
 *
 * 所有广播在 [createFilter] 中注册，统一回调 [onReceive] 方法。
 */
class RecordReceiver : BroadcastReceiver() {

    private val collector: RecordCollector
        get() = MonitorApp.instance.container.recordCollector

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> collector.onScreenChanged("OFF", now)
            Intent.ACTION_USER_PRESENT -> collector.onScreenChanged("UNLOCKED", now)

            Intent.ACTION_POWER_CONNECTED -> {
                val type = getChargeType(intent)
                collector.onChargingChanged("STARTED", type, now)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                collector.onChargingChanged("STOPPED", null, now)
            }

            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                collector.onBatteryChanged(level, plugged, now)
            }
            Intent.ACTION_BATTERY_LOW -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                collector.onBatteryLow(level, now)
            }
            Intent.ACTION_BATTERY_OKAY -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                collector.onBatteryOkay(level, now)
            }

            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", 0)
                collector.onAudioChanged(if (state == 1) "PLUGGED" else "UNPLUGGED", null, now)
            }

            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                collector.onPowerSaveChanged(pm.isPowerSaveMode, now)
            }

            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val enabled = intent.getBooleanExtra("state", false)
                collector.onAirplaneChanged(enabled, now)
            }

            Intent.ACTION_PACKAGE_ADDED -> {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val appName = AppUtils.getAppName(pkg)
                val replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                collector.onPackageChanged(if (replaced) "REPLACED" else "ADDED", pkg, appName, now)
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (!replaced) {
                    collector.onPackageChanged("REMOVED", pkg, null, now)
                }
            }

            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val action = when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> "RINGING"
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> "OFFHOOK"
                    else -> "IDLE"
                }
                collector.onPhoneStateChanged(action, number, now)
            }
        }
    }

    private fun getChargeType(intent: Intent): String {
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "UNKNOWN"
        }
    }

    companion object {
        fun createFilters(): List<IntentFilter> = listOf(
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        )
    }
}
