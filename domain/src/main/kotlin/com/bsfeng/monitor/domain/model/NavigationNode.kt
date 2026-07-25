package com.bsfeng.monitor.domain.model

/**
 * 导航链路中的一个节点。
 *
 * @param name 节点名称（如 "微信"、"发现"、"公众号"、"文章标题"）
 * @param type 节点类型（app / section / sub_section / content）
 * @param className 对应的 Android Activity 类名（用于判重退栈）
 * @param nodeTitle 页面标题原始文本（可能为 null）
 * @param timestampMs 进入该节点的时间戳
 */
data class NavigationNode(
    val name: String,
    val type: String,
    val className: String?,
    val nodeTitle: String?,
    val timestampMs: Long
)

/**
 * 导航变更结果。
 *
 * @param path 当前完整的导航路径栈（从头到尾）
 * @param isNewPath 是否为有效的新路径变更（用于决定是否发射事件）
 * @param changedIndex 发生变化的节点索引（-1 表示根变化）
 */
data class NavChangeResult(
    val path: List<NavigationNode>,
    val isNewPath: Boolean,
    val changedIndex: Int
)
