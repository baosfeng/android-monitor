package com.bsfeng.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.nfc.NfcAdapter
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
        // WiFi + 网络（含 VPN + 移动数据）
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
        registerHotspot()
        // 截屏监听
        registerScreenshot()
        // 情景模式
        registerRingerMode()
        // 时区 + 语言
        registerLocale()
        // === 新增系统状态 ===
        registerDozeMode()
        registerShutdown()
        registerTimeChanged()
        registerDockEvent()
        registerUserSwitch()
        registerNfcState()
        registerDownloadComplete()
        registerScreenRefresh()
        // === 传感器 + 网络深度监控 ===
        SensorMonitor(context).register()
        NetworkMonitor(context).register()
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

    // ========== 新增系统状态广播 ==========

    private fun registerDozeMode() {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIdle = pm.isDeviceIdleMode
                val eventType = if (isIdle) EventType.DOZE_MODE_ENTERED else EventType.DOZE_MODE_EXITED
                val summary = if (isIdle) "手机进入省电待机模式" else "手机退出省电待机模式"
                EventCollector.emit(eventType, mapOf("is_idle" to isIdle.toString()), summary = summary)
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerShutdown() {
        val filter = IntentFilter(Intent.ACTION_SHUTDOWN)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                EventCollector.emit(EventType.DEVICE_SHUTDOWN, summary = "设备正在关机")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerTimeChanged() {
        val filter = IntentFilter(Intent.ACTION_TIME_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                EventCollector.emit(EventType.TIME_MANUALLY_CHANGED, mapOf(
                    "new_time" to System.currentTimeMillis().toString()
                ), summary = "系统时间被手动修改")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerDockEvent() {
        val filter = IntentFilter(Intent.ACTION_DOCK_EVENT)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1)
                val dockName = when (dockState) {
                    Intent.EXTRA_DOCK_STATE_DESK -> "桌面底座"
                    Intent.EXTRA_DOCK_STATE_CAR -> "车载底座"
                    Intent.EXTRA_DOCK_STATE_LE_DESK -> "轻量桌面底座"
                    Intent.EXTRA_DOCK_STATE_HE_DESK -> "重型桌面底座"
                    Intent.EXTRA_DOCK_STATE_UNDOCKED -> "已移除底座"
                    else -> "未知底座($dockState)"
                }
                val summary = "手机$dockName"
                EventCollector.emit(EventType.DOCK_EVENT, mapOf(
                    "dock_state" to dockState.toString(),
                    "dock_name" to dockName
                ), summary = summary)
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerUserSwitch() {
        val filter = IntentFilter("android.intent.action.USER_SWITCHED")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val userHandle = intent.getIntExtra("android.intent.extra.user_handle", -1)
                EventCollector.emit(EventType.USER_SWITCHED, mapOf(
                    "user_handle" to userHandle.toString()
                ), summary = "切换到其他用户")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerNfcState() {
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1)
                val isEnabled = state == NfcAdapter.STATE_ON
                val summary = if (isEnabled) "NFC 已开启" else "NFC 已关闭"
                EventCollector.emit(EventType.NFC_STATE_CHANGED, mapOf(
                    "enabled" to isEnabled.toString(),
                    "state_code" to state.toString()
                ), summary = summary)
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerDownloadComplete() {
        val filter = IntentFilter("android.intent.action.DOWNLOAD_COMPLETE")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra("extra_download_id", -1L)
                EventCollector.emit(EventType.DOWNLOAD_COMPLETED, mapOf(
                    "download_id" to id.toString()
                ), summary = "文件下载完成")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun registerScreenRefresh() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val config = ctx.resources.configuration
                EventCollector.emit(EventType.SCREEN_REFRESH_CHANGED, mapOf(
                    "density_dpi" to config.densityDpi.toString(),
                    "font_scale" to config.fontScale.toString()
                ), summary = "屏幕显示设置发生变化")
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), Context.RECEIVER_EXPORTED)
    }
}
