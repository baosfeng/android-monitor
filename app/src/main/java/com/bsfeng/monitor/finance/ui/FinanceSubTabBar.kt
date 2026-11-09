package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 记账页的「子 Tab」条（.open-design/src/screens/s04-记账.html:108-113）；
// 职责：交易明细 / 统计汇总 两段的切换条与状态常量，复用公共 SegmentedControl（键盘、触感、选中态都已内建）。

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.SegmentedControl

/** 子 Tab 下标：交易明细（默认，原型 s04-tab-tx 为 checked）。 */
internal const val FINANCE_SUBTAB_DETAIL: Int = 0

/** 子 Tab 下标：统计汇总。 */
internal const val FINANCE_SUBTAB_STATS: Int = 1

/** 子 Tab 文案（顺序固定，与下标常量一一对应）。 */
internal val FINANCE_SUBTAB_LABELS: List<String> = listOf("交易明细", "统计汇总")

/**
 * 子 Tab 的段级锚点（lead 清单逐字）：交易明细 → s04-tab-tx、统计汇总 → s04-tab-stats。
 *
 * 锚点对账从 testTag 调用点回溯实参，集合字面量直接写在调用点会断链；
 * 收成常量后，调用点只引一个名字，证据能顺着常量声明体找到这两个字面量。
 */
internal val FINANCE_SUBTAB_TAGS: List<String> = listOf("s04-tab-tx", "s04-tab-stats")

/**
 * 记账页子 Tab 条：交易明细 / 统计汇总。
 *
 * 选中态由调用方 hoist（rememberSaveable），这里只做渲染与回调；切换走 SegmentedControl
 * 已内建的 LIGHT 触觉与 ← → / Home / End 键盘操作，不重复实现一套。
 * 纯文本标签即可读名，无需额外 semantics —— 分段控件本身已带 selectable 状态语义。
 */
@Composable
internal fun FinanceSubTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedControl(
        options = FINANCE_SUBTAB_LABELS,
        selectedIndex = selected.coerceIn(0, FINANCE_SUBTAB_LABELS.lastIndex),
        onSelect = onSelect,
        modifier = modifier.testTag("s04-tabbar"),
        optionTags = FINANCE_SUBTAB_TAGS,
    )
}