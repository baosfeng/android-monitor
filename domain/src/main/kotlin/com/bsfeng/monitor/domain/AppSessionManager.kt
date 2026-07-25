package com.bsfeng.monitor.domain

import com.bsfeng.monitor.domain.model.NavigationNode
import com.bsfeng.monitor.domain.model.SessionRecord

/**
 * App 会话管理器 — 跟踪用户离开/回到 App 的状态。
 *
 * 工作方式：
 * - 离开 App 时调用 [saveSession] 保存现场
 * - 回到 App 时调用 [restoreSession] 恢复并判断是否为"返回"
 * - 超过 [MAX_SESSION_AGE_MS]（30 分钟）的会话自动过期
 */
class AppSessionManager {

    private val sessions = mutableMapOf<String, SessionRecord>()

    /** 会话最大存活时间：30 分钟 */
    private val maxSessionAgeMs: Long = 30 * 60 * 1000L

    /**
     * 保存（更新）指定 App 的会话记录。
     */
    fun saveSession(
        packageName: String,
        appName: String,
        navPath: List<NavigationNode>,
        pageTitle: String?,
        activityClass: String?
    ) {
        if (packageName.isBlank()) return
        sessions[packageName] = SessionRecord(
            packageName = packageName,
            appName = appName,
            navPath = navPath,
            lastPageTitle = pageTitle,
            lastActivityClass = activityClass,
            leaveTimeMs = System.currentTimeMillis()
        )
    }

    /**
     * 恢复（查找）指定 App 的会话记录。
     *
     * @return 找到且未过期的 [SessionRecord]，否则 null
     */
    fun restoreSession(packageName: String): SessionRecord? {
        val record = sessions[packageName] ?: return null
        val now = System.currentTimeMillis()
        if (now - record.leaveTimeMs > maxSessionAgeMs) {
            sessions.remove(packageName)
            return null
        }
        return record
    }

    fun clearSession(packageName: String) {
        sessions.remove(packageName)
    }

    fun clearAll() {
        sessions.clear()
    }
}
