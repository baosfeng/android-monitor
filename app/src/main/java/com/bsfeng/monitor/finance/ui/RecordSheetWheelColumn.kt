package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 WheelColumn；职责：时刻滚轮（滚停吸附到最近一格并回写选中值）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

/** 时间滚轮：一屏 3 格（原型 .s14a-wheel-wrap height:132px / 每格 44px） */
private val WHEEL_H: Dp = 132.dp
private val WHEEL_ITEM_H: Dp = 44.dp

/** 滚轮宽度（原型 .s14a-wheel-wrap width:104px） */
private val WHEEL_WIDTH: Dp = 104.dp

/** 滚轮高亮带左右内缩（原型 .s14a-wheel-band 的 left/right:6px，非 token 值） */
private val WHEEL_BAND_INSET: Dp = 6.dp

/**
 * 时刻滚轮：滚到哪一格就算选中哪一格（原型 .s14a-wheel）。
 *
 * 两个实现要点来自原型的真机教训：
 * 1. 对齐必须在**可见之后**做 —— 隐藏状态下设滚动位置无效（原型里表现为「选中的 10:30 没在中间」）；
 * 2. 上下各留半屏 padding，否则首尾项滚不到中间（原型用的 44px 上下 padding 是同一原因）。
 */
@Composable
internal fun WheelColumn(
    values: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    val pad = (WHEEL_H - WHEEL_ITEM_H) / 2

    LaunchedEffect(selectedIndex) {
        if (values.isNotEmpty()) {
            listState.scrollToItem(selectedIndex.coerceIn(0, values.lastIndex))
        }
    }
    // 滚停后吸附到最近一格，并回写选中值（原型：滚停 80ms 再算）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { inProgress -> !inProgress }
            .collect {
                val raw = listState.firstVisibleItemIndex * WHEEL_ITEM_H.value +
                    listState.firstVisibleItemScrollOffset
                val index = (raw / WHEEL_ITEM_H.value).roundToInt().coerceIn(0, values.lastIndex)
                onSelect(index)
                listState.animateScrollToItem(index)
            }
    }

    Box(
        modifier = Modifier
            .width(WHEEL_WIDTH)
            .height(WHEEL_H)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.surface),
    ) {
        // 中间高亮带（原型 .s14a-wheel-band）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(WHEEL_ITEM_H)
                // 原型 .s14a-wheel-band：left/right 6px、底 color-mix(fg 8%)、圆角 radius-sm
                .padding(horizontal = WHEEL_BAND_INSET)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(8)),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(WHEEL_H),
            contentPadding = PaddingValues(vertical = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values.size) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(WHEEL_ITEM_H),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        values[index],
                        // 原型 .s14a-wheel button：--text-lg(21px)；选中 --fg/600，未选 --muted/400
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = t.textLg),
                        color = if (selected) t.fg else t.muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 两位数字：00 / 09 / 23 */
internal fun twoDigits(value: Int): String = String.format(java.util.Locale.CHINA, "%02d", value)
