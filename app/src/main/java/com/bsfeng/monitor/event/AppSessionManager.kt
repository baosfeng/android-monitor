package com.bsfeng.monitor.event

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

/**
 * App 会话管理器 — 跟踪用户离开/回到 App 的状态。
 *
 * 工作方式：
 * - 离开 App 时调用 [saveSession] 保存现场
 * - 回到 App 时调用 [restoreSession] 恢复并判断是否为"返回"
 * - 超过 [MAX_SESSION_AGE_MS]（30 分钟）的会话自动过期
 *
 * 使用场景：
 * 用户：微信 → 公众号 → 文章 → Home → 支付宝 → 回到微信文章
 *                               ↓                      ↓
 *                        saveSession(微信)        restoreSession(微信)
 *                                                → 发射 RETURN_TO_PAGE
 */
object AppSessionManager {

    private val sessions = mutableMapOf<String, SessionRecord>()

    /** 会话最大存活时间：30 分钟 */
    private const val MAX_SESSION_AGE_MS = 30 * 60 * 1000L

    /**
     * 保存（更新）指定 App 的会话记录。
     * 应在离开该 App 前调用（检测到包名变化时）。
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
        if (now - record.leaveTimeMs > MAX_SESSION_AGE_MS) {
            sessions.remove(packageName)
            return null
        }
        return record
    }

    /**
     * 清除指定 App 的会话（如 App 被杀死重启时）。
     */
    fun clearSession(packageName: String) {
        sessions.remove(packageName)
    }

    /**
     * 清除所有会话（如重置）。
     */
    fun clearAll() {
        sessions.clear()
    }
}
