package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 顶部视图分段的两个选项（顺序 = 显示顺序，索引 0 为聚合）。 */
private const val VIEW_AGGREGATE = "聚合"
private const val VIEW_SINGLE = "单家"

/**
 * 「聚合 / 单家」分段切换（原型 s20a 顶部 .segmented）。
 *
 * 放在 provider chip 上方而不是与 chip 同一行：两者都要整行宽度才不挤，
 * 而聚合模式下 chip 整行消失，切换器留在原地不会让内容上下跳。
 */
@Composable
internal fun ViewModeSwitch(
    aggregateMode: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    SegmentedControl(
        options = listOf(VIEW_AGGREGATE, VIEW_SINGLE),
        selectedIndex = if (aggregateMode) 0 else 1,
        onSelect = { index ->
            val next = index == 0
            if (next == aggregateMode) return@SegmentedControl
            // 视图切换等价于 Tab 切换：中触，与行内点开的轻触区分
            haptic(HapticTone.MEDIUM)
            onSelect(next)
        },
        modifier = Modifier.padding(horizontal = t.space4),
    )
}
