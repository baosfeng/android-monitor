package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt 的二级网格；职责：二级格子网格（可滚动、识别项自动滚入可视区）与单个格子。

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 二级网格第一格的固定文案：只用一级分类、不写入二级（契约 s14a-cat-grid） */
private const val TOP_ONLY_LABEL = "不细分"

/**
 * 网格项：格子模型 + 是否「不细分」档。
 *
 * 「不细分」档的 name 取当前一级名（回传值就是一级名），label 写「不细分」；
 * 因此它不能走 chooseChild（一级名不在 children 里），必须走 chooseTopOnly。
 */
private data class SecondaryCell(val model: PickerCellModel, val topOnly: Boolean)

/**
 * 二级网格：第一格是「不细分」（直接把一级名当分类），后面是当前一级的二级。
 *
 * 高度受限时整块可滚动（普通 Column + verticalScroll，不用 LazyVerticalGrid：项数 ≤ 12，
 * 一次性组合成本低，滚动不会反复组合/回收 item —— 真机反馈浮窗里懒加载网格滑动很卡）。
 * 识别到的二级可能落在第二行以后：按实测行高把它滚进可视区，用户不用自己翻。
 */
@Composable
internal fun SecondaryGrid(
    state: CategoryPickerState,
    recognizedName: String,
    gridMaxHeight: Dp,
    iconSize: Dp,
    onPick: (String) -> Unit,
) {
    val t = LocalDesignTokens.current
    // 第一格永远是「不细分」：一级格子只换网格，落值必须走它或二级格子，
    // 否则「只记一级」的分类在 UI 上无处可选（chooseTopOnly 曾是无调用点的死代码）。
    val cells = remember(state.activeTopName, state.children) {
        val list = mutableListOf<SecondaryCell>()
        if (state.activeTopName.isNotBlank()) {
            list += SecondaryCell(
                model = PickerCellModel(name = state.activeTopName, label = TOP_ONLY_LABEL),
                topOnly = true,
            )
        }
        state.children.forEach { list += SecondaryCell(PickerCellModel(name = it, label = it), topOnly = false) }
        list
    }
    val rows = remember(cells) { cells.chunked(PICKER_COLUMNS) }
    // 切换一级时回到顶部：上一级的滚动位置对新网格没有意义
    val scrollState = remember(state.activeTopName) { ScrollState(0) }
    val rowHeightPx = remember(state.activeTopName) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowGapPx = with(density) { t.space2.toPx() }

    LaunchedEffect(state.activeTopName, recognizedName, rowHeightPx.floatValue) {
        val rowHeight = rowHeightPx.floatValue
        if (rowHeight <= 0f) return@LaunchedEffect
        // 「不细分」不参与识别定位：识别项一定是二级分类（一级名由 picker 自动展开一级）
        val index = cells.indexOfFirst { !it.topOnly && it.model.name == recognizedName }
        if (index < 0) return@LaunchedEffect
        // 等一帧：maxValue 要按实际内容高度更新后才知道能滚多远
        withFrameNanos { }
        val row = index / PICKER_COLUMNS
        val target = (row * (rowHeight + rowGapPx)).toInt()
        scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    SectionTitle("二级分类", state.activeTopName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = gridMaxHeight)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(t.space1),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (rowHeightPx.floatValue <= 0f) {
                            rowHeightPx.floatValue = coords.size.height.toFloat()
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(t.space1),
            ) {
                row.forEach { cell ->
                    PickerCell(
                        cell = cell.model,
                        // 「不细分」高亮 = 选中的正好是当前一级；二级格高亮 = 选中的是该二级
                        selected = if (cell.topOnly) {
                            state.isTopLevelOnlySelected
                        } else {
                            cell.model.name == state.selectedChildName
                        },
                        recognized = !cell.topOnly && cell.model.name == recognizedName,
                        iconSize = iconSize,
                        onClick = {
                            // 「不细分」→ 回传一级名（chooseTopOnly）；二级格 → 回传二级名。
                            // 两者都只在拿到非空名时回传，调用方随即关抽屉。
                            val picked = if (cell.topOnly) state.chooseTopOnly() else state.chooseChild(cell.model.name)
                            if (picked.isNotBlank()) onPick(picked)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 6 个时补空位，保持每列等宽
                repeat(PICKER_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickerCell(
    cell: PickerCellModel,
    selected: Boolean,
    recognized: Boolean,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    // 与一级格同一套视觉：分类色 12% 淡底 + 分类色线描
    val color = categoryColorOf(cell.name)
    Column(
        modifier = modifier
            .clip(shape)
            // 与一级格同一套视觉：无底色，选中 2dp 描边 + 淡底；识别项再淡一层（原型 .is-recognized）
            .background(
                when {
                    recognized -> t.accent.copy(alpha = RECOGNIZED_BG_ALPHA)
                    selected -> t.accent.copy(alpha = SELECTED_BG_ALPHA)
                    else -> Color.Transparent
                }
            )
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(vertical = t.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            // 二级分类各用**自己的**图标与分类色：曾经传父级名，导致同一父级下 10 个格子全一样
            CategoryIcon(name = pickerIconName(cell), color = color, size = iconSize)
            if (recognized) {
                // 识别标记（沿用自动记账浮窗）：右上角小圆点，用户改选后仍能对照系统猜的是哪个
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(RECOGNIZED_DOT_SIZE)
                        .clip(CircleShape)
                        .background(t.accent)
                        .border(1.dp, t.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(t.space1))
        Text(
            displayName(cell.label),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
