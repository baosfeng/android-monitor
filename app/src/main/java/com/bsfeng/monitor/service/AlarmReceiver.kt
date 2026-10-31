package com.bsfeng.monitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.quota.QuotaScheduler
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationGateState
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AlarmReceiver : BroadcastReceiver() {

    private companion object {
        /** 定位采样日志标签：`adb logcat -v time | grep LocationCollector` 可看到采集器侧，
         *  这里补心跳侧的落库结果与失败原因。 */
        const val TAG = "AlarmReceiver"
    }
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
        QuotaScheduler.ensureScheduled(context)

        HealthChecker.checkAndRestore(context)
        // 桌面小组件跟着这条 60 秒心跳刷新：不新增唤醒源与权限，30 秒节流在 WidgetRefresher 内，
        // 没添加到桌面的组件会被跳过（连库都不读）。放在 action 校验之后，外部伪造广播进不来。
        WidgetRefresher.refreshAll(context)

        // 检查 AccessibilityService 是否存活，如果不在且 App 不在前台才尝试唤醒进程。
        // 注意：不再无条件 startActivity + CLEAR_TOP —— 那会每分钟把正在使用的页面
        // 重建回「概览」，用户会看到「设置页/传送页自己跳走」。
        if (!isAccessibilityServiceRunning(context)) {
            bringToFrontIfBackground(context)
        }

        // 每 1 分钟也触发一次数据上报
        // goAsync():广播回调返回后进程可能被系统回收,异步任务必须持有 pendingResult
        val uploadResult = goAsync()
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
                uploadResult.finish()
            }
        }

        // 定位采样寄生在这条心跳里（不新开前台服务、不常驻监听），自己持有一个 PendingResult：
        // 主动定位可能耗时到 5 秒，与上报的完成时机互不牵连，任何一条路径都必须 finish
        sampleLocationIfEnabled(context)
    }

    /**
     * 采一次位置并落库（COORDINATE）。
     *
     * 缓存命中时同步回调、需要主动定位时 5 秒内异步回调；**回调恰好一次**，
     * 因此这里用一个独立的 `PendingResult` + `finishOnce` 守卫：
     * 成功 / 超时 / 未授权 / 节流 / 异常五条路径都会 `finish()`，不会让系统报广播超时。
     *
     * 四重闸：功能开关关闭 / 门禁 SLEEPING / 未授权 / 过滤后无可用点 → 静默返回；
     * 任何异常都不上抛，定位失败不能影响上报与保活。
     */
    private fun sampleLocationIfEnabled(context: Context) {
        val enabled = try {
            FeatureFlags.get(context).isEnabled(Feature.LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "读取位置采集开关失败", e)
            false
        }
        // 开关关闭时不申请 PendingResult，避免无谓地延长广播生命周期
        if (!enabled) return

        // 门禁 SLEEPING 时跳过定位采样：心跳若不看门禁，静止 8 小时会变成每分钟一次
        // 高精度单点（模拟器实测 network/gps provider +registration），和用户抱怨的
        // 「拿起手机定位一直开着」是同一类耗电 —— 门禁不能留这条后门。
        // 其余心跳职责（上报 / 保活 / 唤醒 / 小组件）不受影响，照旧执行；
        // 门禁为 null（前台服务没跑）时放行，保持原行为，不制造新的漏采。
        if (!LocationGateState.allowsHeartbeatSample()) {
            Log.d(TAG, "门禁 SLEEPING：心跳跳过定位采样")
            return
        }

        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
        }

        sampleLocationOnce(context) { finishOnce() }
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

/**
 * 采样一次位置并落库（COORDINATE）。
 *
 * 1 分钟心跳与前台服务里的 10 秒定时器共用这一条路径：节流间隔按当前模式自适应
 * （移动 10 秒 / 静止 3 分钟），落库放 IO 协程，[onFinished] 在**恰好一次**的回调里执行
 * （广播要 finish PendingResult，服务侧传空实现）。
 *
 * 任何异常都不上抛：定位失败不能影响上报、保活与无障碍主链路。
 */
internal fun sampleLocationOnce(context: Context, onFinished: () -> Unit) {
    try {
        LocationCollector.get(context).collectThrottledAsync { sample ->
            if (sample == null) {
                Log.w("LocationSample", "定位采样无可用点（节流中 / 未授权 / 缓存不新鲜且主动定位失败）")
                onFinished()
                return@collectThrottledAsync
            }
            // 落库放 IO：SQLite 单条 insert 毫秒级，但别占着广播/主线程回调
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    context.serviceContainer.recordCollector.onLocation(sample)
                    Log.d(
                        "LocationSample",
                        "定位落库成功 provider=${sample.provider} accuracy=${sample.accuracyMeters}",
                    )
                } catch (e: Exception) {
                    Log.w("LocationSample", "定位落库失败", e)
                } finally {
                    onFinished()
                }
            }
        }
    } catch (e: Exception) {
        Log.w("LocationSample", "定位采样异常", e)
        onFinished()
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
