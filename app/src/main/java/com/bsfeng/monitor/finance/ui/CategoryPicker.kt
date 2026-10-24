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
 * 交互契约（见 docs/记账/分类体系设计.md「UI」）：
 * - 点一级 chip **只切换下方网格，不回调、不关闭**；
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
    // 默认只展示一级；**识别项自动展开它的一级**（原型 pkApplyRecognized 口径），点一级也展开
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
        when (topStyle) {
            CategoryPickerTopStyle.CHIPS -> TopLevelRow(
                state = state,
                onSelectTop = { name -> activeTopName = name; expanded = true },
            )
            CategoryPickerTopStyle.GRID -> {
                SectionTitle("一级分类")
                TopLevelGrid(
                    state = state,
                    onSelectTop = { name -> activeTopName = name; expanded = true },
                )
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
