package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：底部弹层 —— Scrim 遮罩、BottomSheet 容器与把手（SHEET_GRIP_*）常量。负责「从底部升起的临时面板」。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.sheetEnterFromBottom
import com.bsfeng.monitor.ui.foundation.sheetExitToBottom
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.opaquePanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.width

/**
 * 纯遮罩浮层：无内容，点即关。
 *
 * 自己不再自绘遮罩，统一走 [WindowOverlay]（全屏 + 沉浸式 + 唯一一处遮罩实现）。
 */
@Composable
fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    WindowOverlay(visible = visible, onDismiss = onDismiss) { }
}

/**
 * 底部抽屉：窗口级全屏承载（[WindowOverlay]），面板贴底。
 *
 * 沉浸式遮罩 / 返回键 / 点外部关闭的口径全部在 [WindowOverlay]（含「为什么必须走窗口」的说明）。
 *
 * 面板：opaquePanel（surface-warm → surface → bg 合成到 100% 不透明，玻璃主题下也不透底）
 * + topStart/topEnd 圆角 + --elev-3 阴影 + grip；贴底面板加 `navigationBarsPadding()`，
 * 面板底色铺到屏幕底、内容上抬，否则「确定 / 关闭」会被手势条压住。
 * 入场 220ms 滑入 + 淡入（交互口径 §7）；关闭即时卸载。
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showGrip: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val sheetColor = t.opaquePanel
    val sheetShape = RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg)
    // 每次打开都从「未进入」开始，AnimatedVisibility 才会真正播放入场动画
    // （首次组合即 visible = true 时它不播，等于没有动画）
    var entered by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible) { entered = visible }
    WindowOverlay(visible = visible, onDismiss = onDismiss) {
        AnimatedVisibility(
            visible = entered,
            // ⚠️ modifier 必须作用在 AnimatedVisibility（Box 的**直接子节点**）上：调用方普遍写
            // Modifier.align(Alignment.BottomCenter) 来把抽屉贴底，而 align 是 Box 的 parentData ——
            // 落在子节点上才会被读取（此前它被传给内部的 Column，所有抽屉都退回 TopStart）。
            modifier = Modifier.align(Alignment.BottomCenter).then(modifier),
            enter = sheetEnterFromBottom(),
            exit = sheetExitToBottom(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .tokenShadow(3, sheetShape)
                    .clip(sheetShape)
                    .background(sheetColor)
                    .navigationBarsPadding()
                    .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space6),
            ) {
                if (showGrip) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(SHEET_GRIP_W)
                            .height(SHEET_GRIP_H)
                            .clip(RoundedCornerShape(t.radiusPill))
                            .background(t.border)
                    )
                    Spacer(Modifier.height(t.space3))
                }
                content()
            }
        }
    }
}

/** 原型 .sheet-grip：36×4 pill。 */
private val SHEET_GRIP_W = 36.dp

private val SHEET_GRIP_H = 4.dp
