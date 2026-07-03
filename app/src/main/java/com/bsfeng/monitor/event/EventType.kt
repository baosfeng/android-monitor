package com.bsfeng.monitor.event

enum class EventType(val description: String) {
    APP_FOREGROUND("前台 App 切换"),
    ACTIVITY_CHANGE("Activity 级界面切换"),
    SCREEN_ON("屏幕亮起"),
    SCREEN_OFF("屏幕熄灭"),
    UNLOCK_SUCCESS("解锁成功"),
    UNLOCK_FAILED("解锁失败"),
    CHARGING_STARTED("开始充电"),
    CHARGING_STOPPED("停止充电"),
    WIFI_CONNECTED("WiFi 连接"),
    WIFI_DISCONNECTED("WiFi 断开"),
    HEADSET_PLUGGED("耳机插入"),
    HEADSET_UNPLUGGED("耳机拔出"),
    APP_INSTALLED("应用安装"),
    APP_UNINSTALLED("应用卸载"),
    NOTIFICATION_RECEIVED("通知到达"),
    NOTIFICATION_REMOVED("通知移除"),
    BATTERY_CHANGED("电量变化"),
    RINGER_MODE_CHANGED("情景模式变化"),
    CONNECTED_TO_PC("连接电脑"),
    DISCONNECTED_FROM_PC("断开电脑"),
    BLUETOOTH_CONNECTED("蓝牙设备连接"),
    BLUETOOTH_DISCONNECTED("蓝牙设备断开"),
    AIRPLANE_MODE_CHANGED("飞行模式切换"),
    MOBILE_DATA_CHANGED("移动数据切换"),
    POWER_SAVE_MODE_CHANGED("省电模式切换"),
    SCREENSHOT_TAKEN("截屏事件"),
    VPN_CONNECTED("VPN 连接"),
    VPN_DISCONNECTED("VPN 断开"),
    APP_CRASHED("App 崩溃"),
    STORAGE_LOW("存储不足"),
    STORAGE_OK("存储恢复"),
    HOTSPOT_CHANGED("热点切换"),
    VOLUME_CHANGED("音量变化"),
    TIMEZONE_CHANGED("时区变化"),
    LOCALE_CHANGED("语言区域变化"),
    CALL_STATE_CHANGED("通话状态变化"),
    SCREEN_CONTENT_DETECTED("界面内容识别");

    companion object {
        private val map = entries.associateBy { it.name }
        fun fromString(name: String): EventType? = map[name]
        /** 将事件类型名称转为中文描述，找不到时返回原名称 */
        fun getChineseName(name: String): String =
            map[name]?.description ?: name
    }
}
