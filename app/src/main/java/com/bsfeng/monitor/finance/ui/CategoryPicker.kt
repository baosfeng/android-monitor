package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt；职责：两级分类选择器入口（一级 + 二级网格的交互装配）。
// 纯逻辑见 CategoryPickerState，识别判定与格子模型见 CategoryPickerModel，一级展示见
// CategoryPickerTopLevel（chips / 网格），二级网格见 CategoryPickerSecondaryGrid。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 两级分类选择器：一级 chips + 二级 6 列网格（浮窗与「记一笔」共用）。
 *
 * 数据来自 [CategoryTree]（分类表 parent_id 父子关系）；交易 `category_name` 存二级名
 * （有细分）或一级名（选「不细分」），所以选择器只回传名字，落库口径由调用方负责。
 *
 * 交互口径（见 docs/记账/分类体系设计.md「UI」、锚点账本 s15-l1-*）：
 * - 点一级 chip / 格子 **只切换下方网格，不回调、不关闭**；点已展开的同一格收起面板、再点展开；
 * - 点二级或「不细分」才通过 [onPick] 回传最终分类名。
 *
 * @param recognizedName 自动记账识别出的分类（可能是二级）；命中的格子右上角标圆点
 * @param gridMaxHeight 二级网格最大高度，超出后可滚动（浮窗里高度受限，抽屉里较宽裕）
 * @param iconSize 二级格子图标尺寸（浮窗比抽屉略小）
 */
@Composable
fun CategoryPicker(
    tree: CategoryTree,
    type: Int,
    selectedName: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    recognizedName: String = "",
    gridMaxHeight: Dp = PICKER_DEFAULT_GRID_MAX_H,
    iconSize: Dp = PICKER_DEFAULT_ICON_SIZE,
    topStyle: CategoryPickerTopStyle = CategoryPickerTopStyle.CHIPS,
    /**
     * 一级格子的 testTag 前缀（不传 = 不贴锚点，节点与布局无差异）。
     *
     * 只有浮窗（s15）需要把锚点 s15-l1-0..17 逐字落到格子上；其余页面不传，
     * 因此给测试 / 无障碍工具看到的树与改造前完全一致。
     */
    topLevelTagPrefix: String? = null,
) {
    val t = LocalDesignTokens.current
    // 识别到的名字属于哪个一级（空 = 没识别 / 不在本类型树里）
    val recognizedTop = remember(tree, type, recognizedName) {
        recognizedTopName(tree, type, recognizedName)
    }
    // 当前展示哪个一级是纯 UI 状态：外部选中名没变时，用户点一级的结果必须保住
    var activeTopName by remember(tree, type) {
        mutableStateOf(
            recognizedTop.ifBlank { CategoryPickerState(tree, type, selectedName).activeTopName }
        )
    }
    // 默认只展示一级；**识别项自动展开它的一级**（原型 pkApplyRecognized 口径），点新的一级也展开
    var expanded by remember(tree, type) { mutableStateOf(recognizedTop.isNotBlank()) }
    // 同一浮窗换了识别结果（下一笔账单）时重新定位回识别项
    var lastRecognizedTop by remember(tree, type) { mutableStateOf(recognizedTop) }
    LaunchedEffect(tree, type, recognizedName) {
        if (recognizedTop.isBlank() || recognizedTop == lastRecognizedTop) return@LaunchedEffect
        lastRecognizedTop = recognizedTop
        activeTopName = recognizedTop
        expanded = true
    }
    // 外部选中变化（切换类型 / 编辑交易）时重新定位到选中项的一级；
    // 同一级里点二级不收起已展开的网格（原型 pkPickL2 保持面板可见）
    LaunchedEffect(tree, type, selectedName) {
        val top = CategoryPickerState(tree, type, selectedName).activeTopName
        if (top.isNotBlank() && top != activeTopName) activeTopName = top
    }
    val state = remember(tree, type, selectedName, activeTopName) {
        CategoryPickerState(tree, type, selectedName, activeTopName)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (state.topLevel.isEmpty()) {
            Text(
                "分类数据还没准备好",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(vertical = t.space3),
            )
            return@Column
        }
        // 点一级：新的一级切过去并展开；同一格再点收起、再点展开（锚点 s15-l1-*「点同一格收起面板」）。
        // chips 与网格共用同一口径，避免两种一级入口行为不同。
        val onSelectTop: (String) -> Unit = { name ->
            val next = topLevelTap(activeTopName, name, expanded)
            activeTopName = next.activeTopName
            expanded = next.expanded
        }
        when (topStyle) {
            CategoryPickerTopStyle.CHIPS -> TopLevelRow(state = state, onSelectTop = onSelectTop)
            CategoryPickerTopStyle.GRID -> {
                SectionTitle("一级分类")
                TopLevelGrid(state = state, onSelectTop = onSelectTop, tagPrefix = topLevelTagPrefix)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(t.space3))
            SecondaryGrid(
                state = state,
                recognizedName = recognizedName,
                gridMaxHeight = gridMaxHeight,
                iconSize = iconSize,
                onPick = onPick,
            )
        }
    }
}

/**
 * 一级格子 / chip 的点击结果（纯函数；锚点 s15-l1-0…17 的边界「点同一格收起面板；同级只展开一个」）。
 *
 * 点同一格 = 收起 / 再展开（选中值不动：面板收起后已选值不回退丢失）；点新的一级 = 切过去并展开。
 *
 * @return 新的 activeTopName 与二级面板是否展开
 */
internal data class TopLevelTap(val activeTopName: String, val expanded: Boolean)

internal fun topLevelTap(current: String, tapped: String, expanded: Boolean): TopLevelTap =
    if (tapped == current) TopLevelTap(current, !expanded) else TopLevelTap(tapped, true)
