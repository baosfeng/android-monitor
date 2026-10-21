package com.bsfeng.monitor.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定期备份的触发点：闹钟到点（[BackupScheduler]）或开机补排。
 *
 * 备份本身是同步 IO，必须 `goAsync()` 持有 pendingResult，否则广播返回后进程被回收、
 * 备份包会写一半。
 */
class BackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机后闹钟全丢，补排一次（只补频率不是「关闭」的）
            BackupScheduler.ensureScheduled(context)
            return
        }
        val frequency = BackupConfig(context).frequency()
        if (frequency == BackupFrequency.OFF) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                BackupManager(context).backupNow(BackupOrigin.SCHEDULED)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
