package com.bsfeng.monitor.transfer.service

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bsfeng.monitor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴（下拉通知栏一键开关收发）。
 *
 * - 状态源是 [TransferEngine.running]（服务真的在跑 = ACTIVE）：服务被系统杀掉时磁贴自动回到 INACTIVE；
 * - 点击切换：未运行 → 打开「后台接收」并启动前台服务；运行中 → 关闭「后台接收」并停止服务（通知一并移除）；
 * - 与设置页「后台接收」开关双向同步：设置改动后调用 [requestRefresh] 让磁贴重读状态；
 * - 图标用矢量 `R.drawable.ic_transfer_tile`（Material swap_horiz），不引入位图资源。
 */
class TransferTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        // 服务启停 / 设备数变化都要同步到磁贴（例如系统回收服务 → INACTIVE）
        scope.launch {
            TransferEngine.getInstance(this@TransferTileService).running.collect { updateTile() }
        }
        scope.launch {
            TransferEngine.getInstance(this@TransferTileService).devices.collect { updateTile() }
        }
    }

    override fun onStopListening() {
        scope.coroutineContext.cancelChildren()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val engine = TransferEngine.getInstance(this)
        val settings = TransferSettings(this)
        if (engine.running.value) {
            // 关：停止服务与发现，端口释放、通知移除；「可被发现」同步关闭
            settings.backgroundReceive = false
            settings.discoverable = false
            engine.setDiscoverable(false)
            TransferService.stop(this)
            engine.stop()
        } else {
            // 开：等价于「后台接收」开启 + 「可被发现」开启 + 前台服务启动
            settings.backgroundReceive = true
            settings.discoverable = true
            TransferService.start(this)
        }
        updateTile()
    }

    /** 刷新磁贴（标签 / 副标题 / 激活态）。 */
    private fun updateTile() {
        val engine = TransferEngine.getInstance(this)
        val snapshot = TransferTileState.of(
            running = engine.running.value,
            onlineDevices = engine.devices.value.count { it.online },
        )
        qsTile?.apply {
            state = if (snapshot.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = snapshot.label
            subtitle = snapshot.subtitle
            icon = Icon.createWithResource(this@TransferTileService, R.drawable.ic_transfer_tile)
            updateTile()
        }
    }

    companion object {
        /** 让系统重读磁贴状态（设置页切换「后台接收」后调用）。 */
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, TransferTileService::class.java),
                )
            }
        }
    }
}
