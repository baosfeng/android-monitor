package com.bsfeng.monitor.event

/**
 * 屏幕内容分类信息。
 * 存储当前屏幕的标题、类型（chat/article/feed/video/settings/unknown）及分类置信度。
 */
data class ScreenContent(
    val screenTitle: String?,
    val screenType: String,
    val confidence: Float
)
