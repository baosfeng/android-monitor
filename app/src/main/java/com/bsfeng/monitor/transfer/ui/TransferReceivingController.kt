package com.bsfeng.monitor.transfer.ui

import android.app.Application
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.service.TransferTileService
import kotlinx.coroutines.flow.MutableStateFlow

// 从 transfer/ui/TransferViewModel.kt 拆出：「接收 / 发现」开关对引擎与服务的编排。
// 负责「什么时候启动/停止服务、什么时候标记可见」；状态流本身仍由 TransferViewModel 持有。

/**
 * 接收侧开关的编排器（[TransferViewModel] 的委托目标）。
 *
 * 与引擎的分工：本类只决定「调哪个引擎方法、什么时候起停服务」，
 * 真正的网络/磁盘动作全在 [TransferEngine] 里（IO 线程）。
 *
 * 时序约定（原样保留自 ViewModel，改动会复现旧 bug）：
 * - 开接收时先 `setAppVisible(true)` 再启动服务，避免「服务已起但发现线程按不可见判定不启动」；
 * - 关接收时同步关「可被发现」，保证「磁贴关了、App 里就真的是关的」。
 */
internal class TransferReceivingController(
    private val engine: TransferEngine,
    private val application: Application,
    private val serviceStarted: MutableStateFlow<Boolean>,
    private val discoverableState: MutableStateFlow<Boolean>,
    private val backgroundReceiveState: MutableStateFlow<Boolean>,
) {

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
        serviceStarted.value = true
        TransferService.start(application)
        discoverableState.value = true
    }

    /** 关闭接收：停服务（端口释放、对端列表消失），并同步关闭「可被发现」。 */
    fun stopReceiving() {
        val settings = engine.settings()
        settings.backgroundReceive = false
        settings.discoverable = false
        engine.setDiscoverable(false)
        TransferService.stop(application)
        discoverableState.value = false
        backgroundReceiveState.value = false
    }

    /** 兼容旧调用点：不再自动启动服务，仅记录一次。 */
    fun ensureStarted() {
        serviceStarted.value = true
    }

    /** App 回到前台：只更新可见性，不自动开启接收。 */
    fun onAppForeground() {
        if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) return
        engine.setAppVisible(true)
    }

    /** App 退到后台：停发现；「后台接收」关闭时连服务一起停（对端列表随之消失）。 */
    fun onAppBackground() {
        engine.setAppVisible(false)
        if (!engine.settings().backgroundReceive) TransferService.stop(application)
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
            TransferService.start(application)
        } else {
            TransferService.refresh(application)
        }
        TransferTileService.requestRefresh(application)
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
}
