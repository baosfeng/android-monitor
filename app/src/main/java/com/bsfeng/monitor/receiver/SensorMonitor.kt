package com.bsfeng.monitor.receiver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

/**
 * 传感器监控 — 光线、接近、加速度计
 * 低功耗，仅在值变化时记录，不轮询
 */
class SensorMonitor(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastLux: Float = -1f
    private var lastProximity: Boolean = false
    private var lastMovementTimeMs: Long = 0L
    private val MOVEMENT_DEBOUNCE_MS = 30_000L  // 设备移动 30 秒记一次

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> handleLight(event)
                Sensor.TYPE_PROXIMITY -> handleProximity(event)
                Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun register() {
        registerSensor(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL)
        registerSensor(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL)
        registerSensor(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_UI)
    }

    fun unregister() {
        sensorManager.unregisterListener(listener)
    }

    private fun registerSensor(type: Int, rate: Int) {
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(listener, it, rate)
        }
    }

    private fun handleLight(event: SensorEvent) {
        val lux = event.values[0]
        // 变化超过 50 lux 才记录
        if (lastLux < 0 || kotlin.math.abs(lux - lastLux) > 50f) {
            val description = when {
                lux < 5 -> "环境极暗"
                lux < 50 -> "环境较暗"
                lux < 200 -> "环境正常"
                lux < 1000 -> "环境明亮"
                else -> "环境极亮"
            }
            val summary = "$description（${lux.toInt()} lux）"
            EventCollector.emit(EventType.SENSOR_LIGHT_CHANGED, mapOf(
                "lux" to lux.toString(),
                "description" to description
            ), summary = summary)
            lastLux = lux
        }
    }

    private fun handleProximity(event: SensorEvent) {
        val isNear = event.values[0] < event.sensor.maximumRange
        if (isNear != lastProximity) {
            val summary = if (isNear) "手机贴近物体（可能在口袋里）" else "手机远离物体"
            EventCollector.emit(EventType.SENSOR_PROXIMITY_CHANGED, mapOf(
                "is_near" to isNear.toString(),
                "value" to event.values[0].toString()
            ), summary = summary)
            lastProximity = isNear
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastMovementTimeMs < MOVEMENT_DEBOUNCE_MS) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
        val isMoving = magnitude > 12.0  // 静止时约 9.8（重力加速度）

        if (isMoving) {
            val summary = "设备正在移动"
            EventCollector.emit(EventType.DEVICE_MOVEMENT_CHANGED, mapOf(
                "is_moving" to "true",
                "magnitude" to String.format("%.1f", magnitude)
            ), summary = summary)
            lastMovementTimeMs = now
        }
    }
}
