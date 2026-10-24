package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：发现线程的启停与可见性联动。
// 「什么时候该发组播」由 ServiceModePolicy 判定，这里只负责按判定结果起停。

import com.bsfeng.monitor.transfer.discovery.UdpDiscoveryService
import kotlinx.coroutines.launch

/** 发现控制。 */
internal class TransferDiscoveryCoordinator(private val engine: TransferEngine) {

    private val settings get() = engine.settings
    private val scope get() = engine.scope
    private val registry get() = engine.registry
    private val _discoverable get() = engine._discoverable

    private var discovery: UdpDiscoveryService?
        get() = engine.discovery
        set(value) { engine.discovery = value }

    private var visible: Boolean
        get() = engine.appVisible
        set(value) { engine.appVisible = value }

    fun setDiscoverable(enabled: Boolean) {
        settings.discoverable = enabled
        _discoverable.value = enabled
        if (!engine.started.get()) return
        if (enabled &&
            ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, visible, discoverable = true)
        ) {
            // 发现线程可能尚未创建（服务启动时 App 可见性还是 false），统一走 ensureDiscovery
            ensureDiscovery()
        } else {
            discovery?.stop()
            discovery = null
            engine.refreshDevices()
        }
    }

    /**
     * 修改本机名称（对端设备列表里显示的名字）。
     *
     * 发现线程在构造时取别名，因此改名后重建一次公告线程，下一轮公告即生效。
     */
    fun setAlias(value: String) {
        settings.alias = value
        if (!engine.started.get()) return
        discovery?.stop()
        discovery = null
        if (ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, visible, settings.discoverable)) {
            ensureDiscovery()
        }
    }

    /**
     * App 前后台切换：更新可见标记，并按 [ServiceModePolicy] 启停发现线程。
     *
     * 「后台接收」关闭时，退到后台就停掉组播接收线程（服务本身由 UI 层 stopSelf）；
     * 常驻模式下发现线程保持运行。
     */
    fun setAppVisible(visible: Boolean) {
        this.visible = visible
        // 历史任务与「接收开关」无关：进入前台就恢复一次。
        // 否则重启 App 后要先去点「开启接收」才能看到上次的传输记录（用户会以为记录丢了）。
        if (visible && engine.historyLoaded.compareAndSet(false, true)) {
            scope.launch { engine.loadPersistedHistory() }
        }
        val shouldDiscover = ServiceModePolicy.shouldRunDiscovery(
            backgroundReceive = settings.backgroundReceive,
            appVisible = visible,
            discoverable = settings.discoverable,
        )
        val udp = discovery
        if (shouldDiscover) {
            if (engine.started.get() && settings.discoverable && (udp == null || !udp.isRunning())) {
                ensureDiscovery()
            }
        } else {
            udp?.stop()
            engine.refreshDevices()
        }
    }

    /** 按当前设置重建发现线程（服务 / 可见性变化后调用）。 */
    fun ensureDiscovery() {
        if (!engine.started.get()) return
        if (discovery?.isRunning() == true) return
        val udp = UdpDiscoveryService(
            context = engine.appContext,
            deviceId = settings.deviceId,
            alias = settings.alias,
            appVersion = engine.appVersion(),
            httpPort = { engine.localPort },
            registry = registry,
            onDeviceSeen = { engine.refreshDevices() },
        )
        discovery = udp
        udp.start(scope)
    }
}
