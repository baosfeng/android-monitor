package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 WheelColumn；职责：时刻滚轮（点格 / 滚动都能改选中值，停稳后吸附到中带）。

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.rememberMotionTier
import com.bsfeng.monitor.ui.foundation.scaleDuration
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 时间滚轮：一屏 3 格（原型 .s14a-wheel-wrap height:132px / 每格 44px） */
private val WHEEL_H: Dp = 132.dp
private val WHEEL_ITEM_H: Dp = 44.dp

/** 滚轮宽度（原型 .s14a-wheel-wrap width:104px） */
private val WHEEL_WIDTH: Dp = 104.dp

/** 滚轮高亮带左右内缩（原型 .s14a-wheel-band 的 left/right:6px，非 token 值） */
private val WHEEL_BAND_INSET: Dp = 6.dp

/** 点格对齐时长：原型 s14a-wheel-hour / s14a-wheel-minute 的「120ms 对齐」 */
private const val WHEEL_ALIGN_MS = 120

/** 滚停判定窗口：松手后 80ms 内没有重新起滚才算停稳（原型 s14a-timepick「松手后 80ms 判定」） */
private const val WHEEL_SETTLE_MS = 80L

/**
 * 时刻滚轮：滚到哪一格就算选中哪一格，点某一格则把它滚到中带（原型 .s14a-wheel）。
 *
 * 四个实现要点来自原型与契约：
 * 1. 对齐必须在**可见之后**做 —— 隐藏状态下设滚动位置无效（原型里表现为「选中的 10:30 没在中间」）；
 * 2. 上下各留半屏 padding，否则首尾项滚不到中间（原型用的 44px 上下 padding 是同一原因）；
 * 3. 滚停不是「一松手就算」：松手后 80ms 内又起滚（甩动 / 二次拨动）就丢弃这一次判定；
 * 4. 点格与滚停走**同一条**归位路径（[WHEEL_ALIGN_MS] 对齐），所以「点选 → 回传 → 吸附」不会互相打架。
 */
@Composable
internal fun WheelColumn(
    values: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pad = (WHEEL_H - WHEEL_ITEM_H) / 2
    val itemPx = with(LocalDensity.current) { WHEEL_ITEM_H.toPx() }
    val tier = rememberMotionTier()
    // 下面两个 LaunchedEffect 只随 key 重启，闭包里读不到最新值 —— 用 rememberUpdatedState 兜住
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    /** 把第 [index] 格滚到中带：动效档为「精简」时直接落位，其余按 120ms 补齐。 */
    suspend fun alignTo(index: Int) {
        val target = index.coerceIn(0, values.lastIndex)
        val dur = tier.scaleDuration(WHEEL_ALIGN_MS)
        if (dur <= 0) {
            listState.scrollToItem(target)
            return
        }
        val current = listState.firstVisibleItemIndex * itemPx + listState.firstVisibleItemScrollOffset
        val delta = target * itemPx - current
        if (delta == 0f) return
        listState.animateScrollBy(delta, tween<Float>(dur, easing = t.easeOut))
    }

    // 打开抽屉 / 外部改值：瞬时落位（原型：分钟为 30 时打开正好落在中带）；点格：120ms 对齐
    var animateAlign by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, values.lastIndex)
        if (animateAlign) {
            animateAlign = false
            alignTo(target)
        } else {
            listState.scrollToItem(target)
        }
    }

    // 滚停 80ms 判定：停稳后吸附到最近一格，并回写选中值（原型：滚停 80ms 再算）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1) // 跳过初值：刚进组合时那一次不是「滚停」
            .filter { inProgress -> !inProgress }
            .collect {
                delay(WHEEL_SETTLE_MS)
                // 80ms 内又滚起来（甩动 / 二次拨动）→ 这次不算停稳，交给下一轮
                if (listState.isScrollInProgress) return@collect
                val raw = listState.firstVisibleItemIndex * itemPx + listState.firstVisibleItemScrollOffset
                val index = (raw / itemPx).roundToInt().coerceIn(0, values.lastIndex)
                if (index != latestSelected) latestOnSelect(index)
                alignTo(index)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WHEEL_ITEM_H)
                        // 锚点 s14a-wheel-hour / s14a-wheel-minute 的 click：点格滚到中带（120ms）。
                        // 反馈就是「滚到中带 + 数字转 fg/600」，不弹 ripple、不加按压缩放。
                        .testTag("cell.wheel")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (index == selectedIndex) {
                                // 点已选中的格：只对齐，不重复写值
                                scope.launch { alignTo(index) }
                            } else {
                                // 回传草稿 → selectedIndex 变化 → LaunchedEffect 走 120ms 对齐
                                animateAlign = true
                                onSelect(index)
                            }
                        },
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
