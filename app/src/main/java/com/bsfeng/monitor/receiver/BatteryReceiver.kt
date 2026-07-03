package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val level = getBatteryLevel(intent)
                EventCollector.emit(EventType.CHARGING_STARTED, mapOf(
                    "battery_level" to level.toString()
                ))
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                EventCollector.emit(EventType.CHARGING_STOPPED)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)

                val pct = level * 100 / scale
                EventCollector.emit(EventType.BATTERY_CHANGED, mapOf(
                    "battery_level" to pct.toString(),
                    "is_charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL).toString(),
                    "temperature_celsius" to temperature.toString(),
                    "voltage_mv" to voltage.toString(),
                    "health" to healthToString(health)
                ))
            }
        }
    }

    private fun getBatteryLevel(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return level * 100 / scale
    }

    private fun healthToString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        else -> "unknown"
    }
}
