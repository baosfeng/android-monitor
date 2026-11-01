package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的浮层区 —— GalleryOverlays（弹窗/抽屉/Toast 三个入口）与 BottomSheetCard（演示抽屉内容）。负责 s12 的覆盖层陈列。
// 交互口径：
//   · s12-overlays  三个入口按钮按下 0.96 + LIGHT，遮罩 220ms 淡入、面板 220ms 弹起 / 自下推入
//   · s12-sheet     记录类型 chip 单选 + 「确定」+ 手柄下滑超 96dp 或 800dp/s 即关

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

/** 手柄下滑判定（锚点 s12-sheet 边界）：位移 96dp 或速度 800dp/s 即关闭。 */
private val SHEET_DISMISS_DP = 96.dp

private val SHEET_DISMISS_VELOCITY_DP = 800.dp

/** 抽屉内记录类型（口径「chip 文案固定 4 个不换行」）。 */
private val SHEET_TYPES = listOf("前台切换", "广告跳过", "亮屏解锁", "充电")

/**
 * 「选择记录类型」抽屉（锚点 s12-sheet 四行）。
 *
 * chip 是**单选**：口径行 42 的结果列写「多选」，但同组「确定」的 toast 是单数「已选择：前台切换」，
 * 两处自相矛盾；按口径口径「确认文案是单数 → 单选」已裁决为单选（点新 chip 替换旧选中、
 * 再点已选中的 chip 保持选中，与页面内 chip 组同规则）。类型列表为空时「确定」禁用并给原因文案；
 * 手柄下拉跟手，过阈值关闭并丢弃本次选择，否则 120ms 回弹。
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
    // 单选：null = 没有任何可选项（口径边界「一个都不选时禁用」的防御路径）
    var selectedType by remember { mutableStateOf(SHEET_TYPES.firstOrNull()) }
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
        // 手柄：唯一的下拉热区（口径「手柄外区域拖动仍可滚动抽屉内容」）
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
                // 抽屉高度不超过屏高 90%，超出走内部滚动（口径边界）
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
                SHEET_TYPES.forEach { label ->
                    Chip(
                        label = label,
                        selected = label == selectedType,
                        onClick = { selectedType = sheetSelectedAfter(selectedType, label) },
                    )
                }
            }
            Spacer(Modifier.height(t.space4))
            if (selectedType == null) {
                Text(
                    SHEET_NO_SELECTION_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                )
                Spacer(Modifier.height(t.space2))
            }
            AppButton(
                "确定",
                { onConfirm(sheetConfirmPayload(selectedType)) },
                block = true,
                enabled = selectedType != null,
                tone = HapticTone.SUCCESS,
            )
        }
    }
}

/** 抽屉「确定」不可用时的说明（锚点 s12-sheet 边界）。 */
internal const val SHEET_NO_SELECTION_HINT = "请先选择至少 1 个类型"

/**
 * chip 点击后的选中类型（锚点 s12-sheet 单选口径）。
 *
 * 点新 chip 替换旧选中；再点已选中的 chip 保持选中（与页面内 chip 组 s12-chip-seg「再点已选中保持选中」同规则），
 * 因此选中态不会回到「一个都没选」——那是 [sheetConfirmPayload] 返回空列表时才走的防御路径。
 */
internal fun sheetSelectedAfter(current: String?, tapped: String): String =
    if (tapped == current) current else tapped

/** 「确定」回传的类型列表：单选 → 至多 1 个；空列表 = 按钮禁用。 */
internal fun sheetConfirmPayload(selected: String?): List<String> = listOfNotNull(selected)

/** Toast 文案随所选类型生成（锚点 s12-sheet「已选择：前台切换」，单选时必是单数）。 */
internal fun sheetConfirmToast(types: List<String>): String = "已选择：" + types.joinToString("、")
