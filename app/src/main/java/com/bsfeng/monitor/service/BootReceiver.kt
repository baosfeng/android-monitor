package com.bsfeng.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_USER_PRESENT) {
            // 设备解锁后启动保活定时器
            scheduleAlarmCheck(context)
        }
    }
}
