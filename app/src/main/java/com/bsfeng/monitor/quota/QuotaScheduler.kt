package com.bsfeng.monitor.quota

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 额度后台刷新调度。
 *
 * 与保活心跳（requestCode 0）、自动备份（7301）分开用 requestCode [REQUEST_CODE]，
 * 互不覆盖。频率持久化在 [QuotaSettings]；额度功能关闭时调 [apply] 传 0 即取消。
 */
object QuotaScheduler {

    private const val REQUEST_CODE = 7401

    /** 按小时数重排；0 = 取消。 */
    fun apply(context: Context, intervalHours: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(
            context,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        if (intervalHours <= 0) return
        val interval = intervalHours * 60L * 60L * 1000L
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + interval,
            interval,
            pendingIntent,
        )
    }

    /**
     * 「闹钟还在就什么都不做」—— 开机 / 进程重启后闹钟会丢，由心跳补上。
     *
     * 先查后设：重复 `setInexactRepeating` 会把下次触发时间往后推，等于永不触发。
     */
    fun ensureScheduled(context: Context) {
        if (!FeatureFlags.get().isEnabled(Feature.QUOTA)) return
        val hours = QuotaSettings(context).refreshIntervalHours
        if (hours <= 0) return
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, QuotaRefreshReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) return
        apply(context, hours)
    }

    /** 设置变化后立即生效。 */
    fun reschedule(context: Context) {
        val hours = if (FeatureFlags.get().isEnabled(Feature.QUOTA)) {
            QuotaSettings(context).refreshIntervalHours
        } else {
            0
        }
        apply(context, hours)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, QuotaRefreshReceiver::class.java),
            flags,
        )
}

/**
 * 后台刷新入口 —— 只做一件事：让已配置的 provider 各拉一次（内部有 60 秒节流与 429 退避）。
 *
 * 用 `goAsync()` 撑住广播生命周期；失败只记日志，不影响下一次调度。
 */
class QuotaRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!acceptsAction(intent?.action)) return
        if (!FeatureFlags.get().isEnabled(Feature.QUOTA)) return
        val app = context.applicationContext as? MonitorApp ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            runCatching { app.container.quotaRepository.refreshAll() }
            // 刷新完顺手重画额度组件：桌面上的百分比要跟这次拉到的快照一致（30 秒节流）
            WidgetRefresher.refreshAll(context)
            pending.finish()
        }
    }

    companion object {
        /**
         * 该 action 是否该触发一次刷新。
         *
         * 本接收器有两个入口：系统 BOOT_COMPLETED（action 有值）与 AlarmManager 显式
         * PendingIntent（显式组件、action 为空）。除这两者外一律忽略——不校验时收到任何
         * intent 都会全量刷新一次；虽然 exported=false 挡住外部伪造，但同进程误发的
         * 别的 action 也会白烧一轮限流额度。
         *
         * 抽成 companion 里的纯函数是为了能直接断言：`BroadcastReceiver.onReceive`
         * 依赖 Context 与 goAsync()，在 JVM 单测里构造代价高。
         */
        fun acceptsAction(action: String?): Boolean =
            action == null || action == Intent.ACTION_BOOT_COMPLETED
    }
}
