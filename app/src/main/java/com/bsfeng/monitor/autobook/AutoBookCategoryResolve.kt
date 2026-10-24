package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookSheetParts.kt 拆出：确认卡片的分类归一与尺寸常量。
// 分类体系升级后旧名可能不在树里，统一回退到该类型的兜底一级。

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.CategoryCatalog

/**
 * 把分类名归一成「该类型分类树里存在的名字」。
 *
 * 识别结果不一定在树里：分类体系升级前的旧名（如「出行」）或历史商户默认值都可能残留。
 * 树里没有时回退该类型的兜底一级（支出「其他」/ 收入「其他收入」），与
 * [com.bsfeng.monitor.autobook.AutoBookEngine] 落库前的校验同一口径 ——
 * 否则浮窗会显示一个「一个都没选中」的分类，用户不知道最终会记成什么。
 * 树为空（加载失败）时原样返回：浮窗照常可用，落库时由引擎兜底。
 */
internal fun resolveCategory(tree: CategoryTree, type: Int, name: String): String {
    val pickerType = if (type == AutoBookDraft.TYPE_INCOME) 1 else 0
    val topNames = tree.topLevel(pickerType).map { it.name }
    if (topNames.isEmpty()) return name
    if (name.isNotBlank() && tree.contains(name) && tree.topLevelNameOf(name) in topNames) {
        return name
    }
    val fallback = if (pickerType == 1) INCOME_FALLBACK else CategoryCatalog.FALLBACK
    return if (fallback in topNames) fallback else topNames.first()
}

/** 收入分类的兜底一级：支出侧兜底是 [CategoryCatalog.FALLBACK]「其他」 */
internal const val INCOME_FALLBACK = "其他收入"

/**
 * 浮窗分类区高度受限：一级 6 列 3 行（约 3×52dp）+ 间距 + 二级网格。
 *
 * 内容区整体可滚动（见 [AutoBookConfirmSheet]），所以二级网格不必再压到极小；
 * 176dp ≈ 6 列下两行多一点，任一常见的 4-9 个二级都能滚到。
 */
internal val PANEL_GRID_MAX_H: Dp = 176.dp

/** 浮窗里的分类格图标：与「记一笔」同一口径 32dp（原型 .cat-icon 32×32） */
internal val PANEL_CATEGORY_ICON_SIZE: Dp = 32.dp

/** 卡片高度上限占屏高比例（原型 .ab-sheet max-height:82vh） */
internal const val PANEL_MAX_HEIGHT_RATIO = 0.82f

/** 金额格式化：千分位 + 固定两位小数（与记账页交易行口径一致） */
internal fun formatAmount(amount: Double): String =
    String.format(java.util.Locale.CHINA, "%,.2f", kotlin.math.abs(amount))

/**
 * 卡片高度上限 ≈ 82% 窗口高（原型 `.ab-sheet` 的 `max-height:82vh`）。
 *
 * 取 `WindowInfo.containerSize`（px → dp）而非 `Configuration.screenHeightDp`：后者的 insets
 * 口径随 targetSdk 变化、还会取整到 dp；浮窗外层窗口的实际可用高度以容器尺寸为准。
 */
@Composable
internal fun rememberPanelMaxHeight(): Dp {
    val density = LocalDensity.current
    return with(density) {
        (LocalWindowInfo.current.containerSize.height * PANEL_MAX_HEIGHT_RATIO).toDp()
    }
}
