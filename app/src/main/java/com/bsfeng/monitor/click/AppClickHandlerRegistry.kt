package com.bsfeng.monitor.click

/**
 * AppClickHandler 注册表。
 *
 * 根据包名查找对应的专用点击处理器，找不到则走默认逻辑。
 */
object AppClickHandlerRegistry {

    private val handlers: Map<String, AppClickHandler> = mapOf(
        WeChatClickHandler().packageName to WeChatClickHandler()
    )

    /**
     * 获取指定包名的专用处理器。
     * @param packageName 应用包名，如 "com.tencent.mm"
     * @return 对应的处理器，没有则为 null
     */
    fun getHandler(packageName: String): AppClickHandler? = handlers[packageName]

    /**
     * 注册自定义处理器（供扩展使用）。
     */
    fun register(handler: AppClickHandler) {
        @Suppress("UNCHECKED_CAST")
        (handlers as MutableMap)[handler.packageName] = handler
    }
}
