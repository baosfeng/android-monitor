package com.bsfeng.monitor.transfer.ui

import android.app.Application
import android.net.Uri
import android.view.View
import androidx.lifecycle.AndroidViewModel
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.transfer.service.TransferService
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** 在线设备。 */
    val devices: StateFlow<List<PeerDevice>> = engine.devices

    /** 收发任务。 */
    val tasks: StateFlow<List<TransferTask>> = engine.tasks

    /** PIN 交互（接收端展示 / 发送端输入）。 */
    val pinPrompt: StateFlow<TransferEngine.PinPrompt?> = engine.pinPrompt

    /** 端口是否常驻。 */
    val running: StateFlow<Boolean> = engine.running

    /** 本端设备信息。 */
    val alias: String get() = engine.alias
    val deviceId: String get() = engine.deviceId

    /** 实际端口（引擎启动后才有值）。 */
    val localPort: Int get() = engine.localPort

    private val selected = MutableStateFlow<String?>(null)
    private val soundState = MutableStateFlow(readSoundSettings())
    private val started = MutableStateFlow(false)

    /** 当前选中的发送目标设备 id。 */
    val selectedDeviceId: StateFlow<String?> = selected.asStateFlow()

    /** 提示音开关（对应协议 §7 三项默认值）。 */
    val soundSettings: StateFlow<TransferSoundState> = soundState.asStateFlow()

    /** 是否已请求过常驻服务。 */
    val serviceStarted: StateFlow<Boolean> = started.asStateFlow()

    /** 确保前台服务 / 引擎已启动（幂等，Screen 首次进入时调用）。 */
    fun ensureStarted() {
        if (started.value) return
        started.value = true
        TransferService.start(getApplication())
    }

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
}
