package com.bsfeng.monitor.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.bsfeng.monitor.util.AccessibilityStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程活性心跳：把「无障碍服务是否真的在跑」变成可观测的历史事实。
 *
 * ## 为什么需要它
 * MIUI / HyperOS 会把后台进程放进冻结 cgroup（`cgroup.freeze=1`），线程被挂起后
 * `onAccessibilityEvent` 不再投递，广告跳过与自动记账**一起**静默失效。此时：
 *
 * | 判定面 | 冻结时的读数 | 结论 |
 * |---|---|---|
 * | `ENABLED_ACCESSIBILITY_SERVICES` | 正常 | 只有勾选态 |
 * | `AccessibilityManager` 服务列表 | 正常 | 绑定关系还在 |
 * | `MonitorAccessibilityService.isRunning` | **仍是 true** | 静态标志不会被冻结清掉 |
 *
 * 三者都「正常」，所以既有健康判定都看不见冻结。唯一能看见的是**时间**：
 * 进程真在跑时心跳持续更新，被冻结则停更。
 *
 * ## 为什么记录「历史中断」而不是「当前是否停更」
 * 冻结是**用户打开 App 才会被发现**的——而打开 App 这个动作本身就会解冻进程，
 * 心跳立刻恢复。所以只判「此刻是否停更」，设置页永远显示正常，等于没做。
 * 因此心跳恢复时回看间隔：超过阈值就记一条中断记录，由设置页提示用户去关省电策略。
 * 进程被杀后重启也会留下同样的间隔，语义上同属「监控中断过」，一并提示。
 *
 * 详见 `docs/踩坑/进程被系统冻结导致无障碍全部失效.md`。
 */
object HeartbeatWatchdog {

    private const val TAG = "Heartbeat"
    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_LAST_BEAT_MS = "heartbeat_last_ms"
    private const val KEY_GAP_MS = "heartbeat_gap_ms"
    private const val KEY_GAP_RECOVERED_AT = "heartbeat_gap_recovered_at"

    /**
     * 心跳超时阈值：tick 间隔超过它就认为中间「进程没被调度」。
     *
     * 取 3 分钟：轮询链每 1 秒一次，正常几乎不可能断 3 分钟；Doze 下允许的正常停顿
     * 也远小于该量级（实测冻结是整段停摆）。阈值偏大是为了避免「切后台久了」被误报。
     */
    const val STALE_THRESHOLD_MS: Long = 3 * 60 * 1000L

    /**
     * 心跳衰减判定（纯函数，便于 JVM 单测把边界钉死）。
     *
     * @param lastBeatMs 上次心跳的 `elapsedRealtime()`；0 = 从未记录（全新安装 / 首次运行）
     * @param nowMs 当前 `elapsedRealtime()`
     * @param thresholdMs 超时阈值
     * @return true = 心跳已停更，判定进程未被调度
     */
    fun isStale(lastBeatMs: Long, nowMs: Long, thresholdMs: Long = STALE_THRESHOLD_MS): Boolean {
        // 从未记录：不能判定为冻结（否则新装用户一进设置页就看到告警）
        if (lastBeatMs <= 0L) return false
        // 设备重启后 elapsedRealtime 归零，此时 now < last 属正常，不判冻结
        if (nowMs < lastBeatMs) return false
        return nowMs - lastBeatMs > thresholdMs
    }

