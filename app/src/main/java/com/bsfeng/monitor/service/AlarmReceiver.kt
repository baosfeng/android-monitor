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
import com.bsfeng.monitor.network.EventReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.FixBudget
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationGateState
import com.bsfeng.monitor.record.LocationProbePolicy
import com.bsfeng.monitor.record.LocationSample
import com.bsfeng.monitor.record.ServiceHealth
import com.bsfeng.monitor.util.AccessibilityStatus
import com.bsfeng.monitor.widget.WidgetRefresher
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AlarmReceiver : BroadcastReceiver() {

    private companion object {
        /** 定位采样日志标签：`adb logcat -v time | grep LocationCollector` 可看到采集器侧，
         *  这里补心跳侧的落库结果与失败原因。 */
        const val TAG = "AlarmReceiver"

        private const val UPLOAD_RETRY_BASE_MS = 1_000L
        private const val UPLOAD_RETRY_MAX_MS = 5 * 60 * 1_000L
        @Volatile
        private var uploadRetryUntilMs: Long = 0L
        @Volatile
        private var uploadRetryDelayMs: Long = UPLOAD_RETRY_BASE_MS
        private val uploadScope = CoroutineScope(Dispatchers.IO)

        /** 上次尝试拉起壳化服务的中断时刻：每拍都试会把异常日志刷满，冷却 10 分钟。 */
        @Volatile
        private var lastRestartAttemptAtMs: Long = 0L
        private const val RESTART_COOLDOWN_MS = 10 * 60_000L
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

        // 数据上报：仅在持有 Auth Token 时触发上传；uploadBatch 内部取未发记录、批量 ack、
        // 标记已上传并清理过期数据；无待上传记录时直接返回、不发网络请求。
        // 失败按指数退避，不阻塞广播主链路（定位采样等继续执行）。
        val nowMs = System.currentTimeMillis()
        if (nowMs >= uploadRetryUntilMs) {
            val pendingResult = goAsync()
            uploadScope.launch {
                try {
                    val result = EventReporter(context).uploadBatch()
                    result.onSuccess {
                        uploadRetryDelayMs = UPLOAD_RETRY_BASE_MS
                        uploadRetryUntilMs = 0L
                    }.onFailure {
                        uploadRetryDelayMs = (uploadRetryDelayMs * 2).coerceAtMost(UPLOAD_RETRY_MAX_MS)
                        uploadRetryUntilMs = System.currentTimeMillis() + uploadRetryDelayMs
                        Log.w(TAG, "事件上报失败，退避 ${uploadRetryDelayMs}ms", it)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "事件上报异常", e)
                    uploadRetryDelayMs = (uploadRetryDelayMs * 2).coerceAtMost(UPLOAD_RETRY_MAX_MS)
                    uploadRetryUntilMs = System.currentTimeMillis() + uploadRetryDelayMs
                } finally {
                    runCatching { pendingResult.finish() }
                }
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

        // 服务健康必须由**心跳能观测到的活证据**判定，不能只看门禁快照非 null ——
        // 那个字段只代表 onDestroy 没跑过。真机实测：服务被系统变成壳子后（isForeground=false、
        // types=0x0、startForegroundCount 不再增长），state 仍写着 SLEEPING，旧口径据此
        // 「服务在跑、心跳别碰定位」，而服务自己的 ticker 早已停摆 → 采点停了 5.4 小时
        // （lastSuccessAgoSec 17719→19628），自检面板却显示正常。
        // 其余心跳职责（上报 / 保活 / 唤醒 / 小组件）不受影响，照旧执行。
        val now = System.currentTimeMillis()
        val health = runCatching { LocationCollector.get(context).health(now) }.getOrNull()
        val lastSuccessAtMs = health?.lastSuccessAtMs
        probeForegroundAlive(context)
        // 采样器给出的健康档与 LocationGateState 同一份判定（它内部就是读快照后调 judge）：
        // 自检面板与心跳行为必须读同一个来源，否则面板会再次报「一切正常」。
        val serviceHealth = health?.service?.health ?: LocationGateState.serviceHealth(now, lastSuccessAtMs)
        when (serviceHealth) {
            ServiceHealth.NOT_RUNNING -> Unit // 服务没跑：走下面的心跳兜底采点
            ServiceHealth.HEALTHY -> {
                Log.d(TAG, "前台服务健康（ticker 在拍）：心跳跳过定位采样")
                return
            }
            ServiceHealth.SHELLED -> {
                // 壳化：服务实例还在但已不是前台、ticker 也不再拍。重拉前台服务是唯一能恢复
                // 30 秒订阅与探测阶梯的动作（真机拉起 App 后 startForegroundCount 1→2、
                // tickerIdleSec 才开始变化）；即便系统拒绝后台拉起，也要顺手接管一次兜底，
                // 不能让采点继续空着等到用户下次点开 App。
                restartShelledService(context)
                if (!LocationGateState.heartbeatShouldRecoverShell(now, lastSuccessAtMs)) {
                    Log.i(TAG, "前台服务已壳化：心跳已请求重启，兜底未到期（等下一拍）")
                    return
                }
                Log.i(TAG, "前台服务已壳化且兜底到期：心跳接管一次 SLEEPING 兜底")
                runFallbackFromHeartbeat(context, now)
                return
            }
            ServiceHealth.TICKER_STALLED -> {
                // 服务在前台但 ticker 被 doze 挂起（真机 11-15 凌晨 04:11→06:55 空 163 分钟）：
                // 心跳是唯一还活着的唤醒源，只有「ticker 停摆 + 兜底到期」同时成立才补位。
                if (!LocationGateState.heartbeatShouldRunSleepingFallback(now)) {
                    Log.d(TAG, "服务 ticker 停摆但兜底未到期：心跳只看不动手")
                    return
                }
                Log.i(TAG, "服务 ticker 停摆且兜底到期：心跳接手一次 SLEEPING 兜底")
                runFallbackFromHeartbeat(context, now)
                return
            }
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
     * 读系统侧的定位前台服务记录：null = 读不到（拿不到服务列表 / 系统裁剪），true/false = 前台身份。
     *
     * 只认本包的 [LocationForegroundService]，按优先级：服务是否还活着 → [RunningServiceInfo.foreground]；
     * 记录不存在时返回 false（服务实例都没了本身就是壳化的终态）。
     */
    private fun isLocationServiceForeground(context: Context): Boolean? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return null
        val records = runCatching { manager.getRunningServices(Int.MAX_VALUE) }.getOrNull() ?: return null
        val record = records.firstOrNull {
            it.service.packageName == context.packageName &&
                it.service.className == LocationForegroundService::class.java.name
        } ?: return false
        return record.foreground
    }

    /**
     * 观测前台服务的「前台身份」并写回快照：壳化最直接的证据就是它变 false。
     *
     * 只信系统给的 [ActivityManager.RunningServiceInfo.foreground]：读不到（拿不到服务列表 /
     * 系统版本不支持）就**不写**，让判定退回 ticker + 采点两条信号，不能用「读不到」当壳化证据。
     */
    private fun probeForegroundAlive(context: Context) {
        val alive = runCatching { isLocationServiceForeground(context) }.getOrNull() ?: return
        LocationGateState.onForegroundObserved(alive)
        if (!alive) Log.i(TAG, "前台服务已失去前台身份（壳化）：等 ticker 判定与自愈")
    }

    /**
     * 把已壳化的前台服务重新拉起来（自愈）。
     *
     * 平台约束：Android 12+ 从后台启动前台服务会抛 [ForegroundServiceStartNotAllowedException]，
     * 心跳是后台广播，**不能保证**拉起成功。因此这里只做「尽力而为 + 冷却」：
     * - 失败就记一次日志，采点仍由下面的兜底与下一次用户打开 App（MainActivity.onResume）承担；
     * - 10 分钟冷却：每拍都试着起一次没有意义，还会把异常日志刷满。
     */
    private fun restartShelledService(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRestartAttemptAtMs < RESTART_COOLDOWN_MS) return
        lastRestartAttemptAtMs = now
        val ok = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                android.content.Intent(context, LocationForegroundService::class.java),
            )
            true
        }.getOrElse {
            Log.i(TAG, "后台拉起前台服务被系统拒绝（" + it.javaClass.simpleName + "）：先由心跳接管兜底")
            false
        }
        if (ok) Log.i(TAG, "前台服务已壳化：已请求重新拉起（" + (RESTART_COOLDOWN_MS / 60_000L) + " 分钟内不再重试）")
    }

    /**
     * 心跳替服务补一次 SLEEPING 兜底。
     *
     * 与服务的兜底走同一条采点路径（[sampleLocationOnce]）与同一个结果出口
     * （[LocationGateState.onFallbackResult]），只是发令方不同 —— 这样「失败就 5 分钟后重试」
     * 在 ticker 恢复后依然成立，两套驱动不会各记一份账。
     */
    private fun runFallbackFromHeartbeat(context: Context, nowMs: Long) {
        LocationGateState.onFallbackAttempt(nowMs)
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        // 预算固定覆盖级（4s + 8s）：兜底只求「大致还在原地」。壳化时门禁快照可能是陈旧的
        // MOVING，按它取预算会升到满预算 5s+15s —— 那是这轮省电改造明确要挡掉的后门。
        sampleLocationOnce(context, force = true, budget = FixBudget.PROBE) { sample ->
            runCatching {
                LocationGateState.onFallbackResult(success = sample != null)
                if (sample == null) {
                    Log.i(TAG, "心跳兜底没拿到点：5 分钟后重试")
                }
            }
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
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
 *
 * @param force 跳过节流（门禁探测 / doze 补位）：兜底到期的语义就是「现在该采」，被 180 秒的
 *   静默窗口挡住会让心跳每分钟空转一次、还把失败记进退避 —— 那正是把「没到期」读成「失败了」。
 */
internal fun sampleLocationOnce(
    context: Context,
    force: Boolean = false,
    /**
     * 预算档位；null = 按门禁态推导（PROBING 覆盖级 / MOVING 与「门禁未知」满预算）。
     *
     * 壳化兜底路径显式传 [FixBudget.PROBE]：那时门禁快照可能是陈旧的 MOVING，
     * 按它推导会拿到满预算，等于给「服务壳化」开一条 20 秒射频的后门。
     */
    budget: FixBudget? = null,
    onFinished: (LocationSample?) -> Unit,
) {
    // 预算档位跟着门禁走：PROBING（玩手机 / 室内走动）用覆盖级，MOVING 与「门禁未知」用满预算。
    // 心跳与前台服务的探测抢同一个发令闸，只在服务里改档的话，落在 PROBING 的心跳
    // 仍会发起一轮 5s + 15s 的满预算请求，「单次探测射频上限」就被这条后门打破了。
    val effectiveBudget = budget ?: LocationProbePolicy.heartbeatBudget(LocationGateState.state)
    try {
        LocationCollector.get(context).collectThrottledAsync(force = force, budget = effectiveBudget) { sample ->
            if (sample == null) {
                Log.w("LocationSample", "定位采样无可用点（节流中 / 未授权 / 缓存不新鲜且主动定位失败）")
                onFinished(null)
                return@collectThrottledAsync
            }
            // 落库放 IO：SQLite 单条 insert 毫秒级，但别占着广播/主线程回调。
            // 宿主必须随任务收尾 cancel：否则每次心跳都留一个永不结束的 scope，
            // goAsync 的 PendingResult 虽然 finish 了，子协程仍可能继续跑。
            val work = IoWorkScope()
            work.scope.launch {
                try {
                    context.serviceContainer.recordCollector.onLocation(sample)
                    Log.d(
                        "LocationSample",
                        "定位落库成功 provider=${sample.provider} accuracy=${sample.accuracyMeters}",
                    )
                } catch (e: Exception) {
                    Log.w("LocationSample", "定位落库失败", e)
                } finally {
                    onFinished(sample)
                    work.cancel()
                }
            }
        }
    } catch (e: Exception) {
        Log.w("LocationSample", "定位采样异常", e)
        onFinished(null)
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
