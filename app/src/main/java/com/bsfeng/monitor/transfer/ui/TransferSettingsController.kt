package com.bsfeng.monitor.transfer.ui

import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.transfer.service.TransferEngine
import kotlinx.coroutines.flow.MutableStateFlow

// 从 transfer/ui/TransferViewModel.kt 拆出：「免配对窗口 / 提示音 / 阈值 / 接收目录」这四项设置的读写。
// 负责「改设置后怎么刷新状态流」；状态流本身与嵌套类型仍留在 TransferViewModel（对外 API 不变）。

/**
 * 设置项的委托目标（[TransferViewModel] 内部使用）。
 *
 * 每个 setter 的模式固定：先写引擎的 [com.bsfeng.monitor.transfer.service.TransferSettings]，
 * 再把结果回读进状态流 —— 读一次而不是拼一次，保证 UI 看到的一定是引擎里的真值。
 */
internal class TransferSettingsController(
    private val engine: TransferEngine,
    private val openPairingState: MutableStateFlow<TransferViewModel.OpenPairingState>,
    private val thresholdState: MutableStateFlow<Int>,
    private val receivedStatsState: MutableStateFlow<com.bsfeng.monitor.transfer.transport.DownloadsSink.ReceivedStats>,
    private val soundState: MutableStateFlow<TransferViewModel.TransferSoundState>,
) {

    /** 开关免配对窗口：开启时从此刻起算当前时长，关闭立即回到需配对。 */
    fun setOpenPairingWindow(enabled: Boolean) {
        val settings = engine.settings()
        if (enabled) settings.startOpenPairingWindow() else settings.stopOpenPairingWindow()
        openPairingState.value = readOpenPairing()
    }

    /** 切换免配对窗口时长（开启状态下会以新时长重新计时）。 */
    fun setOpenPairingMinutes(minutes: Int) {
        val settings = engine.settings()
        settings.openPairingMinutes = minutes
        if (settings.isOpenPairingActive()) settings.startOpenPairingWindow(minutes = minutes)
        openPairingState.value = readOpenPairing()
    }

    /** 免配对窗口剩余毫秒（UI 每秒刷新）。 */
    fun openPairingRemainingMs(): Long = engine.settings().openPairingRemainingMs()

    /** 切换体积阈值（MB；0 = 关闭）。 */
    fun setAutoApproveThresholdMb(mb: Int) {
        engine.settings().autoApproveThresholdMb = mb
        thresholdState.value = mb
    }

    /** 刷新接收目录占用（IO 由调用方所在协程调度决定）。 */
    fun refreshReceivedStats() {
        receivedStatsState.value = engine.receivedStats()
    }

    /** 清理接收目录并清空对应历史；返回删除条数。 */
    fun clearReceivedHistory(): Int {
        val removed = engine.clearReceivedHistory()
        receivedStatsState.value = engine.receivedStats()
        return removed
    }

    /** 切换提示音开关。 */
    fun setSound(enabled: Boolean, kind: TransferViewModel.SoundKind) {
        val settings = engine.settings()
        when (kind) {
            TransferViewModel.SoundKind.COMPLETE -> settings.soundOnComplete = enabled
            TransferViewModel.SoundKind.FAILURE -> settings.soundOnFailure = enabled
            TransferViewModel.SoundKind.NEW_DEVICE -> settings.soundOnNewDevice = enabled
        }
        soundState.value = readSoundSettings()
    }

    private fun readOpenPairing(): TransferViewModel.OpenPairingState =
        readOpenPairing(engine)

    private fun readSoundSettings(): TransferViewModel.TransferSoundState =
        readSoundSettings(engine)

    companion object {
        /** 免配对窗口状态快照（[TransferViewModel] 初始化状态流时也用它）。 */
        fun readOpenPairing(engine: TransferEngine): TransferViewModel.OpenPairingState {
            val settings = engine.settings()
            return TransferViewModel.OpenPairingState(
                enabled = settings.openPairingEnabled,
                minutes = settings.openPairingMinutes,
                expiresAtMs = settings.openPairingExpiresAtMs,
            )
        }

        /** 提示音开关快照（[TransferViewModel] 初始化状态流时也用它）。 */
        fun readSoundSettings(engine: TransferEngine): TransferViewModel.TransferSoundState {
            val settings = engine.settings()
            return TransferViewModel.TransferSoundState(
                complete = settings.soundOnComplete,
                failure = settings.soundOnFailure,
                newDevice = settings.soundOnNewDevice,
            )
        }

        fun isWindowActive(expiresAtMs: Long, nowMs: Long): Boolean =
            PairingPolicy.isWindowActive(expiresAtMs, nowMs)

        fun remainingMs(expiresAtMs: Long, nowMs: Long): Long =
            PairingPolicy.remainingMs(expiresAtMs, nowMs)
    }
}