    /**
     * 落一次心跳（轮询链每次跳到时调用）。
     *
     * 顺带回看与上次心跳的间隔：若超过阈值，说明这段时间进程没在跑（冻结或被杀），
     * 记一条中断记录供设置页展示。恢复后心跳会连续更新，因此不会重复记录。
     *
     * @param wallClockMs 墙钟时间，仅用于「中断发生在几点」的展示
     */
    fun beat(context: Context, nowMs: Long, wallClockMs: Long = System.currentTimeMillis()) {
        runCatching {
            val p = prefs(context)
            val last = p.getLong(KEY_LAST_BEAT_MS, 0L)
            // 恢复正常后才有可能「看到」中断：此刻进程在跑，说明刚被唤醒/重启
            if (isStale(last, nowMs)) {
                p.edit {
                    putLong(KEY_GAP_MS, nowMs - last)
                    putLong(KEY_GAP_RECOVERED_AT, wallClockMs)
                }
                Log.w(TAG, "检测到监控中断 " + (nowMs - last) / 1000 + " 秒（进程可能被冻结）")
            }
            p.edit { putLong(KEY_LAST_BEAT_MS, nowMs) }
        }.onFailure { Log.w(TAG, "心跳落盘失败", it) }
    }

    /** 服务主动停止（`onDestroy`）时清掉心跳，避免「用户自己关掉服务」被当成中断。 */
    fun onServiceStopped(context: Context) {
        runCatching { prefs(context).edit { putLong(KEY_LAST_BEAT_MS, 0L) } }
    }

    /**
     * 上次中断的时长（ms）；0 = 没有未确认的中断记录。
     *
     * 用户点「知道了」或修复后调用 [clearGap] 清除。
     */
    fun pendingGapMs(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_GAP_MS, 0L) }.getOrDefault(0L)

    /** 清除中断记录（用户已确认知道）。 */
    fun clearGap(context: Context) {
        runCatching {
            prefs(context).edit {
                putLong(KEY_GAP_MS, 0L)
                putLong(KEY_GAP_RECOVERED_AT, 0L)
            }
        }
    }

    /**
     * 当前是否判定为「正被冻结 / 未被调度」。
     *
     * 覆盖 HealthChecker 的心跳路径（广播还能收到、但服务已长时间没 tick 的边界情况）；
     * 用户可感知的提示以 [pendingGapMs] 的历史记录为主。
     *
     * @param serviceRunning [MonitorAccessibilityService.isRunning]
     */
    fun isSuspended(
        context: Context,
        serviceRunning: Boolean,
        nowMs: Long,
        thresholdMs: Long = STALE_THRESHOLD_MS,
    ): Boolean {
        // 服务明确已停：交给「服务未开启」那条提示，不重复告警
        if (!serviceRunning) return false
        // 用户没勾选无障碍：不是冻结问题
        if (!AccessibilityStatus.isEnabledInSettings(context)) return false
        return isStale(lastBeatMs(context), nowMs, thresholdMs)
    }

    /** 上次心跳时间（`elapsedRealtime()`；0 = 从未记录）。 */
    fun lastBeatMs(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_BEAT_MS, 0L) }.getOrDefault(0L)

    /**
     * 设置页提示文案。
     *
     * @return null = 没有需要提示的中断记录
     */
    fun gapNotice(context: Context): String? {
        val gap = pendingGapMs(context)
        if (gap <= 0L) return null
        val at = runCatching {
            prefs(context).getLong(KEY_GAP_RECOVERED_AT, 0L)
        }.getOrDefault(0L).takeIf { it > 0L }
            ?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
        return gapNoticeOf(gap, at)
    }

    /**
     * 中断提示文案（纯函数，便于 JVM 单测校验措辞与时长的换算边界）。
     *
     * @param gapMs 中断时长
     * @param recoveredAt 恢复时刻的 `HH:mm`，null = 不展示时刻
     */
    fun gapNoticeOf(gapMs: Long, recoveredAt: String?): String {
        val minutes = gapMs / 60_000L
        val span = if (minutes >= 1) "约 $minutes 分钟" else "约 " + (gapMs / 1000L).coerceAtLeast(1L) + " 秒"
        val whenText = if (recoveredAt != null) "（" + recoveredAt + " 恢复）" else ""
        return "上次监控中断" + span + whenText + "，APP 当时可能被系统冻结。请关闭针对本应用的省电策略，或加入电池白名单"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
