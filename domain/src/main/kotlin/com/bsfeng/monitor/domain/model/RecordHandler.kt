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
        action in setOf("OFF", "UNLOCKED", "AD_SKIPPED")
    override fun createLabel(action: String, detail: Map<String, String>?): String = when (action) {
        "OFF" -> "屏幕熄灭"; "UNLOCKED" -> "解锁成功"; "AD_SKIPPED" -> {
            detail?.get("app_name")?.let { "跳过 $it 广告" } ?: "跳过广告"
        }; else -> "锁屏"
    }
    override fun needsDuration(action: String) = false
}

class ChargingHandler : RecordHandler {
    override val type = RecordType.CHARGING
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("STARTED", "STOPPED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val chargeType = detail?.get("type") ?: ""
        val level = detail?.get("level") ?: ""
        val levelSuffix = if (level.isNotEmpty()) "（电量 ${level}%）" else ""
        return when (action) {
            "STARTED" -> {
                val prefix = if (chargeType.isNotEmpty()) chargeType else ""
                "${prefix}充电$levelSuffix"
            }
            else -> "停止充电$levelSuffix"
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

// PAGE 页面轨迹（指纹档）：label 只拼应用名 + 指纹短串，detail 里没有任何页面文本
class PageHandler : RecordHandler {
    override val type = RecordType.PAGE
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("ENTER", "CHANGED")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        val app = detail?.get("app_name") ?: detail?.get("package_name") ?: ""
        // 页面标题优先（仅本地采集，缺了就沿用指纹短码，绝不编造标题）。
        // 标题等于应用名时不拼「抖音 · 抖音」：自绘界面（如抖音）只给得出窗口标题，
        // 这时的标题没有页面含义，直接显示应用名即可，比页面指纹更好读。
        val title = detail?.get("page_title")?.takeIf { it.isNotBlank() }
        if (title != null) {
            if (app.isEmpty()) return title
            return if (title == app) app else "$app · $title"
        }
        // 只用前 4 位做「页面编号」：8 位裸 hash 用户读不出信息。
        // detail 里的 page_key 保持完整 8 位（去重与聚合仍需要全量指纹）。
        val short = detail?.get("page_key")?.take(SHORT_KEY_LENGTH) ?: ""
        val page = if (short.isEmpty()) "" else "页面 #$short"
        return when {
            app.isEmpty() && page.isEmpty() -> "页面"
            app.isEmpty() -> page
            page.isEmpty() -> app
            else -> "$app · $page"
        }
    }
    override fun needsDuration(action: String) = false

    companion object {
        /** label 里展示的指纹短码长度（4 位 hex，碰撞率 1/65536，可接受）。 */
        const val SHORT_KEY_LENGTH = 4
    }
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

// LOCATION Handler：label 优先中文地址（逆地理编码结果），失败回落经纬度
class LocationHandler : RecordHandler {
    override val type = RecordType.LOCATION
    override fun validate(action: String, detail: Map<String, String>?): Boolean =
        action in setOf("COORDINATE", "ENTER", "EXIT")
    override fun createLabel(action: String, detail: Map<String, String>?): String {
        return detail?.get("address")?.takeIf { it.isNotBlank() }
            ?: detail?.let { d ->
                val lat = d["lat"]
                val lng = d["lng"]
                if (lat != null && lng != null) "$lat,$lng" else null
            }
            ?: "位置"
    }
    override fun needsDuration(action: String) = false
}
