package com.bsfeng.monitor.skip

/**
 * 小程序跳过处理器。
 *
 * 微信小程序广告以原生 View 覆盖层形式展示在 WebView 之上，
 * 但广告关闭按钮不一定有独立的无障碍节点。
 * 实际跳过需要：
 * - 使用坐标点击（dispatchGesture）命中覆盖层，而非 ACTION_CLICK
 * - 给广告充分加载时间（matchDelay 控制在 BuiltInRules 中）
 * - 进入小程序时重置计时器，避免用进入微信的旧时间
 *
 * 用法示例：
 * ```
 * if (MiniProgramSkipHandler.isMiniProgram(watchClassName)) {
 *     ActionExecutor.perform(target, MiniProgramSkipHandler.getClickAction())
 * }
 * ```
 */
object MiniProgramSkipHandler {

    /** 小程序 Activity className 的标识子串 */
    private const val APPBRAND_CLASS_PATTERN = "appbrand"

    /**
     * 判断当前 className 是否属于小程序页面。
     *
     * 微信小程序的宿主 Activity 为 `AppBrandUI` / `AppBrandUI00` 等，
     * className 均包含 "appbrand"。
     *
     * @param className 当前窗口的 className，可为 null
     * @return true 表示当前为小程序页面
     */
    fun isMiniProgram(className: String?): Boolean =
        className?.contains(APPBRAND_CLASS_PATTERN) == true

    /**
     * 获取小程序的点击动作。
     *
     * 始终返回 "click_coordinate"：
     * - dispatchGesture 坐标点击能穿透到广告覆盖层
     * - 避免 ACTION_CLICK 直接作用在小程序导航按钮上（会关闭小程序）
     *
     * @return 固定返回 "click_coordinate"
     */
    fun getClickAction(): String = "click_coordinate"
}
