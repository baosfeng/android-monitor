package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 CategorySheet；职责：选择分类抽屉（两级同屏，复用 CategoryPicker）。

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.CategoryTree

/**
 * 选择分类：两级同屏（上方一级 chips + 下方 6 列二级网格），与自动记账浮窗共用 [CategoryPicker]。
 *
 * 选中二级 / 「不细分」即回传一级或二级名并关抽屉；点一级只切换网格（用户还在挑细分）。
 */
@Composable
internal fun BoxScope.CategorySheet(
    visible: Boolean,
    tree: CategoryTree,
    type: Int,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    SheetShell(title = "选择分类", onDismiss = onDismiss) {
        CategoryPicker(
            tree = tree,
            type = type,
            selectedName = selectedName,
            onPick = onPick,
            // 一级 18 个用 6 列网格完整展示（3 行）；二级最多 2 行，留出固定高度可滚动
            gridMaxHeight = 140.dp,
            iconSize = 32.dp,
            topStyle = CategoryPickerTopStyle.GRID,
        )
    }
}
