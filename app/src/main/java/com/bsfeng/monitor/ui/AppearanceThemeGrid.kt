package com.bsfeng.monitor.ui

// 从 ui/AppearanceScreen.kt 拆出：主题选择器区块 + 选项卡 —— ThemePicker（区块标题 / 「当前」值 /
// 两列网格 / 键盘导航）与 ThemeGridItem（原型 s13 的 .theme-opt / .theme-swatch：3 段色卡 +
// 中英文名 + 选中态）。负责「8 个主题怎么排、按下与选中怎么反馈」，整页装配仍在 AppearanceScreen.kt。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 网格列数（锚点 s13-picker-grid：两列 4 行、项间距 8dp）。 */
private const val GRID_COLUMNS = 2

/**
 * 组内导航键 → 目标下标（纯函数，便于把「两列间移动 / 到边界不回绕」直接单测钉死）。
 *
 * 返回当前下标表示「键已处理但不移动」：用来吞掉边界上的方向键，避免父层滚动跟着跑；
 * null 表示该键不归本组处理（Tab / Esc 等交还上层由焦点系统处理）。
 */
internal fun gridNavTarget(key: Key, index: Int, count: Int, columns: Int = GRID_COLUMNS): Int? {
    if (count <= 0 || columns <= 0) return null
    val column = index % columns
    val target = when (key) {
        Key.DirectionLeft -> if (column > 0) index - 1 else index
        Key.DirectionRight -> if (column < columns - 1 && index + 1 < count) index + 1 else index
        Key.DirectionUp -> if (index - columns >= 0) index - columns else index
        Key.DirectionDown -> if (index + columns < count) index + columns else index
        else -> return null
    }
    return target.coerceIn(0, count - 1)
}

/**
 * 主题选择器区块（锚点 s13-picker；网格 s13-picker-grid）。
 *
 * 键盘口径（锚点 s13-picker-grid 键盘行）：Tab 进组落在当前选中项——组挂 [focusGroup]、
 * 组内只有 [focusIndex] 项可聚焦（roving tabindex）；方向键上下左右在两列间移动焦点但**不换主题**，
 * Enter / Space 才切换；到边界不回绕；Tab 出组交给焦点系统。
 *
 * @param onMoveFocus 方向键移动焦点（只改键盘焦点下标，不改选中主题）
 * @param onPick 点击 / Enter / Space 切换主题（同一主题也回调，由调用方决定不重复写盘）
 */
@Composable
internal fun ThemePicker(
    ids: List<String>,
    currentId: String,
    focusIndex: Int,
    focusRequesters: List<FocusRequester>,
    onMoveFocus: (Int) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    gridModifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth()) {
        // 区块标题与「当前」值同行两端对齐；点两者都无反应（不展开下拉、无触感）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("主题", style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
            // 「当前」随选中即时更新；超长时单行省略，不换行撑高标题行
            Text(
                "当前 " + themeLabel(currentId),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(t.space2))
        Column(
            gridModifier
                .fillMaxWidth()
                .padding(horizontal = t.space4)
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            ids.getOrNull(focusIndex)?.let(onPick)
                            true
                        }

                        else -> {
                            val target = gridNavTarget(event.key, focusIndex, ids.size)
                                ?: return@onPreviewKeyEvent false
                            if (target != focusIndex) onMoveFocus(target)
                            // 导航键一律吞掉：到边界不回绕，也不让父层滚动跟着跑
                            true
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            ids.chunked(GRID_COLUMNS).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    pair.forEachIndexed { columnIndex, id ->
                        val index = rowIndex * GRID_COLUMNS + columnIndex
                        ThemeGridItem(
                            id = id,
                            selected = id == currentId,
                            focusable = index == focusIndex,
                            focusRequester = focusRequesters[index],
                            onClick = { onPick(id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 末行不足两列时补占位，保持左列宽度（窄屏不压缩选项文字）
                    if (pair.size < GRID_COLUMNS) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 主题选项卡（原型 .theme-opt / .theme-swatch）：
 * 左 = 46×36 三段色卡（底 0-34% / 表面 34-64% / 文字 64-100%，线性渐变，恒渲染该主题自己的配色）；
 * 右 = 中文（bodyLarge）+ 英文（labelSmall muted）两行；
 * 选中态 = 描边加粗到 fg 40% + --elev-1 + 字重 600（不占强调色预算）。
 *
 * 反馈全部复用共享基元：按下走 [rowPressFeedback]（0.99 + alpha .9，时长取 --dur-press）、
 * 焦点环走 [keyboardFocusRing]（仅键盘输入时出现）、行高 ≥ 52dp。
 */
@Composable
internal fun ThemeGridItem(
    id: String,
    selected: Boolean,
    focusable: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val preview = DesignTokens(id)
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            // 锚点逐字落 testTag（glassmorphism → s13-theme-glass，见 themeAnchor）
            .testTag(themeAnchor(id))
            // 行高 ≥ 52dp（口径口径：列表行高 52dp / 点击区 ≥ 44dp）
            .heightIn(min = t.rowMinH)
            .rowPressFeedback(interaction)
            .tokenShadow(if (selected) 1 else 0, shape)
            .clip(shape)
            .background(t.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) t.fgFade(40) else t.borderSoft,
                shape = shape,
            )
            .focusRequester(focusRequester)
            // roving tabindex：组内只有键盘当前项可聚焦 → Tab 进组一次、落当前选中项
            .focusProperties { canFocus = focusable }
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 36.dp)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to preview.bg,
                            0.34f to preview.bg,
                            0.34f to preview.surface,
                            0.64f to preview.surface,
                            0.64f to preview.fg2,
                            1f to preview.fg2,
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                ),
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                themeNameCn(id),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) t.fg else t.fg2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                themeNameEn(id),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
