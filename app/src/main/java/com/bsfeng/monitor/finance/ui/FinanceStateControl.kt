package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的五态演示开关；职责：DEBUG 下五态覆盖的持有对象与切换条。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五态覆盖：[value] 非空时屏幕按该态渲染，为空时按真实数据推导。
 * 仅 DEBUG 包可唤出切换条，正式包恒为 null。
 */
@Stable
internal class FinanceStateControl(
    private val override: MutableState<FiveState?>,
    private val barVisible: MutableState<Boolean>,
) {
    val value: FiveState? get() = override.value
    val barShown: Boolean get() = barVisible.value

    fun toggleBar() {
        barVisible.value = !barVisible.value
    }

    fun select(state: FiveState?) {
        override.value = state
    }
}

@Composable
internal fun rememberFinanceStateControl(): FinanceStateControl {
    val override = remember { mutableStateOf<FiveState?>(null) }
    val barVisible = remember { mutableStateOf(false) }
    return remember { FinanceStateControl(override, barVisible) }
}

/** 五态 + 自动 的横向切换条（演示件）；统一判据 [rememberDemoVisible] + 长按过标题才出现。 */
@Composable
internal fun FinanceStateBar(control: FinanceStateControl, modifier: Modifier = Modifier) {
    if (!control.barShown) return
    if (!com.bsfeng.monitor.ui.components.rememberDemoVisible()) return
    val t = LocalDesignTokens.current
    val options: List<Pair<String, FiveState?>> = listOf(
        "自动" to null,
        "加载" to FiveState.LOADING,
        "空" to FiveState.EMPTY,
        "失败" to FiveState.ERROR,
        "正常" to FiveState.POPULATED,
        "边界" to FiveState.EDGE,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.surfaceWarm)
            .padding(t.space1),
        horizontalArrangement = Arrangement.spacedBy(t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (label, state) ->
            val selected = control.value == state
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(if (selected) t.accent else t.surface)
                    .clickable { control.select(state) }
                    .padding(horizontal = t.space2, vertical = t.space1),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) t.accentOn else t.fg2,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
