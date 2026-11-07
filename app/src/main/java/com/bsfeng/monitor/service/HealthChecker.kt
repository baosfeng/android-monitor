package com.bsfeng.monitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.util.AccessibilityStatus

object HealthChecker {
    private const val CHANNEL_ID_ALERT = "monitor_alert"

    /** 两类告警各自的通知 id：共用同一个 id 会互相覆盖，先发的那条永远看不到。 */
    private const val ID_SERVICE_STOPPED = 1001
    private const val ID_PROCESS_FROZEN = 1002

    private const val NOTIFICATION_COOLDOWN_MS = 30 * 60 * 1000L  // 30 分钟

    /** 两类告警的冷却窗各算各的：共用一个时间戳会让后发生的故障被前一次的冷却吞掉。 */
    private var lastStoppedAtMs = 0L
    private var lastFrozenAtMs = 0L

    fun checkAndRestore(context: Context) {
        val serviceRunning = MonitorAccessibilityService.isRunning
        // 「是否开启」只有 util/AccessibilityStatus 一个权威实现：它用 FEEDBACK_ALL_MASK 并解释过
        // 为什么不能用 FEEDBACK_GENERIC（本类与 AlarmReceiver 曾是两份走偏的副本，已删）。
        // 告警有副作用（发通知 / 拉前台），所以要求连续 N 次都判未开启才动手，避免单次 ROM 抖动误报。
        if (AccessibilityStatus.isDisabledConfirmed(context, serviceRunning)) {
            notify(
                context,
                id = ID_SERVICE_STOPPED,
                lastAtMs = lastStoppedAtMs,
                title = "监控服务已停止",
                content = "请前往 设置 → 无障碍 → 行为记录 重新开启",
            ) { lastStoppedAtMs = it }
        } else if (HeartbeatWatchdog.isSuspended(
                context,
                serviceRunning,
                SystemClock.elapsedRealtime(),
            )
        ) {
            // 勾选态与绑定关系都正常、但进程没在跑 —— MIUI/HyperOS 冻结的特征。
            // 详见 docs/踩坑/进程被系统冻结导致无障碍全部失效.md
            notify(
                context,
                id = ID_PROCESS_FROZEN,
                lastAtMs = lastFrozenAtMs,
                title = "监控进程疑似被冻结",
                content = "无障碍显示已开启但没有心跳。请关闭针对本应用的「省电策略」，或把应用加入电池白名单",
            ) { lastFrozenAtMs = it }
        }
    }

    /** 按各自的冷却窗发通知；冷却中直接跳过。 */
    private inline fun notify(
        context: Context,
        id: Int,
        lastAtMs: Long,
        title: String,
        content: String,
        record: (Long) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAtMs < NOTIFICATION_COOLDOWN_MS) return
        record(now)
        notifyUser(context, id, title, content)
    }

    private fun notifyUser(context: Context, id: Int, title: String, content: String) {
        createChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            // SINGLE_TOP 而不是 CLEAR_TASK：点预警通知回到 App 时不重建页面、不清用户当前所在 Tab
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 没有通知权限则跳过
        }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_ALERT,
            "监控预警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "当监控服务异常时通知用户"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
