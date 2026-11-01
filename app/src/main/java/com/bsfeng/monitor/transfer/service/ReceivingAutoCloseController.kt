package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：「自动关闭接收」倒计时的状态与循环。
// 引擎只负责在启停 / 设置变更时通知这里，具体计时与到点动作都在本类。

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「自动关闭接收」倒计时。
 *
 * 口径：
 * - 起算只在内存，进程重启不恢复（重启后重新按设置计时）；
 * - 分段 `delay` 而非一次睡满：设置变更或手动关闭时 `cancel()` 立即生效，不留悬挂任务；
 * - 到点先发事件让宿主服务收尾（移除通知 / 磁贴回 INACTIVE），再调用 [onExpired] 兜底自停。
 */
internal class ReceivingAutoCloseController(
    private val scope: CoroutineScope,
    private val settings: TransferSettings,
    private val events: MutableSharedFlow<Unit>,
    private val onExpired: () -> Unit,
) {
    private val countdown = AutoCloseCountdown()
    private var job: Job? = null

    private val _deadline = MutableStateFlow(0L)
    val deadline: MutableStateFlow<Long> get() = _deadline

    /** 当前剩余毫秒（未计时返回 0）。 */
    fun remainingMs(nowMs: Long): Long = ReceivingAutoClose.remainingMs(_deadline.value, nowMs)

    /**
     * 按当前设置起算倒计时（服务启动成功后调用）。
     *
     * 幂等：每次调用先清掉旧任务，因此「重新开启接收」= 重新计时、改设置 = 用新时长重算。
     */
    fun start() {
        cancel()
        val minutes = ReceivingAutoClose.normalize(settings.autoCloseMinutes)
        if (minutes == ReceivingAutoClose.OFF) return
        countdown.start(minutes = minutes)
        _deadline.value = countdown.deadlineMs()
        Log.i(TAG, "自动关闭接收已计时：$minutes 分钟后停止")
        job = scope.launch { loop() }
    }

    /** 取消计时并清理任务（手动关闭服务、设置改回关闭、服务销毁）。 */
    fun cancel() {
        job?.cancel()
        job = null
        countdown.cancel()
        _deadline.value = 0L
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (countdown.shouldStop()) {
                Log.i(TAG, "自动关闭接收：到点，停止服务")
                // 先通知宿主（主线程执行 shutdown），没有监听者时 onExpired 也保证服务真的停
                events.tryEmit(Unit)
                onExpired()
                break
            }
            delay(countdown.remainingMs().coerceAtLeast(1L).coerceAtMost(TICK_MS))
        }
    }

    private companion object {
        const val TAG = "TransferEngine"

        /** 倒计时的检查间隔上限（到点判定误差 ≤ 1s）。 */
        const val TICK_MS = 1_000L
    }
}
