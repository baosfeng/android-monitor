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
        HealthChecker.checkAndRestore(context)

        // 每 30 分钟也触发一次数据上报
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val reporter = EventReporter(context)
            if (AppUtils.getToken() != null) {
                reporter.uploadBatch()
            } else {
                reporter.registerDevice()
                reporter.uploadBatch()
            }
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

    // 每 30 分钟检查一次
    alarmManager.setInexactRepeating(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + 30_000,
        30 * 60 * 1000L,
        pendingIntent
    )
}
