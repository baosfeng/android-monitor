package com.bsfeng.monitor.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 全应用唯一的页面底衬入口：`--backdrop-a` → `--backdrop-b` 的竖直渐变，铺满整个窗口。
 *
 * ## 为什么按窗口坐标算渐变，而不是按控件自身高度
 * 页面底衬落在 Scaffold 内容区里（状态栏与导航栏已被让开），按控件高度画的渐变会在
 * 内容区顶/底各留一条色差接缝；按窗口高铺开，全屏任意位置的色值恒定，页面是否被 inset
 * 都不出现接缝，外层根底衬与页面底衬也永远不会错位。
 *
 * ## 用法
 * ```
 * Box(Modifier.fillMaxSize().pageBackdrop()) { ... }
 * ```
 * 调用方只需要给出铺满区域，不需要知道状态栏高度；内容避让另由 `statusBarsPadding()` /
 * `navigationBarsPadding()` 或 Scaffold 的 padding 负责。
 *
 * drawWithCache：build 块只在尺寸 / token / 窗口变化时执行，逐帧重跑的只有一次 drawRect，
 * 不再每帧 new Brush + listOf。
 */
@Composable
fun Modifier.pageBackdrop(): Modifier {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    var topInWindowPx by remember { mutableFloatStateOf(0f) }
    var windowHeightPx by remember { mutableIntStateOf(0) }
    // 组合期读一次：布局回填位置/窗口尺寸后必然重组，drawWithCache 的 build 块随之重算
    val topPx = topInWindowPx
    val windowHeightState = windowHeightPx
    return this
        .onGloballyPositioned { coordinates ->
            topInWindowPx = coordinates.positionInWindow().y
            // edge-to-edge 下承载 Compose 的 View 铺满窗口（系统栏之下也算），高度即窗口高
            if (view.height > 0) windowHeightPx = view.height
        }
        .drawWithCache {
            // 首帧还没量到窗口尺寸时，按「自身高度 + 已让开的高度」兜底，避免整屏只有一个色
            val windowHeight = if (windowHeightState > 0) {
                windowHeightState.toFloat()
            } else {
                size.height + topPx
            }
            val brush = Brush.verticalGradient(
                colors = listOf(t.backdropA, t.backdropB),
                startY = -topPx,
                endY = windowHeight - topPx,
            )
            onDrawBehind { drawRect(brush) }
        }
}
