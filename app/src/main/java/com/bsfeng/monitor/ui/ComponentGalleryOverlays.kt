package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的浮层区 —— GalleryOverlays（弹窗/抽屉/Toast 三个入口）与 BottomSheetCard（演示抽屉内容）。负责 s12 的覆盖层陈列。
// 契约 docs/交互设计/s12-组件与状态-交互契约.md：
//   · s12-overlays  三个入口按钮按下 0.96 + LIGHT，遮罩 220ms 淡入、面板 220ms 弹起 / 自下推入
//   · s12-sheet     记录类型 chip 独立切换 + 「确定」+ 手柄下滑超 96dp 或 800dp/s 即关

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun GalleryOverlays(onDialog: () -> Unit, onSheet: () -> Unit, onToast: () -> Unit) {
    DemoSection("浮层 Overlays", Modifier.testTag("s12-overlays")) {
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space2)) {
                AppButton("弹窗", onDialog, Modifier.weight(1f), AppButtonVariant.Ghost)
                AppButton("底部抽屉", onSheet, Modifier.weight(1f), AppButtonVariant.Ghost)
            }
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            AppButton("Toast 轻提示", onToast, variant = AppButtonVariant.Ghost, block = true)
        }
    }
}

/** 手柄下滑判定（契约 s12-sheet 边界）：位移 96dp 或速度 800dp/s 即关闭。 */
private val SHEET_DISMISS_DP = 96.dp

private val SHEET_DISMISS_VELOCITY_DP = 800.dp

/** 抽屉内记录类型（契约「chip 文案固定 4 个不换行」）。 */
private val SHEET_TYPES = listOf("前台切换", "广告跳过", "亮屏解锁", "充电")

/**
 * 「选择记录类型」抽屉（契约 s12-sheet 四行）。
 *
 * chip 与页面内的单选组不同：每个 chip 独立切换（契约行 42「多选：每个 chip 独立切换」），
 * 一个都不选时「确定」禁用并给原因文案；手柄下拉跟手，过阈值关闭并丢弃本次选择，否则 120ms 回弹。
 */
@Composable
internal fun BottomSheetCard(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val t = LocalDesignTokens.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf(0)) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val closeAt = with(density) { SHEET_DISMISS_DP.toPx() }
    val velocityAt = with(density) { SHEET_DISMISS_VELOCITY_DP.toPx() }
    val tracker = remember { VelocityTracker() }

    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        showGrip = false,
        // 跟手下移：拖动量来自手柄手势，松手过阈值即关闭，未过阈值回弹
        modifier = Modifier
            .testTag("s12-sheet")
            .offset { IntOffset(0, dragY.roundToInt()) },
    ) {
        // 手柄：唯一的下拉热区（契约「手柄外区域拖动仍可滚动抽屉内容」）
        Box(
            Modifier
                .fillMaxWidth()
                .height(t.space8)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            if (dragY > closeAt || velocity > velocityAt) {
                                onDismiss()
                            } else {
                                // 未过阈值：120ms（--dur-state）回弹归位，不停在半开
                                scope.launch {
                                    animate(dragY, 0f, animationSpec = tween(t.durState)) { value, _ ->
                                        dragY = value
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                animate(dragY, 0f, animationSpec = tween(t.durState)) { value, _ ->
                                    dragY = value
                                }
                            }
                        },
                    ) { change, delta ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        dragY = (dragY + delta).coerceAtLeast(0f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .height(t.space1)
                    .width(t.space12)
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.border),
            )
        }
        Column(
            Modifier
                // 抽屉高度不超过屏高 90%，超出走内部滚动（契约边界）
                .heightIn(max = (configuration.screenHeightDp * 0.9f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("选择记录类型", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space1))
            Text(
                "底部抽屉从下方推入，220ms 进入 / 150ms 退出。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                SHEET_TYPES.forEachIndexed { index, label ->
                    Chip(
                        label = label,
                        selected = index in selected,
                        onClick = {
                            selected = if (index in selected) selected - index else selected + index
                        },
                    )
                }
            }
            Spacer(Modifier.height(t.space4))
            if (selected.isEmpty()) {
                Text(
                    SHEET_NO_SELECTION_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                )
                Spacer(Modifier.height(t.space2))
            }
            AppButton(
                "确定",
                {
                    onConfirm(SHEET_TYPES.filterIndexed { index, _ -> index in selected })
                },
                block = true,
                enabled = selected.isNotEmpty(),
                tone = HapticTone.SUCCESS,
            )
        }
    }
}

/** 抽屉「确定」不可用时的说明（契约 s12-sheet 边界）。 */
internal const val SHEET_NO_SELECTION_HINT = "请先选择至少 1 个类型"

/** Toast 文案随所选类型生成（契约 s12-sheet「已选择：前台切换」）。 */
internal fun sheetConfirmToast(types: List<String>): String = "已选择：" + types.joinToString("、")
