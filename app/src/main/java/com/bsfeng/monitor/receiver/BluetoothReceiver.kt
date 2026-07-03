package com.bsfeng.monitor.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class BluetoothReceiver : BroadcastReceiver() {
    private var connectedTime: Long = 0L

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        ) ?: return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                connectedTime = System.currentTimeMillis()
                EventCollector.emit(EventType.BLUETOOTH_CONNECTED, mapOf(
                    "device_name" to (device.name ?: "未知设备"),
                    "device_address" to device.address,
                    "device_type" to deviceTypeToString(device.type)
                ))
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val duration = System.currentTimeMillis() - connectedTime
                EventCollector.emit(EventType.BLUETOOTH_DISCONNECTED, mapOf(
                    "device_name" to (device.name ?: "未知设备"),
                    "device_address" to device.address,
                    "connected_duration_ms" to duration.toString()
                ))
            }
        }
    }

    private fun deviceTypeToString(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }
}
