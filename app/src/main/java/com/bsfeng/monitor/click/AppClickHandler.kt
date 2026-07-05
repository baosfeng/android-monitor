package com.bsfeng.monitor.click

import android.view.accessibility.AccessibilityEvent

/**
 * App 专用的点击文本处理器接口。
 *
 * 某些 App（如微信、企业微信）的点击事件文本提取需要针对其特有视图结构进行优化。
 * 如有对应实现类则优先使用，否则走默认提取逻辑。
 */
interface AppClickHandler {

    /** 该处理器适用的包名 */
    val packageName: String

    /**
     * 获取比默认 [clickText] 更合适的点击显示文本。
     *
     * 当默认提取的文本为时间戳、viewId 等无意义内容时，处理器可尝试从
     * 视图树中（如父容器、兄弟节点）找到更有意义的文本。
     *
     * @param event 原始 AccessibilityEvent
     * @param clickText 默认文本提取结果（可能为 null 或短字符串）
     * @return 更合适的显示文本，返回 null 表示使用默认逻辑
     */
    fun resolveClickDisplayText(event: AccessibilityEvent, clickText: String?): String?
}
