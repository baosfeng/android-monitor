package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt 的纯逻辑状态；职责：从选中名反推一级、给出二级列表、把选择翻译成回传值。

import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Category

/**
 * 选择器纯逻辑状态（无 Compose 依赖，可 JVM 单测）。
 *
 * 负责三件事：
 * 1. 从「当前选中名」反推应该高亮哪个一级（选中名可能是二级，例如「电商」→「购物」）；
 * 2. 给出当前一级下的二级列表（网格内容）；
 * 3. 把用户操作翻译成回传值：选二级 → 二级名；选「不细分」→ 当前一级名。
 *
 * 不可变：切换一级返回新实例（[selectTop]），Compose 侧用 mutableState 持有当前展示的一级。
 */
class CategoryPickerState(
    private val tree: CategoryTree,
    val type: Int,
    val selectedName: String = "",
    initialTopName: String? = null,
) {

    /** 该类型的一级分类（按 sortOrder） */
    val topLevel: List<Category> = tree.topLevel(type)

    /** 选中名所属的一级；不是本类型的一级/二级（未知、脏数据）时为空 */
    val selectedTopName: String = run {
        if (selectedName.isBlank() || !tree.contains(selectedName)) return@run ""
        val top = tree.topLevelNameOf(selectedName)
        if (topLevel.any { it.name == top }) top else ""
    }

    /** 当前展示的一级：优先显式切换结果，其次选中项的一级，最后第一个一级 */
    val activeTopName: String = run {
        if (!initialTopName.isNullOrBlank() && topLevel.any { it.name == initialTopName }) {
            return@run initialTopName
        }
        selectedTopName.ifBlank { topLevel.firstOrNull()?.name ?: "" }
    }

    /** 一级 chips 的初始定位下标 */
    val activeTopIndex: Int = topLevel.indexOfFirst { it.name == activeTopName }.coerceAtLeast(0)

    /** 当前一级下的二级名（网格内容，不含「不细分」项） */
    val children: List<String> = tree.childrenOf(activeTopName).map { it.name }

    /** 选中的是本一级下的二级时，返回该二级名（网格高亮用）；否则空串 */
    val selectedChildName: String = if (children.contains(selectedName)) selectedName else ""

    /** 选中的正好是当前一级（「不细分」项高亮） */
    val isTopLevelOnlySelected: Boolean =
        selectedName.isNotBlank() && selectedName == activeTopName

    /** 只切换展示的一级，不动选中值（用户还在挑细分） */
    fun selectTop(name: String): CategoryPickerState =
        CategoryPickerState(tree, type, selectedName, name)

    /**
     * 选二级：名字必须是当前一级下的二级才回传，否则返回空串。
     *
     * 校验的意义是防「把别的类型/别的父级的名字写进交易」——选择器本身只会传回点击到的格子。
     */
    fun chooseChild(name: String): String = if (children.contains(name)) name else ""

    /** 选「不细分」：记一级 */
    fun chooseTopOnly(): String = activeTopName
}
