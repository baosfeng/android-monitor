package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：五态的演示控制 —— StateSwitchRow（加载/空/错/有数据/边界 切换条）。
// 负责「调试时切五态」；仍是 ui 包内的共用件（internal）。
//
// 演示件闸门统一走 rememberDemoVisible()（debug 构建 + 已解锁 + 调试模式已开），
// 与「组件与状态演示」「模拟自动记账浮窗」同一判据，不再有「两层闸 / 三层闸」并存。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五态切换条（演示用）。
 * 显示条件走统一判据 [rememberDemoVisible]：debug 构建 + 已解锁 + 调试模式已开；release 恒为真数据。
 */
@Composable
internal fun StateSwitchRow(state: FiveState, onSelect: (FiveState) -> Unit, modifier: Modifier = Modifier) {
    if (!rememberDemoVisible()) return
    val labels = listOf("加载态", "空态", "错误态", "有数据", "边界态")
    Column(modifier.fillMaxWidth().padding(horizontal = LocalDesignTokens.current.space4)) {
        ChipRow(
            items = labels,
            selectedIndex = FiveState.values().indexOf(state),
            onSelect = { onSelect(FiveState.values()[it]) },
        )
    }
}
