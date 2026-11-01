package com.bsfeng.monitor.record

// 从 service/LocationForegroundService 拆出：门禁 ticker 的拍子策略与排程（纯逻辑，可单测）。
// 服务只提供 Handler 宿主，以及三态 / 兜底时刻两个只读来源。

import kotlin.math.min

/**
 * 门禁 ticker 的拍子策略（纯函数）。
 *
 * 为什么要单独定节奏：旧实现固定 30 秒一拍、无条件重排，SLEEPING 时每拍只做三件事
 * （onTick 评估、syncSensors、比一次 30 分钟兜底是否到期），一整天 2880 次纯空转唤醒 ——
 * 比 1 分钟心跳还多一倍。SLEEPING 下唯一有意义的定时事件就是那条 30 分钟兜底，拍子可以放到 5 分钟。
 *
 * 上界 5 分钟不是随手给的：兜底时刻是「上次兜底 + 30 分钟」，拍子必须能在到期**前一拍**排到，
 * 所以取 min(5 分钟, 距到期剩余)；下限 30 秒保证到期那一拍不会迟到超过 30 秒（与旧的 30 秒拍同精度）。
 */
object LocationTickerPolicy {

    /** MOVING / PROBING 的拍子：30 秒（探测与静默补采的节奏一秒都不许拖）。 */
    const val ACTIVE_TICK_MS = LocationPace.MOVING_INTERVAL_MS

    /** SLEEPING 的拍子上限：5 分钟。 */
    const val SLEEPING_MAX_TICK_MS = 5 * 60_000L

    /** 拍子下限：30 秒。 */
    const val MIN_TICK_MS = 30_000L

    /**
     * SLEEPING 兜底间隔：30 分钟（防传感器误判 —— 手机放车上 / 传送带上时加速度可能一直平稳）。
     *
     * 与拍子是两个量：兜底是**事件**，拍子只是「多久醒来看一眼到期没有」。放在这里而不是服务里，
     * 是为了让「到期剩余」与「什么时候真的补」读同一份常量。
     */
    const val SLEEPING_FALLBACK_INTERVAL_MS = 30 * 60_000L

    /**
     * 下一拍延迟（毫秒）。
     *
     * - MOVING / PROBING：30 秒，固定；
     * - SLEEPING：[lastFallbackAtMs] == 0（刚进 SLEEPING，还没记过兜底）→ 5 分钟，服务会在这一拍初始化；
     *   时钟回拨（now < lastFallback）→ 30 秒（不猜，交给下一次评估）；
     *   兜底已过期 → 30 秒（下一拍就补，不拖）；
     *   否则 min(5 分钟, 距到期剩余)，下限 30 秒。
     */
    fun nextDelayMs(state: MotionGate.State, nowMs: Long, lastFallbackAtMs: Long): Long {
        if (state != MotionGate.State.SLEEPING) return ACTIVE_TICK_MS
        if (lastFallbackAtMs <= 0L) return SLEEPING_MAX_TICK_MS
        if (nowMs < lastFallbackAtMs) return MIN_TICK_MS
        val remainingMs = lastFallbackAtMs + SLEEPING_FALLBACK_INTERVAL_MS - nowMs
        if (remainingMs <= 0L) return MIN_TICK_MS
        return min(SLEEPING_MAX_TICK_MS, remainingMs).coerceAtLeast(MIN_TICK_MS)
    }
}

/** ticker 排程宿主：生产实现是 Handler（先 removeCallbacks 再 postDelayed）；单测注入假实现。 */
interface TickerHost {
    /** 摘掉已排的一拍，再按 [delayMs] 重排（保证同一时刻只有一拍在排）。 */
    fun schedule(delayMs: Long)

    /** 取消已排的一拍。 */
    fun cancel()
}

/**
 * 门禁 ticker 的排程器（纯逻辑）：拍子见 [LocationTickerPolicy]，**状态迁移立刻重排**。
 *
 * 为什么必须重排：SLEEPING 的拍子最长 5 分钟，而传感器随时可能把门禁拉到 PROBING / MOVING。
 * 只在到点后才读新状态的话，探测与静默补采会被拖到 5 分钟后 —— 这属于时序正确性，
 * 靠读代码守不住，所以排程逻辑抽出来、用假宿主在单测里钉死（见 LocationTickerSchedulerTest）。
 */
class LocationTickerScheduler(
    private val host: TickerHost,
    private val clock: () -> Long,
    private val stateOf: () -> MotionGate.State,
    private val lastFallbackAtMsOf: () -> Long,
    private val log: (String) -> Unit = {},
) {

    /** 是否已启动（服务 onStartCommand 启动、onDestroy 停止）。 */
    var isActive: Boolean = false
        private set

    /** 排程代次：每次重排 +1；tick 执行期间被重排过（代次变了）就不再重复排。 */
    var version: Long = 0L
        private set

    private var lastLoggedDelayMs = -1L

    /** 启动：按当前态排第一拍。幂等。 */
    fun start() {
        if (isActive) return
        isActive = true
        schedule("启动")
    }

    /** 停止：服务销毁 / 退出时调用，不再排下一拍。 */
    fun stop() {
        isActive = false
        host.cancel()
    }

    /**
     * 门禁状态迁移（或兜底时刻变化）：ticker 可能已被排到 5 分钟后，立刻按新态重排。
     *
     * 不重排的后果是「传感器把门禁拉到 PROBING，探测却要等 5 分钟」—— 用户会看到坐车不升级。
     */
    fun onStateChanged() {
        if (isActive) schedule("状态迁移")
    }

    /**
     * 一拍的业务执行完毕：期间没被重排过就按当前态续排。
     *
     * 期间重排过（代次变了）说明业务里已经发生过状态迁移、并已按新态排过一拍，这里再排会多出一条 tick 链。
     *
     * @param versionAtTickStart 本拍开始时的 [version]（tick 里取一次传进来）
     */
    fun afterTick(versionAtTickStart: Long) {
        if (isActive && version == versionAtTickStart) schedule("到点续排")
    }

    private fun schedule(reason: String) {
        val state = stateOf()
        val delayMs = LocationTickerPolicy.nextDelayMs(state, clock(), lastFallbackAtMsOf())
        version++
        host.schedule(delayMs)
        // 只在拍子变化时打日志：SLEEPING 稳态是每 5 分钟一拍，全打会把 logcat 刷满
        if (delayMs != lastLoggedDelayMs) {
            lastLoggedDelayMs = delayMs
            log("门禁 ticker：下一拍 " + (delayMs / 1000) + "s（" + reason + "，" + state + "）")
        }
    }
}
