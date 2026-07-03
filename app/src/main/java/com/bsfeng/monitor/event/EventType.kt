package com.bsfeng.monitor.event

enum class EventType(val description: String) {
    // ===== App & 界面 =====
    APP_FOREGROUND("前台 App 切换"),
    ACTIVITY_CHANGE("Activity 级界面切换"),
    SCREEN_CONTENT_DETECTED("界面内容识别"),

    // ===== 点击 & 交互 =====
    VIEW_CLICKED("点击交互"),
    VIEW_LONG_CLICKED("长按交互"),
    VIEW_SCROLLED("页面滚动"),
    VIEW_SELECTED("选中变化"),
    KEYBOARD_SHOWN("键盘弹出"),
    KEYBOARD_HIDDEN("键盘收起"),
    PIP_MODE_CHANGED("画中画模式"),
    SCREEN_ROTATION_CHANGED("屏幕旋转"),

    // ===== 屏幕 =====
    SCREEN_ON("屏幕亮起"),
    SCREEN_OFF("屏幕熄灭"),
    UNLOCK_SUCCESS("解锁成功"),
    UNLOCK_FAILED("解锁失败"),
    BIOMETRIC_AUTH("生物识别"),

    // ===== 通知 =====
    NOTIFICATION_RECEIVED("通知到达"),
    NOTIFICATION_REMOVED("通知移除"),

    // ===== 系统状态 =====
    DOZE_MODE_ENTERED("进入省电待机"),
    DOZE_MODE_EXITED("退出省电待机"),
    DEVICE_SHUTDOWN("设备关机"),
    TIME_MANUALLY_CHANGED("系统时间修改"),
    DOCK_EVENT("底座连接"),
    USER_SWITCHED("用户切换"),
    NFC_STATE_CHANGED("NFC 状态变化"),

    // ===== 网络 =====
    WIFI_CONNECTED("WiFi 连接"),
    WIFI_DISCONNECTED("WiFi 断开"),
    MOBILE_DATA_CHANGED("移动数据切换"),
    NETWORK_TYPE_CHANGED("网络类型变化"),
    SIGNAL_STRENGTH_CHANGED("信号强度变化"),
    VPN_CONNECTED("VPN 连接"),
    VPN_DISCONNECTED("VPN 断开"),
    HOTSPOT_CHANGED("热点切换"),

    // ===== 连接设备 =====
    BLUETOOTH_CONNECTED("蓝牙设备连接"),
    BLUETOOTH_DISCONNECTED("蓝牙设备断开"),
    HEADSET_PLUGGED("耳机插入"),
    HEADSET_UNPLUGGED("耳机拔出"),
    CONNECTED_TO_PC("连接电脑"),
    DISCONNECTED_FROM_PC("断开电脑"),

    // ===== 电源 & 存储 =====
    CHARGING_STARTED("开始充电"),
    CHARGING_STOPPED("停止充电"),
    BATTERY_CHANGED("电量变化"),
    POWER_SAVE_MODE_CHANGED("省电模式切换"),
    STORAGE_LOW("存储不足"),
    STORAGE_OK("存储恢复"),

    // ===== 音频 & 情景 =====
    VOLUME_CHANGED("音量变化"),
    RINGER_MODE_CHANGED("情景模式变化"),
    AIRPLANE_MODE_CHANGED("飞行模式切换"),

    // ===== 应用 & 系统 =====
    APP_INSTALLED("应用安装"),
    APP_UNINSTALLED("应用卸载"),
    APP_CRASHED("App 崩溃"),
    SCREENSHOT_TAKEN("截屏事件"),
    TIMEZONE_CHANGED("时区变化"),
    LOCALE_CHANGED("语言区域变化"),
    CALL_STATE_CHANGED("通话状态变化"),

    // ===== 窗口内容变化 =====
    WINDOW_CONTENT_CHANGED("页面内容变化"),

    // ===== 传感器 =====
    SENSOR_LIGHT_CHANGED("环境光线变化"),
    SENSOR_PROXIMITY_CHANGED("接近传感器"),
    DEVICE_MOVEMENT_CHANGED("设备移动状态"),

    // ===== 新增高价值事件 =====
    MEDIA_PLAYING("媒体播放"),
    PAGE_CONTENT("页面内容"),
    URL_VISITED("网页浏览"),
    DOWNLOAD_COMPLETED("下载完成"),
    SCREEN_REFRESH_CHANGED("刷新率变化"),
    VOICE_CALL_STARTED("语音通话开始"),
    VOICE_CALL_ENDED("语音通话结束"),
    APP_USAGE_DAILY_REPORT("使用日报"),
    SLEEP_PERIOD("睡眠时段"),

    // ===== 新增无障碍深度 =====
    TOUCH_INTERACTION("触摸交互"),
    TEXT_SELECTED("选中文本"),
    DIALOG_DETECTED("弹窗检测"),
    MULTI_WINDOW_CHANGED("多窗口变化");

    companion object {
        private val map = entries.associateBy { it.name }
        fun fromString(name: String): EventType? = map[name]
        fun getChineseName(name: String): String =
            map[name]?.description ?: name
    }
}
