package com.bsfeng.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.PowerManager
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.receiver.*
import java.util.Locale

class ReceiverRegistrar(private val context: Context) {

    private val screenReceiver = ScreenStateReceiver()
    private val batteryReceiver = BatteryReceiver()
    private val wifiReceiver = WifiReceiver()
    private val bluetoothReceiver = BluetoothReceiver()
    private val headsetReceiver = HeadsetReceiver()
    private val packageReceiver = PackageReceiver()
    private val airplaneReceiver = AirplaneReceiver()
    private val powerSaveReceiver = PowerSaveReceiver()
    private val storageReceiver = StorageReceiver()
    private val usbReceiver = UsbReceiver()
    private val callReceiver = CallStateReceiver()
    private val volumeObserver = VolumeChangeObserver(context)
    private val screenshotObserver = ScreenshotObserver()

    fun registerAll() {
        // 屏幕状态
        registerScreen()
        // 电池
        registerBattery()
        // WiFi + 网络（含 VPN + 移动数据）— 通过 ConnectivityManager.NetworkCallback 实现
        // 蓝牙
        registerBluetooth()
        // 耳机
        registerHeadset()
        // 应用安装/卸载
        registerPackage()
        // 飞行模式
        registerAirplane()
        // 省电模式
        registerPowerSave()
        // 存储空间
        registerStorage()
        // USB
        registerUsb()
        // 通话
        registerCall()
        // 音量
        volumeObserver.register(context.contentResolver)
        // 热点 + 移动数据
        registerNetworkCallback()
        // 热点状态广播
        registerHotspot()
        // 截屏监听
        registerScreenshot()
        // 情景模式
        registerRingerMode()
        // 时区 + 语言
        registerLocale()
    }

    private fun registerScreen() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerBattery() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerBluetooth() {
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (_: SecurityException) {
            // 没有 BLUETOOTH_CONNECT 权限
        }
    }

    private fun registerHeadset() {
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerPackage() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerAirplane() {
        val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        context.registerReceiver(airplaneReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerPowerSave() {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        context.registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    @Suppress("DEPRECATION")
    private fun registerStorage() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        }
        context.registerReceiver(storageReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerUsb() {
        val filter = IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerCall() {
        val filter = IntentFilter("android.intent.action.PHONE_STATE")
        try {
            context.registerReceiver(callReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (_: SecurityException) {
            // 没有 READ_PHONE_STATE 权限
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiReceiver.registerConnectivityCallback(context, connectivityManager)
    }

    private fun registerHotspot() {
        val filter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        try {
            context.registerReceiver(wifiReceiver, filter,
                Context.RECEIVER_EXPORTED)
        } catch (_: Exception) {
            // 部分 Android 版本不允许普通应用注册此广播
        }
    }

    private fun registerScreenshot() {
        screenshotObserver.start()
    }

    private fun registerLocale() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        EventCollector.emit(EventType.TIMEZONE_CHANGED, mapOf(
                            "current_timezone" to java.util.TimeZone.getDefault().id
                        ))
                    }
                    Intent.ACTION_LOCALE_CHANGED -> {
                        EventCollector.emit(EventType.LOCALE_CHANGED, mapOf(
                            "current_locale" to Locale.getDefault().toLanguageTag()
                        ))
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerRingerMode() {
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val ringerMode = intent.getIntExtra(
                    AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL
                )
                val modeStr = when (ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> "unknown"
                }
                EventCollector.emit(EventType.RINGER_MODE_CHANGED, mapOf(
                    "ringer_mode" to modeStr,
                    "ringer_mode_code" to ringerMode.toString()
                ))
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }
}
