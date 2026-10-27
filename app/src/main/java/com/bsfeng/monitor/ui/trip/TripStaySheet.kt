package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：停留详情抽屉 —— TripStaySheet（底部抽屉内容）、placeSourceNote（位置来源说明）。负责「点开某段停留看期间的应用」。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.launch

/** 下滑关闭的距离判据：超过卡高 40%（契约 trip-detail-sheet 边界）。 */
private const val SHEET_DISMISS_FRACTION: Float = 0.4f

/** 下滑关闭的速度判据：300dp/s（契约 trip-detail-sheet 边界）。 */
private val SHEET_DISMISS_VELOCITY = 300.dp

/**
 * 停留详情抽屉（契约 trip-detail-sheet / -2 / -3）。
 *
 * @param placeOrdinal 该段地点在当天的位次（0 起，见 placeOrdinal）——原型三个抽屉实例
 *   （「家」「某某咖啡」「公司」）按地点映射；第 4 个地点起不打锚点。
 *
 * 交互：遮罩点按 / 返回键 / 卡内「关闭」三路同效，都不改数据、无撤销窗口；
 * 下滑超过卡高 40% 或速度 300dp/s 才关闭，不足回弹（dragToDismiss）。
 * 关闭后焦点回到触发它的停留段（由调用方 TripScreen 用 lastRowRequester 模式回位）。
 */
@Composable
internal fun TripStaySheet(
    stay: Stay,
    dayStart: Long,
    placeOrdinal: Int,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .sheetTag(placeOrdinal)
                .dragToDismiss(onDismiss),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = placeDisplayName(stay),
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge("停留 " + formatSpanText(stay.durationMs))
            }
            Spacer(Modifier.height(t.space1))
            MetaText(
                formatSpanClock(stay.start, dayStart) + " – " + formatSpanClock(stay.end, dayStart) +
                    " · " + placeSourceNote(stay.place),
            )

            Spacer(Modifier.height(t.space4))
            Text("期间应用", style = MaterialTheme.typography.labelLarge, color = t.muted)
            Spacer(Modifier.height(t.space2))
            if (stay.topApps.isEmpty()) {
                MetaText("这段时间没有应用使用记录")
            } else {
                // 模型侧给的是全部应用（按时长降序）；这里列前 8 条 + 「其他 N 个应用」，
                // 避免抽屉被长列表撑出屏，同时不会把剩下的 App 直接吞掉。
                visibleApps(stay.topApps).forEach { slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = t.tapMin),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = slice.app,
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatSpanText(slice.ms),
                            style = MaterialTheme.typography.labelMedium,
                            color = t.muted,
                        )
                    }
                }
                val rest = otherAppsNote(stay.topApps)
                if (rest.isNotEmpty()) {
                    Spacer(Modifier.height(t.space1))
                    MetaText(rest)
                }
            }

            Spacer(Modifier.height(t.space4))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(t.radiusPill),
            ) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 抽屉锚点（契约 trip-detail-sheet / trip-detail-sheet-2 / trip-detail-sheet-3）。
 *
 * 原型三个抽屉分别对应「家」「某某咖啡」「公司」三个地点；实现按地点首次出现的位次
 * （ordinal）映射，是结构位置不是数据身份。第 4 个地点起不打锚点：锚点集合严格等于契约定义。
 */
private fun Modifier.sheetTag(ordinal: Int): Modifier = when (ordinal) {
    0 -> testTag("trip-detail-sheet")
    1 -> testTag("trip-detail-sheet-2")
    2 -> testTag("trip-detail-sheet-3")
    else -> this
}

/**
 * 该停留段所属地点在当天的位次（0 起，按首次出现排序）。
 *
 * 抽屉内容按「段」而非按「地点」（契约 s16-seg-home-noon 边界：同名地点多段各自独立开抽屉），
 * 只有锚点按地点映射——与原型三个抽屉实例一致。
 */
internal fun placeOrdinal(timeline: DayTimeline, stay: Stay): Int =
    timeline.stays.map { it.place.id }.distinct().indexOf(stay.place.id).coerceAtLeast(0)

/**
 * 下滑关闭：片段自底部跟手，松手时「位移 >= 卡高 40%」或「速度 >= 300dp/s」命中任一即关闭，
 * 否则用 dur-overlay-out（150ms）回弹。
 *
 * 位移走 graphicsLayer.translationY（绘制期），不进 measure/layout；向上拖被钳到 0（抽屉不能被拉高）。
 * 边界：卡片整体的下滑 / 退出动画由共享 BottomSheet 承担——该组件关闭即卸载，退出 150ms 播不出来，
 * 属已知共享缺陷，本页不单独修（见交接文档）。
 */
@Composable
private fun Modifier.dragToDismiss(onDismiss: () -> Unit): Modifier {
    val t = LocalDesignTokens.current
    val scope = rememberCoroutineScope()
    val velocityThreshold = with(LocalDensity.current) { SHEET_DISMISS_VELOCITY.toPx() }
    val offset = remember { Animatable(0f) }
    var cardHeight by remember { mutableFloatStateOf(0f) }
    return this
        .onSizeChanged { cardHeight = it.height.toFloat() }
        .graphicsLayer { translationY = offset.value }
        .pointerInput(Unit) {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onVerticalDrag = { change, dragAmount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch {
                        offset.snapTo((offset.value + dragAmount).coerceAtLeast(0f))
                    }
                },
                onDragEnd = {
                    val velocity = tracker.calculateVelocity().y
                    val passedDistance = cardHeight > 0f && offset.value >= cardHeight * SHEET_DISMISS_FRACTION
                    if (passedDistance || velocity >= velocityThreshold) {
                        onDismiss()
                    } else {
                        scope.launch { offset.animateTo(0f, tween(t.durOverlayOut)) }
                    }
                },
                onDragCancel = {
                    scope.launch { offset.animateTo(0f, tween(t.durOverlayOut)) }
                },
            )
        }
}

/** 地点来源：有坐标说定位，没坐标如实说是推算。 */
private fun placeSourceNote(place: Place): String = when (place.source) {
    Basis.GPS -> "地点来自定位"
    Basis.DEVICE -> "地点来自网络名与使用簇"
}
