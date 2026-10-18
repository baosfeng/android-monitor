package com.bsfeng.monitor.transfer.service

/**
 * 快捷设置磁贴的状态映射（纯 Kotlin，可纯 JVM 单测）。
 *
 * 磁贴是「后台接收」的快捷入口：实际运行状态以 [TransferEngine.running] 为准，
 * 服务被系统杀掉时磁贴必须回到 INACTIVE（见 `TransferTileService` 对 running 的订阅）。
 */
object TransferTileState {

    /** 磁贴固定标签。 */
    const val LABEL: String = "局域网互传"

    /** 未运行时的副标题。 */
    const val SUBTITLE_OFF: String = "已关闭"

    /** 磁贴快照。 */
    data class Snapshot(
        val active: Boolean,
        val label: String,
        val subtitle: String,
    )

    /**
     * 按运行状态与在线设备数生成快照。
     *
     * @param running 服务/引擎是否在运行。
     * @param onlineDevices 在线设备数（负值按 0 处理）。
     */
    fun of(running: Boolean, onlineDevices: Int): Snapshot =
        if (running) {
            Snapshot(
                active = true,
                label = LABEL,
                subtitle = onlineDevices.coerceAtLeast(0).toString() + " 台设备",
            )
        } else {
            Snapshot(active = false, label = LABEL, subtitle = SUBTITLE_OFF)
        }
}
