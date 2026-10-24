package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt 的识别判定与格子模型；职责：识别分类归属的一级（纯函数）、二级格子模型与图标取名。

import com.bsfeng.monitor.finance.CategoryTree

/**
 * 识别到的分类名属于哪个一级（纯函数，可 JVM 单测）。
 *
 * 空串 = 没识别、名字不在树里、或不属于该类型的分类树 —— 调用方按「无识别」处理（默认折叠）。
 */
internal fun recognizedTopName(tree: CategoryTree, type: Int, recognizedName: String): String {
    if (recognizedName.isBlank()) return ""
    if (!tree.contains(recognizedName)) return ""
    val top = tree.topLevelNameOf(recognizedName)
    return if (tree.topLevel(type).any { it.name == top }) top else ""
}

/** 网格项：二级分类名（图标与分类色都按它自己取，不继承父级） */
internal data class PickerCellModel(
    val name: String,
    val label: String,
)

/**
 * 格子取图标/分类色用的名字：**就是格子自己的分类名**。
 *
 * 曾经这里传父级名（`state.activeTopName`），导致同一父级下 10 个二级格子全画成父级
 * 那一个图标。单测 [PickerCellModel] 与 [pickerIconName] 保证「不再继承父级」不被改回去。
 */
internal fun pickerIconName(cell: PickerCellModel): String = cell.name
