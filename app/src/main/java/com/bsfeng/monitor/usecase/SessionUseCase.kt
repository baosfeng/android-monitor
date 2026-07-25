package com.bsfeng.monitor.usecase

import com.bsfeng.monitor.domain.AppSessionManager
import com.bsfeng.monitor.domain.model.NavigationNode
import com.bsfeng.monitor.domain.model.SessionRecord

/**
 * 会话管理用例 — 封装会话保存/恢复/过期逻辑。
 * 纯业务逻辑，不依赖 Android 框架。
 */
class SessionUseCase(
    private val appSessionManager: AppSessionManager
) {

    fun saveSession(
        packageName: String,
        appName: String,
        navPath: List<NavigationNode>,
        pageTitle: String?,
        activityClass: String?
    ) {
        appSessionManager.saveSession(packageName, appName, navPath, pageTitle, activityClass)
    }

    fun restoreSession(packageName: String): SessionRecord? {
        return appSessionManager.restoreSession(packageName)
    }

    fun clearSession(packageName: String) {
        appSessionManager.clearSession(packageName)
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        return when {
            minutes >= 60 -> "${minutes / 60}小时${minutes % 60}分钟"
            minutes >= 1 -> "${minutes}分钟${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    /** 判断是否为回到相同页面 */
    fun isSamePage(
        currentClassName: String,
        previousClassName: String?,
        currentPageTitle: String?,
        previousPageTitle: String?
    ): Boolean {
        val currentShort = currentClassName.substringAfterLast('.')
        val previousShort = previousClassName?.substringAfterLast('.')
        return currentShort == previousShort || currentClassName == previousClassName
    }
}
