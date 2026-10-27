package com.bsfeng.monitor.ui.components

// 键盘焦点环（契约 s01 头部通用口径）：2dp、对比 ≥3:1，仅键盘 / 遥控器操作时出现。
// 触屏点击即便让控件获得焦点也不画环（InputMode.Touch），避免「点一下留个框」。

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 焦点环线宽（契约：2dp）。 */
val FOCUS_RING_WIDTH = 2.dp

/**
 * 键盘焦点环：与 `clickable` / Button 共用同一节点。
 *
 * 只包一层 `onFocusChanged`，不改变布局与点击区；焦点环颜色取 `t.accent`（白底对比 > 3:1）。
 * 触屏与键盘混用时以「获得焦点那一刻的输入方式」为准。
 */
@Composable
fun Modifier.keyboardFocusRing(shape: Shape): Modifier {
    val t = LocalDesignTokens.current
    val inputModeManager = LocalInputModeManager.current
    var ringVisible by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { state ->
            ringVisible = state.isFocused && inputModeManager.inputMode == InputMode.Keyboard
        }
        .then(if (ringVisible) Modifier.border(FOCUS_RING_WIDTH, t.accent, shape) else Modifier)
}
