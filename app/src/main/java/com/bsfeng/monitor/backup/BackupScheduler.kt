package com.bsfeng.monitor.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 自动备份的闹钟调度。
 *
 * 与保活心跳（[com.bsfeng.monitor.service.scheduleAlarmCheck]，requestCode 0）分开用
 * requestCode [REQUEST_CODE]，互不覆盖。频率持久化在 [BackupConfig]，改动时调 [apply] 重排。
 */
object BackupScheduler {

    private const val REQUEST_CODE = 7301

    /** 按 [frequency] 重排；[BackupFrequency.OFF] 等于取消。 */
    fun apply(context: Context, frequency: BackupFrequency) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(
            context,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        if (frequency == BackupFrequency.OFF) return
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + frequency.intervalMs,
            frequency.intervalMs,
            pendingIntent,
        )
    }

    /**
     * 「闹钟还在就什么都不做」——开机 / 进程重启后 AlarmManager 里的重复闹钟会丢，
     * 由 [com.bsfeng.monitor.service.AlarmReceiver] 每次心跳顺手调一次补上。
     *
     * 先查后设：重复调用 `setInexactRepeating` 会把下次触发时间往后推，等于永不触发。
     */
    fun ensureScheduled(context: Context) {
        val frequency = BackupConfig(context).frequency()
        if (frequency == BackupFrequency.OFF) return
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BackupReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) return
        apply(context, frequency)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BackupReceiver::class.java),
            flags,
        )
}
