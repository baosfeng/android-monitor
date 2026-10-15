package com.bsfeng.monitor.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五态演示开关（仅 debug 生效）。
 *
 * 三屏的真实数据态由各自 ViewModel 推导（loading / error / empty / populated），
 * edge 态在真实数据下不可复现（需要超长文案 + 万级计数），因此提供一个 debug 专用的
 * 显式覆盖开关——等价于原型右下角的「原型控制」面板。release 构建下本文件不渲染任何东西。
 */
object FiveStateDemo {

    /** null = 跟随真实数据态。 */
    var forced: FiveState? by mutableStateOf(null)

    fun effective(real: FiveState): FiveState = forced ?: real

    fun cycle(): FiveState? {
        val order = listOf(null, FiveState.LOADING, FiveState.EMPTY, FiveState.ERROR, FiveState.POPULATED, FiveState.EDGE)
        val next = order[(order.indexOf(forced) + 1) % order.size]
        forced = next
        return next
    }
}

/**
 * 五态切换条（演示用）：两行排布，保证 6 个 chip 在 393dp+ 宽度下全部可点。
 * 显示条件：debug 构建 **且** 设置里的「调试模式」已打开 —— 关掉即隐藏、打开即出现。
 */
@Composable
fun FiveStateDemoBar(modifier: Modifier = Modifier) {
    if (!com.bsfeng.monitor.ui.components.rememberDemoVisible()) return
    val t = LocalDesignTokens.current
    var current by androidx.compose.runtime.remember { mutableStateOf(FiveStateDemo.forced) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DemoChip("自动", current == null) { FiveStateDemo.forced = null; current = null }
            DemoChip("加载", current == FiveState.LOADING) { FiveStateDemo.forced = FiveState.LOADING; current = FiveState.LOADING }
            DemoChip("空", current == FiveState.EMPTY) { FiveStateDemo.forced = FiveState.EMPTY; current = FiveState.EMPTY }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DemoChip("错误", current == FiveState.ERROR) { FiveStateDemo.forced = FiveState.ERROR; current = FiveState.ERROR }
            DemoChip("填充", current == FiveState.POPULATED) { FiveStateDemo.forced = FiveState.POPULATED; current = FiveState.POPULATED }
            DemoChip("边界", current == FiveState.EDGE) { FiveStateDemo.forced = FiveState.EDGE; current = FiveState.EDGE }
        }
    }
}

@Composable
private fun DemoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Chip(label = label, selected = selected, onClick = onClick)
}
