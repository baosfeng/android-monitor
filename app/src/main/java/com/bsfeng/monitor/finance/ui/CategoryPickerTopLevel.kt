package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt 的一级展示；职责：一级网格（6 列全展示）与一级 chips（横向滚动）。

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 一级网格：6 列完整展示全部一级（18 个 = 3 行，不用横向翻）。
 *
 * 记账抽屉里一级是「主入口」，要一眼看全；浮窗高度有限仍用 chips（[CategoryPickerTopStyle.CHIPS]）。
 */
@Composable
internal fun TopLevelGrid(
    state: CategoryPickerState,
    onSelectTop: (String) -> Unit,
    tagPrefix: String? = null,
) {
    val t = LocalDesignTokens.current
    val rows = remember(state.topLevel) { state.topLevel.chunked(PICKER_COLUMNS) }
    Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(t.space1),
            ) {
                row.forEachIndexed { column, top ->
                    TopLevelCell(
                        name = top.name,
                        selected = top.name == state.activeTopName,
                        onClick = { onSelectTop(top.name) },
                        modifier = Modifier
                            .weight(1f)
                            // 调用方要锚点时才贴 testTag（顺序 = 网格阅读顺序）；null 时不产生任何节点差异
                            .then(
                                if (tagPrefix == null) Modifier
                                else Modifier.testTag(tagPrefix + (rowIndex * PICKER_COLUMNS + column))
                            ),
                    )
                }
                repeat(PICKER_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TopLevelCell(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(t.radiusMd)
    // 旧版口径：分类格用各自分类色（12% 淡底 + 分类色线描），选中只加 2dp accent 描边
    val color = categoryColorOf(name)
    Column(
        modifier = modifier
            .clip(shape)
            // 选中：2dp accent 描边 + 淡底（原型 .cat-l1[aria-pressed] 的 accent-text + outline）
            .background(if (selected) t.accent.copy(alpha = SELECTED_BG_ALPHA) else Color.Transparent)
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            // 点一级只换网格：轻震一次（口径同全部格子点击；s15 锚点 s15-l1-* 的「轻震」）
            .testTag("cell.category.l1")
            .clickable {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(vertical = t.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryIcon(name = name, color = color, size = 32.dp)
        Spacer(Modifier.height(2.dp))
        Text(
            displayName(name),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一级 chips：横向滚动，点一下只切换下方网格 */
@Composable
internal fun TopLevelRow(state: CategoryPickerState, onSelectTop: (String) -> Unit) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    // 初始定位到选中项的一级：识别到的可能是「购物·电商」，不给定位就要用户自己翻 chips
    LaunchedEffect(state.activeTopIndex, state.topLevel) {
        if (state.activeTopIndex > 0) listState.scrollToItem(state.activeTopIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items(state.topLevel.size) { index ->
            val top = state.topLevel[index]
            TopLevelChip(
                name = top.name,
                selected = top.name == state.activeTopName,
                onClick = { onSelectTop(top.name) },
            )
        }
    }
}

@Composable
private fun TopLevelChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = Modifier
            .heightIn(min = TOP_CHIP_MIN_H)
            .clip(shape)
            .background(if (selected) t.accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) t.accent else t.border, shape)
            .testTag("chip.category")
            .clickable { onClick() }
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
