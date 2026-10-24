package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：服务生命周期 —— 启动 / 停止 / 自动关闭计时 /
// 暂停接收 / 设备刷新 / 后台 ticker。这些方法都改引擎的内部字段，故以扩展函数留在同包。

import android.util.Log
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.transfer.discovery.UdpDiscoveryService
import com.bsfeng.monitor.transfer.transport.MiniHttpServer
import com.bsfeng.monitor.transfer.policy.SendStallReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.TAG
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.PAUSE_MINUTES

internal fun TransferEngine.startImpl(): Int {
    // 功能关闭 = 真停活：不绑 HTTP 端口、不发 UDP 公告、不写历史
    if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) {
        Log.i(TAG, "传送功能已关闭：不启动 HTTP / UDP")
        return -1
    }
    if (started.compareAndSet(false, true)) {
        val httpServer = MiniHttpServer(this)
        val boundPort = runCatching { httpServer.start(scope) }
            .onFailure { Log.e(TAG, "HTTP 服务启动失败: ${it.message}") }
            .getOrDefault(-1)
        if (boundPort > 0) {
            server = httpServer
            val udp = UdpDiscoveryService(
                context = appContext,
                deviceId = settings.deviceId,
                alias = settings.alias,
                appVersion = appVersion(),
                httpPort = { localPort },
                registry = registry,
                onDeviceSeen = { refreshDevices() },
            )
            discovery = udp
            if (ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, appVisible, settings.discoverable)) {
                udp.start(scope)
            }
            _discoverable.value = settings.discoverable
            scope.launch { tickerLoop() }
            historyLoaded.set(true)
            scope.launch { loadPersistedHistory() }
            _running.value = true
            // 启动成功后才起算「自动关闭接收」（设置关闭时不做任何事）
            autoClose.start()
            Log.i(TAG, "传送服务已启动，HTTP 端口 $boundPort")
        } else {
            started.set(false)
        }
    } else if (ServiceModePolicy.shouldRunDiscovery(
            backgroundReceive = settings.backgroundReceive,
            appVisible = appVisible,
            discoverable = settings.discoverable,
        )
    ) {
        // 服务已在运行：补齐发现线程。首次启动时 App 可见性可能晚于点击，
        // 不补这一步会出现「HTTP 端口在跑、组播/广播却不发」的半启动状态。
        ensureDiscovery()
    }
    return localPort
}

/** 停止发现与服务，清理在途会话（保留 `.part` 供下次续传）。 */
internal fun TransferEngine.stopImpl() {
    // 手动关闭 / 服务销毁：取消「自动关闭」计时（重新 start 会重新计时）
    autoClose.cancel()
    if (!started.compareAndSet(true, false)) return
    server?.stop()
    server = null
    discovery?.stop()
    discovery = null
    // 停止不清理 `.part`：协议 §6 要求失败/中断保留临时文件供下次续传
    sessions.clear()
    outgoing.clear()
    pendingSend = null
    _pinPrompt.value = null
    _running.value = false
    refreshDevices()
}

/**
 * 「自动关闭接收」设置变化后重放一次计时。
 *
 * 运行中改时长 → 按新时长重新计时（与免配对窗口改时长同口径）；
 * 改成「关闭」→ 取消计时；服务没在跑 → 什么都不做（下次启动按新设置起算）。
 */
internal fun TransferEngine.refreshAutoCloseScheduleImpl() {
    if (!started.get() || _running.value != true) {
        autoClose.cancel()
        return
    }
    autoClose.start()
}

// ─────────────────────────── 后台 ticker 与超时收敛 ───────────────────────────

internal suspend fun TransferEngine.tickerLoop() {
    while (scope.isActive) {
        delay(tickMs)
        refreshDevices()
        enforceTimeouts()
        sessionTasks.pruneExpiredSessions()
    }
}

/**
 * 超时收敛：确认超时（4007）与「传输中长时间无进展」都统一落为 FAILED，
 * 任务随之从「进行中」移出（模块的单一状态出口，UI 不再自己判断）。
 *
 * 接收侧按会话收敛；发送侧按任务行收敛（见 [convergeStalledSendTasks]），口径同一个。
 */
internal fun TransferEngine.enforceTimeouts() {
    val now = System.currentTimeMillis()
    sessions.values.toList().forEach { session ->
        when {
            // 确认超时保持任务 message = "failed"（协议线值），沿用既有口径
            session.state == TransferStatus.AWAITING_CONFIRM && now >= session.confirmDeadlineMs ->
                failSession(session, TransferErrorCode.CONFIRM_TIMEOUT, "")

            session.state == TransferStatus.TRANSFERRING &&
                now - session.lastProgressAtMs >= noProgressTimeoutMs ->
                failSession(session, TransferErrorCode.CONFIRM_TIMEOUT, "长时间无进展")
        }
    }
    convergeStalledSendTasks(now)
}

/**
 * 发送侧超时收敛（与接收侧同口径、同错误码 4007）。
 *
 * 真机 Bug：「进行中」卡着一条发送任务两小时以上——发送会话没有超时路径，
 * 对端失联 / 拒收后不回话时任务永远停在 `OFFERED`（已提交清单）或 `TRANSFERRING`。
 */
/** 发送侧超时收敛（与接收侧同口径、同错误码 4007）。 */
internal fun TransferEngine.convergeStalledSendTasks(now: Long) = timeouts.convergeStalledSendTasks(now)

/** 一条发送会话整体收敛（幂等）。 */
internal fun TransferEngine.failSendSession(sessionId: String, reason: SendStallReason) =
    timeouts.failSendSession(sessionId, reason)

/** 会话失败收敛（幂等）：改状态 + 写任务行 + 清 PIN 弹层。 */
internal fun TransferEngine.failSession(session: IncomingSession, code: Int, message: String) =
    timeouts.failSession(session, code, message)

internal fun TransferEngine.refreshDevices() {
    val now = System.currentTimeMillis()
    val snapshot = registry.snapshot(now)
    val known = _devices.value.map { it.deviceId }.toSet()
    _devices.value = snapshot
    if (snapshot.any { it.deviceId !in known && it.online }) {
        playSound(TransferSoundPlayer.Event.NEW_DEVICE)
    }
}

