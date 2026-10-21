package com.bsfeng.monitor.transfer.ui

import android.app.Application
import android.net.Uri
import android.view.View
import androidx.lifecycle.AndroidViewModel
import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.service.TransferTileService
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「传送」Tab 的状态与事件（协议 §8 的 `TransferViewModel`）。
 *
 * 只做三件事：把 [TransferEngine] 的 Flow 透出、把 UI 事件转成引擎调用、维护「选中设备 / 提示音开关」这些纯 UI 状态。
 * 网络与磁盘全部在引擎里（IO 线程），ViewModel 不做阻塞操作。
 */
class TransferViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = TransferEngine.getInstance(application)

    init {
        // ViewModel 由 UI 创建即代表 App 在前台：显式标记可见。
        // 只依赖 Activity 的 ON_START 时，观察者注册晚于事件会漏掉首帧，
        // 表现为「开启接收后端口在跑、组播/广播不发、对端搜不到本机」。
        // 功能关闭时不碰引擎：UI 层本就不该创建本 VM，这里再兜一层。
        if (FeatureFlags.get().isEnabled(Feature.TRANSPORT)) engine.setAppVisible(true)
    }

    /** 在线设备。 */
    val devices: StateFlow<List<PeerDevice>> = engine.devices

    /** 收发任务。 */
    val tasks: StateFlow<List<TransferTask>> = engine.tasks

    /** 删除事件（UI 开 5s 撤销窗口）。 */
    val deletedTasks: SharedFlow<DeletedTask> = engine.deletedTasks

    /** 撤销窗口时长（协议外交互，统一 5s）。 */
    val undoWindowMs: Long get() = TransferEngine.UNDO_WINDOW_MS

    /** PIN 交互（接收端展示 / 发送端输入）。 */
    val pinPrompt: StateFlow<TransferEngine.PinPrompt?> = engine.pinPrompt

    /** 端口是否常驻。 */
    val running: StateFlow<Boolean> = engine.running

    /** 本端设备信息。 */
    val alias: String get() = engine.alias
    val deviceId: String get() = engine.deviceId

    /** 本机名称（可改名；改名后传送页顶部与设置页立即刷新）。 */
    private val aliasState = MutableStateFlow(engine.alias)
    val aliasFlow: StateFlow<String> = aliasState.asStateFlow()

    /** 修改本机名称（对端看到的名字）；空串回落到系统设备名。 */
    fun setAlias(value: String) {
        engine.setAlias(value)
        aliasState.value = engine.alias
    }

    /** 实际端口（引擎启动后才有值）。 */
    val localPort: Int get() = engine.localPort

    private val selected = MutableStateFlow<String?>(null)
    private val soundState = MutableStateFlow(readSoundSettings())
    private val started = MutableStateFlow(false)
    private val discoverableState = MutableStateFlow(engine.settings().discoverable)
    private val openPairingState = MutableStateFlow(readOpenPairing())
    private val thresholdState = MutableStateFlow(engine.settings().autoApproveThresholdMb)
    private val receivedStatsState = MutableStateFlow(DownloadsSink.ReceivedStats(0, 0L))
    private val backgroundReceiveState = MutableStateFlow(engine.settings().backgroundReceive)
    private val autoCloseMinutesState = MutableStateFlow(engine.settings().autoCloseMinutes)

    /** 当前选中的发送目标设备 id。 */
    val selectedDeviceId: StateFlow<String?> = selected.asStateFlow()

    /** 提示音开关（对应协议 §7 三项默认值）。 */
    val soundSettings: StateFlow<TransferSoundState> = soundState.asStateFlow()

    /** 是否已请求过常驻服务。 */
    val serviceStarted: StateFlow<Boolean> = started.asStateFlow()

    /** 「可被发现」开关状态。 */
    val discoverable: StateFlow<Boolean> = discoverableState.asStateFlow()

    /** 免配对窗口状态（开关 + 时长 + 到期时刻）。 */
    val openPairing: StateFlow<OpenPairingState> = openPairingState.asStateFlow()

    /** 体积阈值（MB，0 = 关闭）。 */
    val autoApproveThresholdMb: StateFlow<Int> = thresholdState.asStateFlow()

    /** 接收目录占用（设置页展示与清理入口）。 */
    val receivedStats: StateFlow<DownloadsSink.ReceivedStats> = receivedStatsState.asStateFlow()

    /** 「后台接收」开关（默认关闭：仅 App 可见时运行）。 */
    val backgroundReceive: StateFlow<Boolean> = backgroundReceiveState.asStateFlow()

    /** 「自动关闭接收」时长（分钟；0 = 关闭，默认）。 */
    val autoCloseMinutes: StateFlow<Int> = autoCloseMinutesState.asStateFlow()

    /** 自动关闭的到期时刻（0 = 未计时）；UI 据此显示剩余时间。 */
    val autoCloseDeadline: StateFlow<Long> = engine.autoCloseDeadline

    /** 切换「自动关闭接收」时长（0 = 关闭）；运行中立即按新时长重新计时。 */
    fun setAutoCloseMinutes(minutes: Int) {
        val normalized = ReceivingAutoClose.normalize(minutes)
        engine.settings().autoCloseMinutes = normalized
        autoCloseMinutesState.value = normalized
        engine.refreshAutoCloseSchedule()
    }

    /**
     * 开启接收（传送页开关 / 按钮）：打开「可被发现」并启动服务。
     *
     * App 启动时**不会**自动调用本方法——接收只由用户显式开启（磁贴或传送页），
     * 保证「磁贴关了、App 里就真的是关的」。
     */
    fun startReceiving() {
        // 功能关闭：不开「可被发现」、不拉服务（磁贴 / 旧入口遗留调用也走这里）
        if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) return
        val settings = engine.settings()
        settings.discoverable = true
        // 用户此刻就在界面上操作：先标记可见，再启动服务，
        // 避免「服务已启动但发现线程按不可见判定不启动」的半启动状态。
        engine.setAppVisible(true)
        engine.setDiscoverable(true)
        started.value = true
        TransferService.start(getApplication())
        discoverableState.value = true
    }

    /** 关闭接收：停服务（端口释放、对端列表消失），并同步关闭「可被发现」。 */
    fun stopReceiving() {
        val settings = engine.settings()
        settings.backgroundReceive = false
        settings.discoverable = false
        engine.setDiscoverable(false)
        TransferService.stop(getApplication())
        discoverableState.value = false
        backgroundReceiveState.value = false
    }

    /** 兼容旧调用点：不再自动启动服务，仅记录一次。 */
    fun ensureStarted() {
        started.value = true
    }

    /** App 回到前台：只更新可见性，不自动开启接收。 */
    fun onAppForeground() {
        if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) return
        engine.setAppVisible(true)
    }

    /** App 退到后台：停发现；「后台接收」关闭时连服务一起停（对端列表随之消失）。 */
    fun onAppBackground() {
        engine.setAppVisible(false)
        if (!engine.settings().backgroundReceive) TransferService.stop(getApplication())
    }

    /** 切换「后台接收」：开启→常驻前台服务；关闭→退前台、仅 App 可见时运行。 */
    fun setBackgroundReceive(enabled: Boolean) {
        if (enabled && !FeatureFlags.get().isEnabled(Feature.TRANSPORT)) return
        engine.settings().backgroundReceive = enabled
        backgroundReceiveState.value = enabled
        if (enabled) {
            // 后台接收 = 允许服务常驻；同时把「可被发现」打开，避免服务跑着却搜不到
            engine.settings().discoverable = true
            discoverableState.value = true
            TransferService.start(getApplication())
        } else {
            TransferService.refresh(getApplication())
        }
        TransferTileService.requestRefresh(getApplication())
    }

    /** 通知动作「暂停 5 分钟」的等价入口（设置页/调试用）。 */
    fun pauseOffers() {
        engine.pauseOffers()
    }

    /** 立即恢复接收。 */
    fun resumeOffers() {
        engine.resumeOffers()
    }

    /** 当前是否暂停接收。 */
    fun isOffersPaused(): Boolean = engine.isOffersPaused()

    /** 选中 / 取消选中发送目标。 */
    fun selectDevice(deviceId: String?) {
        selected.value = if (selected.value == deviceId) null else deviceId
    }

    /** 把选中的文件发给选中设备。 */
    fun sendFiles(uris: List<Uri>) {
        val deviceId = selected.value ?: return
        val peer = devices.value.firstOrNull { it.deviceId == deviceId } ?: return
        engine.sendFiles(peer, uris)
    }

    /** 发送端输入 PIN 后继续。 */
    fun submitPin(pin: String) {
        engine.submitPin(pin)
    }

    /** 关闭 PIN 弹层（发送端取消）。 */
    fun dismissPin() {
        engine.dismissPin()
    }

    /** 接收端拒绝。 */
    fun denyIncoming(sessionId: String) {
        engine.denyIncoming(sessionId)
    }

    /** 取消任务。 */
    fun cancelTask(sessionId: String) {
        engine.cancelTask(sessionId)
    }

    /** 删除一条已终结任务（记录 + 已落盘接收文件）；进行中任务会被引擎拒绝。 */
    fun deleteTask(sessionId: String, fileId: String) {
        engine.deleteTask(sessionId, fileId)
    }

    /** 撤销删除：返回快照，`fileDeleted=true` 时 UI 提示「文件已删除，仅恢复记录」。 */
    fun undoDelete(deletionId: String): DeletedTask? = engine.undoDelete(deletionId)

    /** 撤销窗口结束：放弃恢复。 */
    fun commitDelete(deletionId: String) {
        engine.commitDelete(deletionId)
    }

    /** 移除配对。 */
    fun removePairing(deviceId: String) {
        engine.removePairing(deviceId)
    }

    /** 已配对设备。 */
    fun pairedDeviceIds(): Set<String> = engine.pairedDeviceIds()

    /** 触觉反馈目标（Compose 的 `LocalView.current`）。 */
    fun setHapticTarget(view: View?) {
        engine.setHapticTarget(view)
    }

    /** 「可被发现」开关：关闭后停止 UDP 公告 / 应答，对端列表清空。 */
    fun setDiscoverable(enabled: Boolean) {
        engine.setDiscoverable(enabled)
        discoverableState.value = enabled
    }

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

    /** 切换体积阈值（MB；0 = 关闭）。 */
    fun setAutoApproveThresholdMb(mb: Int) {
        engine.settings().autoApproveThresholdMb = mb
        thresholdState.value = mb
    }

    /** 免配对窗口剩余毫秒（UI 每秒刷新）。 */
    fun openPairingRemainingMs(): Long = engine.settings().openPairingRemainingMs()

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

    private fun readOpenPairing(): OpenPairingState {
        val settings = engine.settings()
        return OpenPairingState(
            enabled = settings.openPairingEnabled,
            minutes = settings.openPairingMinutes,
            expiresAtMs = settings.openPairingExpiresAtMs,
        )
    }

    /** 切换提示音开关。 */
    fun setSound(enabled: Boolean, kind: SoundKind) {
        val settings = engine.settings()
        when (kind) {
            SoundKind.COMPLETE -> settings.soundOnComplete = enabled
            SoundKind.FAILURE -> settings.soundOnFailure = enabled
            SoundKind.NEW_DEVICE -> settings.soundOnNewDevice = enabled
        }
        soundState.value = readSoundSettings()
    }

    private fun readSoundSettings(): TransferSoundState {
        val settings = engine.settings()
        return TransferSoundState(
            complete = settings.soundOnComplete,
            failure = settings.soundOnFailure,
            newDevice = settings.soundOnNewDevice,
        )
    }

    /** 提示音开关状态。 */
    data class TransferSoundState(
        val complete: Boolean,
        val failure: Boolean,
        val newDevice: Boolean,
    )

    /** 提示音开关的类型。 */
    enum class SoundKind { COMPLETE, FAILURE, NEW_DEVICE }

    /** 免配对窗口状态。 */
    data class OpenPairingState(
        val enabled: Boolean,
        val minutes: Int,
        val expiresAtMs: Long,
    ) {
        /** 当前是否生效。 */
        fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
            enabled && PairingPolicy.isWindowActive(expiresAtMs, nowMs)

        /** 剩余毫秒。 */
        fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long =
            if (!enabled) 0L else PairingPolicy.remainingMs(expiresAtMs, nowMs)
    }
}
