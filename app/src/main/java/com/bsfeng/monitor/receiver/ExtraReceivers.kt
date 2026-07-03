package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class HeadsetReceiver : BroadcastReceiver() {
    private var pluggedTime: Long = 0L

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra("state", 0)
        val hasMic = intent.getIntExtra("microphone", 0)
        val deviceName = intent.getStringExtra("name") ?: ""

        when (state) {
            1 -> { // 插入
                pluggedTime = System.currentTimeMillis()
                EventCollector.emit(EventType.HEADSET_PLUGGED, mapOf(
                    "type" to "wired",
                    "has_microphone" to (hasMic == 1).toString(),
                    "device_name" to deviceName
                ))
            }
            0 -> { // 拔出
                val duration = System.currentTimeMillis() - pluggedTime
                EventCollector.emit(EventType.HEADSET_UNPLUGGED, mapOf(
                    "type" to "wired",
                    "device_name" to deviceName,
                    "plugged_duration_ms" to duration.toString()
                ))
            }
        }
    }
}

class AirplaneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra("state", false)
        EventCollector.emit(EventType.AIRPLANE_MODE_CHANGED, mapOf(
            "enabled" to enabled.toString()
        ))
    }
}

class PowerSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val enabled = pm.isPowerSaveMode
        EventCollector.emit(EventType.POWER_SAVE_MODE_CHANGED, mapOf(
            "enabled" to enabled.toString()
        ))
    }
}

@Suppress("DEPRECATION")
class StorageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DEVICE_STORAGE_LOW -> {
                val info = java.io.File(context.filesDir.parentFile?.absolutePath ?: "/data")
                val total = info.totalSpace / (1024 * 1024)
                val free = info.freeSpace / (1024 * 1024)
                val used = total - free
                EventCollector.emit(EventType.STORAGE_LOW, mapOf(
                    "storage_percent_used" to (used * 100 / total).toString(),
                    "available_mb" to free.toString(),
                    "total_mb" to total.toString()
                ))
            }
            Intent.ACTION_DEVICE_STORAGE_OK -> {
                EventCollector.emit(EventType.STORAGE_OK)
            }
        }
    }
}

class UsbReceiver : BroadcastReceiver() {
    private var connectedTime: Long = 0L

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                connectedTime = System.currentTimeMillis()
                EventCollector.emit(EventType.CONNECTED_TO_PC)
            }
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val duration = System.currentTimeMillis() - connectedTime
                EventCollector.emit(EventType.DISCONNECTED_FROM_PC, mapOf(
                    "connected_duration_ms" to duration.toString()
                ))
            }
        }
    }
}
