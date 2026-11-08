package com.bsfeng.monitor.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.service.IoWorkScope
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
        // goAsync 的局部协程宿主：备份结束（无论成败）都要 cancel，
        // 否则广播进程被回收前后留下的 scope 没有句柄，任务无法收尾。
        val work = IoWorkScope()
        work.scope.launch {
            try {
                BackupManager(context).backupNow(BackupOrigin.SCHEDULED)
            } finally {
                pendingResult.finish()
                work.cancel()
            }
        }
    }
}
