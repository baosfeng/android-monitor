package com.bsfeng.monitor.transfer.service

/**
 * 「接收状态」展示口径（纯 Kotlin，可单测）。
 *
 * 单一真值源是 [TransferEngine.running]：磁贴、设置页「后台接收」、传送页状态区都读它，
 * 因此三处显示与开关状态天然一致——不存在「磁贴关了、App 里还显示开启」的第二套状态。
 */
object ReceivingState {

    /** 状态快照。 */
    data class Snapshot(
        val running: Boolean,
        /** 一行状态文案。 */
        val statusText: String,
        /** 是否显示「开启接收」按钮。 */
        val canStart: Boolean,
    )

    /** 按运行状态生成展示口径。 */
    fun of(running: Boolean, port: Int, onlineDevices: Int): Snapshot =
        if (running) {
            Snapshot(
                running = true,
                statusText = if (onlineDevices > 0) {
                    "端口 $port · 在线 $onlineDevices 台"
                } else {
                    "端口 $port · 等待设备出现"
                },
                canStart = false,
            )
        } else {
            Snapshot(
                running = false,
                statusText = "未开启接收 · 点此开启",
                canStart = true,
            )
        }
}
