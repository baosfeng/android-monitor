package com.bsfeng.monitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 系统重启后 AlarmManager 闹钟丢失,重新调度(重复调用会更新同一 PendingIntent,不会叠加)
        scheduleAlarmCheck(context)

        HealthChecker.checkAndRestore(context)

        // 检查 AccessibilityService 是否存活，如果不在则尝试唤醒应用进程
        if (!isAccessibilityServiceRunning(context)) {
            // 通过启动 MainActivity 来唤醒进程（会触发 Application.onCreate 重新初始化）
            val wakeIntent = Intent(context, com.bsfeng.monitor.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(wakeIntent)
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
