package com.bsfeng.monitor.ui

// 从 ui/MainViewModel.kt 拆出：实时页的数据状态 MonitorState（列表 / 计数 / 五态标志 / 热力条 / 类型计数）。
// 负责「一屏要展示哪些字段」，不含任何加载逻辑（逻辑在 MainViewModel）。

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.AppUsageStat

/**
 * 实时页（s02）的数据快照。
 *
 * 每次状态变化都是整个 data class 的 copy —— 调用方用 `if (next != old)` 判等来决定
 * 是否真的 emit，避免每 3s 轮询都触发整页重组（见 [MainViewModel.refreshData]）。
 */
data class MonitorState(
    val isMonitoring: Boolean = true,
    /** 当前已加载的事件列表（分页） */
    val recentEvents: List<Record> = emptyList(),
    /** 今天的事件总数 */
    val todayEventCount: Int = 0,
    /** 是否正在加载更多 */
    val isLoadingMore: Boolean = false,
    /** 是否还有更多数据可加载 */
    val hasMore: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    /** 无障碍服务实例是否已连接（用于区分"已勾选但未连接"） */
    val accessibilityServiceRunning: Boolean = false,
    val appUsageStats: List<AppUsageStat> = emptyList(),
    val screenEvents: List<Record> = emptyList(),
    /** 首屏加载中（s02 五态之 loading） */
    val isLoading: Boolean = true,
    /** 加载失败原因（s02 五态之 error）；null = 正常 */
    val loadError: String? = null,
    /** 今日逐时事件强度（24 格热力条） */
    val hourlyIntensity: List<Float> = List(24) { 0f },
    /** 今日各类型事件条数（s02 类型 chip 的计数） */
    val typeCounts: Map<RecordType, Int> = emptyMap(),
)
