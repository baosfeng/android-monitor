package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：五态的演示控制 —— StateSwitchRow（加载/空/错/有数据/边界 切换条）与 FiveStateSlot（按状态渲染对应骨架/空态/错态/边界态）。负责「调试时切五态」。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五态切换条（演示用）。
 * 显示条件走统一判据 [rememberDemoVisible]：debug 构建 + 已解锁 + 调试模式已开；release 恒为真数据。
 */
@Composable
internal fun StateSwitchRow(state: FiveState, onSelect: (FiveState) -> Unit, modifier: Modifier = Modifier) {
    if (!com.bsfeng.monitor.ui.components.rememberDemoVisible()) return
    val labels = listOf("加载态", "空态", "错误态", "有数据", "边界态")
    Column(modifier.fillMaxWidth().padding(horizontal = LocalDesignTokens.current.space4)) {
        ChipRow(
            items = labels,
            selectedIndex = FiveState.values().indexOf(state),
            onSelect = { onSelect(FiveState.values()[it]) },
        )
    }
}

/** 五态的通用骨架（与各屏真实数据无关的演示面）。 */
@Composable
internal fun FiveStateSlot(
    state: FiveState,
    emptyText: String,
    errorText: String,
    edgeText: String,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when (state) {
        FiveState.LOADING -> AppCard { StateSkeleton(rows = 4) }
        FiveState.EMPTY -> EmptyState(emptyText)
        FiveState.ERROR -> ErrorState(errorText, onRetry = onRetry)
        FiveState.EDGE -> EdgeState(edgeText)
        FiveState.POPULATED -> content()
    }
}
