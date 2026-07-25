package com.bsfeng.monitor.domain.model

/**
 * 会话记录：保存用户离开某个 App 时的现场。
 *
 * @param packageName 应用包名
 * @param appName 应用中文名
 * @param navPath 离开时的导航栈
 * @param lastPageTitle 离开时的页面标题
 * @param lastActivityClass 离开时的 Activity 类名
 * @param leaveTimeMs 离开时间戳
 */
data class SessionRecord(
    val packageName: String,
    val appName: String,
    val navPath: List<NavigationNode>,
    val lastPageTitle: String?,
    val lastActivityClass: String?,
    val leaveTimeMs: Long
)
