package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier

/**
 * 一个分段项：显示文案 + 锚点（s18-mode-aggregate / s18-plan-legacy 等逐字照抄）。
 *
 * 为什么单独成文件（基建审计 I4）：它是 data class 不是 @Composable，跟 UI 文件一起被
 * 覆盖率排除就等于「声明在、永远不被度量」。同文件放下标↔语义的换算，因为这两个方向
 * 分散在调用点里写（`if (aggregate) 0 else 1` 与 `index == 0`）时，改了一边不改另一边
 * 会让「选中聚合」变成「选中单家」而没有任何测试变红。
 */
internal data class QuotaSegment(val label: String, val tagModifier: Modifier)

/** 视图模式 → 分段下标：0 = 聚合，1 = 单家，与 ViewModeSwitch 里选项的顺序同源。 */
internal fun viewModeIndex(aggregate: Boolean): Int = if (aggregate) 0 else 1

/** 分段下标 → 是否聚合（onSelect 回调把下标换回语义时用）。 */
internal fun isAggregateIndex(index: Int): Boolean = index == 0
