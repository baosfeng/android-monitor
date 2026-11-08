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
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationGateState
import com.bsfeng.monitor.record.LocationProbePolicy
import com.bsfeng.monitor.util.AccessibilityStatus
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
        // 系统重启后 AlarmManager 闹钟丢失，这里只补排一次（已在位时是空操作）。
        // **不能**无条件 scheduleAlarmCheck：那会把下一次触发重新锚到 30 秒后，等效周期腰斩成 30 秒。
        ensureAlarmCheck(context)
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
        // 判定走 AccessibilityStatus 唯一实现，并要求连续 N 次都判未开启才动手：
        // 单次抖动就把用户从别的 App 拽回来，代价远大于晚一分钟发现故障。
        if (AccessibilityStatus.isDisabledConfirmed(context, MonitorAccessibilityService.isRunning)) {
            bringToFrontIfBackground(context)
        }

        // 数据上报：EventReporter.uploadBatch 目前是恒返回 0 的空壳（二期实现），
        // 这里连 goAsync 都不申请 —— 否则每分钟白起一个协程、无 token 时还会每次重试设备注册。
        // 二期接上传时把「有 token 才上传 + 失败指数退避」一起写回来，别恢复成现在的无条件每 60s。

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
    // 预算档位跟着门禁走：PROBING（玩手机 / 室内走动）用覆盖级，MOVING 与「门禁未知」用满预算。
    // 心跳与前台服务的探测抢同一个发令闸，只在服务里改档的话，落在 PROBING 的心跳
    // 仍会发起一轮 5s + 15s 的满预算请求，「单次探测射频上限」就被这条后门打破了。
    val budget = LocationProbePolicy.heartbeatBudget(LocationGateState.state)
    try {
        LocationCollector.get(context).collectThrottledAsync(budget = budget) { sample ->
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

/**
 * 保活心跳的排班口径。
 *
 * 抽成独立对象是为了让「要不要排」能在纯 JVM 单测里断言（[AlarmReceiver] 本身依赖 Context）。
 * 数字口径与 `docs/后台服务/概述.md` 一致：**首次 30 秒后，之后每 60 秒一次**。
 */
internal object HeartbeatAlarmSchedule {

    /** 首个触发延迟：进程刚起来先让启动流程跑完，不在启动瞬间唤醒自己。 */
    const val FIRST_DELAY_MS = 30_000L

    /** 心跳周期：健康检查 / 桌面组件刷新 / 定位采样都搭这一条，改它要连文档一起改。 */
    const val INTERVAL_MS = 60_000L

    /** 与自动备份（7301）、额度刷新（7401）分开，互不覆盖。 */
    const val REQUEST_CODE = 0

    /**
     * 只有「还没有这条闹钟」才排。
     *
     * `setInexactRepeating` 的 start 语义是「从现在起再等 [FIRST_DELAY_MS]」，
     * 每次触发都排一次等于把周期腰斩成 30 秒（唤醒、定位、6 个组件刷新全翻倍）。
     */
    fun shouldSchedule(hasScheduledAlarm: Boolean): Boolean = !hasScheduledAlarm
}

/**
 * 「心跳闹钟还在就什么都不做」—— 开机 / 进程重启后 AlarmManager 里的重复闹钟会丢。
 *
 * 与 [com.bsfeng.monitor.backup.BackupScheduler.ensureScheduled] 同口径：先 `FLAG_NO_CREATE` 查、
 * 没有才 [scheduleAlarmCheck]。**所有补排入口都必须走这里**，别再直接调 [scheduleAlarmCheck]。
 */
fun ensureAlarmCheck(context: Context) {
    val existing = PendingIntent.getBroadcast(
        context,
        HeartbeatAlarmSchedule.REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )
    if (!HeartbeatAlarmSchedule.shouldSchedule(existing != null)) return
    scheduleAlarmCheck(context)
}

/**
 * 无条件排一次心跳（`now + FIRST_DELAY_MS` 起、每 [HeartbeatAlarmSchedule.INTERVAL_MS] 一次）。
 *
 * 会重新锚定首触发时刻，因此只在确认没有闹钟时调用，见 [ensureAlarmCheck]。
 */
fun scheduleAlarmCheck(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        HeartbeatAlarmSchedule.REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // 每 1 分钟检查一次保活状态
    alarmManager.setInexactRepeating(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + HeartbeatAlarmSchedule.FIRST_DELAY_MS,
        HeartbeatAlarmSchedule.INTERVAL_MS,
        pendingIntent,
    )
}
