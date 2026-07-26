package com.bsfeng.monitor.domain.model

interface RecordHandler {
    val type: RecordType
    fun validate(action: String, detail: Map<String, String>?): Boolean
    fun createLabel(action: String, detail: Map<String, String>?): String
    fun needsDuration(action: String): Boolean
}

// ===== 各类型 Handler 实现 =====

class ScreenHandler : RecordHandler {
    override val type = RecordType.SCREEN
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("ON", "OFF", "UNLOCKED", "LOCKED")
    override fun createLabel(action: String, detail: Map<String, String>?): String = when (action) {
        "ON" -> "屏幕亮起"; "OFF" -> "屏幕熄灭"; "UNLOCKED" -> "解锁成功"; else -> "锁屏"
    }
    override fun needsDuration(action: String) = false
}

class ChargingHandler : RecordHandler {
    override val type = RecordType.CHARGING
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("STARTED", "STOPPED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val chargeType = detail?.get("type") ?: ""
        return when (action) {
            "STARTED" -> if (chargeType.isNotEmpty()) "${chargeType}充电" else "开始充电"
            else -> "停止充电"
        }
    }
    override fun needsDuration(action: String) = false
}

class AppUsageHandler : RecordHandler {
    override val type = RecordType.APP_USAGE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("OPENED", "CLOSED", "FOREGROUND")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val appName = detail?.get("app_name") ?: ""
        return when (action) {
            "OPENED" -> "打开${appName}"; "CLOSED" -> "关闭${appName}"
            else -> appName
        }
    }
    override fun needsDuration(action: String) = action == "CLOSED"
}

class WifiHandler : RecordHandler {
    override val type = RecordType.WIFI
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("CONNECTED", "DISCONNECTED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val ssid = detail?.get("ssid") ?: ""
        return when (action) {
            "CONNECTED" -> "连接 WiFi${if (ssid.isNotEmpty()) "：$ssid" else ""}"
            else -> "断开 WiFi${if (ssid.isNotEmpty()) "：$ssid" else ""}"
        }
    }
    override fun needsDuration(action: String) = false
}

class AudioHandler : RecordHandler {
    override val type = RecordType.AUDIO
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("PLUGGED", "UNPLUGGED", "BLUETOOTH_CONNECTED", "BLUETOOTH_DISCONNECTED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val device = detail?.get("device") ?: ""
        return when (action) {
            "PLUGGED" -> "插入耳机"
            "UNPLUGGED" -> "拔出耳机"
            "BLUETOOTH_CONNECTED" -> "蓝牙连接${if (device.isNotEmpty()) "：$device" else ""}"
            else -> "蓝牙断开${if (device.isNotEmpty()) "：$device" else ""}"
        }
    }
    override fun needsDuration(action: String) = false
}

class BatteryHandler : RecordHandler {
    override val type = RecordType.BATTERY
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("CHANGED", "LOW", "OKAY", "SAVE_MODE_ON", "SAVE_MODE_OFF")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val level = detail?.get("level") ?: ""
        return when (action) {
            "CHANGED" -> "电量 ${level}%"
            "LOW" -> "低电量警告（${level}%）"
            "OKAY" -> "电量恢复正常（${level}%）"
            "SAVE_MODE_ON" -> "开启省电模式"
            else -> "关闭省电模式"
        }
    }
    override fun needsDuration(action: String) = false
}

class PackageHandler : RecordHandler {
    override val type = RecordType.PACKAGE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("ADDED", "REMOVED", "REPLACED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val appName = detail?.get("app_name") ?: detail?.get("package_name") ?: ""
        return when (action) {
            "ADDED" -> "安装了 $appName"
            "REMOVED" -> "卸载了 $appName"
            else -> "更新了 $appName"
        }
    }
    override fun needsDuration(action: String) = false
}

class PowerSaveHandler : RecordHandler {
    override val type = RecordType.POWER_SAVE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("ON", "OFF")
    override fun createLabel(action: String, detail: Map<String, String>?): String =
        if (action == "ON") "开启省电模式" else "关闭省电模式"
    override fun needsDuration(action: String) = false
}

class AirplaneHandler : RecordHandler {
    override val type = RecordType.AIRPLANE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("ON", "OFF")
    override fun createLabel(action: String, detail: Map<String, String>?): String =
        if (action == "ON") "开启飞行模式" else "关闭飞行模式"
    override fun needsDuration(action: String) = false
}

class PhoneStateHandler : RecordHandler {
    override val type = RecordType.PHONE_STATE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("RINGING", "OFFHOOK", "IDLE")
    override fun createLabel(action: String, detail: Map<String, String>?): String = when (action) {
        "RINGING" -> "来电"
        "OFFHOOK" -> "通话中"
        else -> "挂断"
    }
    override fun needsDuration(action: String) = action == "OFFHOOK"
}

// LOCATION Handler 作为预留，第二期实现完整逻辑
class LocationHandler : RecordHandler {
    override val type = RecordType.LOCATION
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("COORDINATE", "ENTER", "EXIT")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        return detail?.get("address")
            ?: detail?.let { d ->
                val lat = d["lat"]
                val lng = d["lng"]
                if (lat != null && lng != null) "$lat,$lng" else null
            }
            ?: "位置"
    }
    override fun needsDuration(action: String) = false
}
