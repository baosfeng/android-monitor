package com.bsfeng.monitor.transfer.service

/**
 * 服务运行模式（纯 Kotlin，可纯 JVM 单测）。
 *
 * 「后台接收」是省电开关，默认**关闭**：
 * - 关闭（[Mode.APP_ONLY]）：只在 App 可见时运行服务——进入前台启动监听，退到后台 / 被划掉即停服务、
 *   关闭通知，从别人的设备列表里消失；
 * - 开启（[Mode.PERSISTENT]）：常驻前台服务 + 常驻通知（本轮之前的旧行为）。
 *
 * 通知、发现线程、服务本身都按这里的判定启停，避免三处各写一套条件。
 */
object ServiceModePolicy {

    /** 运行模式。 */
    enum class Mode { APP_ONLY, PERSISTENT }

    /** 由设置推导运行模式。 */
    fun mode(backgroundReceive: Boolean): Mode =
        if (backgroundReceive) Mode.PERSISTENT else Mode.APP_ONLY

    /** 服务是否应当运行：常驻模式一直运行；仅前台模式只在 App 可见时运行。 */
    fun shouldRunService(backgroundReceive: Boolean, appVisible: Boolean): Boolean =
        backgroundReceive || appVisible

    /** 是否进入前台（并挂常驻通知）：只有常驻模式需要。 */
    fun shouldRunForeground(backgroundReceive: Boolean): Boolean = backgroundReceive

    /** 发现线程是否应当运行：用户开着「可被发现」，且（常驻 或 App 可见）。 */
    fun shouldRunDiscovery(
        backgroundReceive: Boolean,
        appVisible: Boolean,
        discoverable: Boolean,
    ): Boolean = discoverable && shouldRunService(backgroundReceive, appVisible)

    /** 传送页顶部的模式提示。 */
    fun modeHint(backgroundReceive: Boolean): String =
        if (backgroundReceive) {
            "后台接收已开启 · 常驻通知"
        } else {
            "仅应用内可见 · 后台接收已关闭"
        }
}
