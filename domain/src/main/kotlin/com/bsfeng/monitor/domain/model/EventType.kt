package com.bsfeng.monitor.domain.model

/**
 * 事件类型枚举。
 *
 * ⚠️ 当前仅保留导航/会话追踪相关事件，其余全部删除。
 */
enum class EventType(val description: String) {

    // ===== App & 界面 =====
    APP_FOREGROUND("App 切换"),
    ACTIVITY_CHANGE("页面跳转"),

    // ===== 交互点击 =====
    VIEW_CLICKED("点击交互"),

    // ===== 导航链路 =====
    NAVIGATION_CHANGE("导航路径"),

    // ===== 返回追踪 =====
    RETURN_TO_PAGE("回到之前浏览的页面");

    companion object {
        private val map = entries.associateBy { it.name }
        fun fromString(name: String): EventType? = map[name]
        fun getChineseName(name: String): String =
            map[name]?.description ?: name
    }
}
