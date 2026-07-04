package com.bsfeng.monitor.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

/**
 * 使用 ApplicationExitInfo (API 30+) 兜底检测应用被杀死事件。
 * 作为 PACKAGE_RESTARTED 广播的补充——当广播未送达时，
 * 通过轮询历史进程退出原因捕获被强制停止的应用。
 *
 * 只在 API 30+ 上生效，仅查询最近前台出现过的应用，
 * 避免遍历所有已安装包带来的性能开销。
 */
class AppKillDetector(private val context: Context) {

    // 待检测的应用列表（由外部更新——从无障碍服务获取最近前台应用）
    @Volatile
    var targetPackages: Set<String> = emptySet()
        private set

    // 已上报过的退出标记（package_timestamp），避免重复上报
    private val reportedKeys = mutableSetOf<String>()

    /** 更新待检测的应用列表 */
    fun updateTargetPackages(packages: Set<String>) {
        targetPackages = packages
    }

    /**
     * 执行一轮检测：查询 targetPackages 中每个应用的最近退出原因。
     * 应在定时器或协程循环中定期调用（建议间隔 30~60 秒）。
     */
    fun detect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (targetPackages.isEmpty()) return

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val now = System.currentTimeMillis()
        val cutoff = now - 120_000L  // 只关心最近 2 分钟内的退出

        for (pkg in targetPackages) {
            try {
                val reasons = am.getHistoricalProcessExitReasons(pkg, 0, 3)
                for (reason in reasons) {
                    if (reason.timestamp < cutoff) continue
                    if (reason.timestamp > now) continue

                    // 只关注"用户强制停止"和"低内存/系统回收"
                    val interest = when (reason.reason) {
                        8 -> true  // ActivityManager.REASON_USER_REQUESTED
                        3 -> true  // ActivityManager.REASON_LOW_MEMORY
                        5 -> true  // ActivityManager.REASON_CRASH_NATIVE
                        else -> false
                    }
                    if (!interest) continue

                    val key = "${pkg}_${reason.timestamp}"
                    if (key in reportedKeys) continue
                    reportedKeys.add(key)

                    val exitDesc = when (reason.reason) {
                        8 -> "被用户强制停止"
                        3 -> "因内存不足被系统杀死"
                        5 -> "Native 崩溃"
                        else -> "异常退出"
                    }

                    val appName = try {
                        val pm = context.packageManager
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) { pkg }

                    EventCollector.emit(EventType.APP_KILLED, mapOf(
                        "package_name" to pkg,
                        "app_name" to appName,
                        "exit_reason" to exitDesc,
                        "exit_reason_code" to reason.reason.toString(),
                        "kill_timestamp" to reason.timestamp.toString()
                    ), summary = "$appName $exitDesc")
                }
            } catch (_: Exception) {
                // 单个包查询失败不影响其他包
            }
        }
    }

    /** 清理过期的已上报标记，防止内存泄漏 */
    fun trimReportedKeys() {
        if (reportedKeys.size > 500) {
            reportedKeys.clear()
        }
    }
}
