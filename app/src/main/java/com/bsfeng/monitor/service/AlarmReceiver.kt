package com.bsfeng.monitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 本接收器有两个入口：系统 BOOT_COMPLETED（action 有值）与 AlarmManager 心跳（显式组件、action 为空）。
        // 其他 action 一律忽略，防止外部伪造广播触发上报与进程唤醒。
        val action = intent.action
        if (action != null && action != Intent.ACTION_BOOT_COMPLETED) return
        if (action == null && intent.component?.className != javaClass.name) return
        // 系统重启后 AlarmManager 闹钟丢失,重新调度(重复调用会更新同一 PendingIntent,不会叠加)
        scheduleAlarmCheck(context)
        // 定期备份的闹钟同理：Restart 后补排一次（已在跑时是空操作，不会把触发时间往后推）
        BackupScheduler.ensureScheduled(context)

        HealthChecker.checkAndRestore(context)

        // 检查 AccessibilityService 是否存活，如果不在且 App 不在前台才尝试唤醒进程。
        // 注意：不再无条件 startActivity + CLEAR_TOP —— 那会每分钟把正在使用的页面
        // 重建回「概览」，用户会看到「设置页/传送页自己跳走」。
        if (!isAccessibilityServiceRunning(context)) {
            bringToFrontIfBackground(context)
        }

        // 每 1 分钟也触发一次数据上报
        // goAsync():广播回调返回后进程可能被系统回收,异步任务必须持有 pendingResult
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val reporter = EventReporter(context)
                if (AppUtils.getToken() != null) {
                    reporter.uploadBatch()
                } else {
                    reporter.registerDevice()
                    reporter.uploadBatch()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isAccessibilityServiceRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                16 // AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        } catch (_: Exception) {
            true  // 无法判断时默认 OK
        }
    }

    /**
     * 仅在 App 不在前台时才把界面唤到前台。
     *
     * 用 `SINGLE_TOP`（不是 `CLEAR_TOP`）避免重建当前页；前台用户正在操作时直接跳过，
     * 否则用户会看到页面被每分钟一次的保活闹钟踢回「概览」。
     */
    private fun bringToFrontIfBackground(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val inForeground = runCatching {
            activityManager?.runningAppProcesses?.any {
                it.processName == context.packageName &&
                    it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: false
        }.getOrDefault(false)
        if (inForeground) return
        val wakeIntent = Intent(context, com.bsfeng.monitor.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(wakeIntent) }
    }
}

fun scheduleAlarmCheck(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, AlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // 每 1 分钟检查一次保活状态
    alarmManager.setInexactRepeating(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + 30_000,
        60 * 1000L,  // 1 分钟心跳
        pendingIntent
    )
}
