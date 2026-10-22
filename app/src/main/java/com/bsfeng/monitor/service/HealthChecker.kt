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
import com.bsfeng.monitor.data.DatabaseHelper

object HealthChecker {
    private const val CHANNEL_ID_ALERT = "monitor_alert"
    private const val NOTIFICATION_ID = 1001
    private var lastNotificationTime = 0L
    private const val NOTIFICATION_COOLDOWN_MS = 30 * 60 * 1000L  // 30 分钟

    fun checkAndRestore(context: Context) {
        // 检查 AccessibilityService 是否存活
        if (!isAccessibilityServiceRunning(context)) {
            notifyUser(context, "监控服务已停止", "请前往 设置 → 无障碍 → 行为记录 重新开启")
        } else if (HeartbeatWatchdog.isSuspended(
                context,
                MonitorAccessibilityService.isRunning,
                SystemClock.elapsedRealtime(),
            )
        ) {
            // 勾选态与绑定关系都正常、但进程没在跑 —— MIUI/HyperOS 冻结的特征。
            // 详见 docs/踩坑/进程被系统冻结导致无障碍全部失效.md
            notifyUser(
                context,
                "监控进程疑似被冻结",
                "无障碍显示已开启但没有心跳。请关闭针对本应用的「省电策略」，或把应用加入电池白名单",
            )
        }

        // 检查事件积压（二期实现后接入 RecordStore 统计）
        val count = 0
        if (count > 1000) {
            notifyUser(context, "事件积压过多",
                "当前待上报事件 $count 条，请检查网络连接")
        }
    }

    private fun isAccessibilityServiceRunning(context: Context): Boolean {
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                16 // AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            return enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        } catch (_: Exception) {
            return true  // 无法判断时默认 OK
        }
    }

    private fun notifyUser(context: Context, title: String, content: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) return
        lastNotificationTime = now

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
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
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
