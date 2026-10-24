package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/CategoryPicker.kt 的尺寸常量与区块标题；职责：选择器尺寸常量、一级样式枚举与 SectionTitle。

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 二级网格默认最大高度：6 列下约 2 行，够放下任一常见的 4-9 个二级 */
internal val PICKER_DEFAULT_GRID_MAX_H: Dp = 176.dp

/** 二级格子图标默认尺寸（抽屉里 6 列，图标略收小） */
internal val PICKER_DEFAULT_ICON_SIZE: Dp = 32.dp

/** 一级 chip 高度：走 --tap-min，触控目标不缩水（分类区总高的另一半留给二级网格） */
internal val TOP_CHIP_MIN_H: Dp = 44.dp

/** 二级网格列数：一行 6 个（与自动记账浮窗一致，减少滑动） */
internal const val PICKER_COLUMNS = 6

/** 识别标记圆点直径（沿用自动记账浮窗既有视觉：9dp 实心 + 面板色描边） */
internal val RECOGNIZED_DOT_SIZE: Dp = 9.dp

/** 选中格子淡底（原型 accent 描边 + 极淡底，避免 6 列网格一片空白） */
internal const val SELECTED_BG_ALPHA = 0.08f

/** 识别格淡底（原型 .ab-sheet .cat-l2.is-recognized = accent 10%） */
internal const val RECOGNIZED_BG_ALPHA = 0.12f

/** 一级展示样式：抽屉里完整展示全部一级（网格），浮窗空间小用横向 chips */
enum class CategoryPickerTopStyle { CHIPS, GRID }

/** 区块标题：一级 / 二级两层用标题和名字区分，用户一眼知道当前在挑哪一层 */
@Composable
internal fun SectionTitle(title: String, trailing: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = t.space2, bottom = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = t.muted)
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(t.space1))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = t.accentText,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 一级显示名：网格里直接用全称（6 列 11sp 放得下 4 字，与原型 s05/s14a 一致）。
 */
internal fun displayName(name: String): String = name
