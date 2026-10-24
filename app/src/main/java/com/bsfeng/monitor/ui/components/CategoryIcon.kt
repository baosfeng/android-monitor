package com.bsfeng.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.R
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 分类图标名 ↔ 真机 finance_categories.name 的映射与 drawable 解析。
 *
 * 图标为 24x24 viewBox 线描（`strokeWidth 1.7`、`fill=none`、圆角端点），
 * 每个分类（一级 + 二级）都有**独立 key 与独立 drawable**，二级不再继承父级图标；
 * 唯一性由 `CategoryIconUniquenessTest` 守护（145 个分类 ↔ 145 个不同资源）。
 *
 * 映射表本体在 [CategoryIconKeys] / [CategoryIconDrawables]，本对象只做门面，
 * 保持 `CategoryIcons.keyOf` / `CategoryIcons.drawableOf` 的既有调用与测试。
 */
internal object CategoryIcons {

    /** 分类名 → 图标 key；未绘制图标的分类返回 null。 */
    fun keyOf(categoryName: String): String? = categoryIconKeyOf(categoryName)

    /** 图标 key → drawable 资源；key 非法返回 0（调用方回落）。 */
    fun drawableOf(key: String): Int = categoryIconDrawableOf(key)
}

/** 原型 .cat-icon 的尺寸（34px 底 / 20px 图标）。 */
private val CATEGORY_ICON_SIZE: Dp = 34.dp
private const val CATEGORY_GLYPH_RATIO = 20f / 34f

/**
 * 分类图标块（替代原型 .cat-icon）：分类色 12% 淡底 + 分类色混 78% 前景色的线描图标。
 *
 * @param name 分类名（与 finance_categories.name 一致，如「餐饮」）
 * @param color 该分类的色槽颜色，取 t.categoryDot(sortOrder)
 */
@Composable
fun CategoryIcon(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = CATEGORY_ICON_SIZE,
) {
    val t = LocalDesignTokens.current
    val glyph = size * CATEGORY_GLYPH_RATIO
    // 旧版口径：分类色 12% 淡底 + 分类色线描（色混 22% 前景），与 s05/s14a 的彩色分类格一致
    val ink = lerp(color, t.fg, 0.22f)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val res = CategoryIcons.keyOf(name)?.let { CategoryIcons.drawableOf(it) } ?: 0
        if (res != 0) {
            Icon(
                painter = painterResource(res),
                contentDescription = name,
                tint = ink,
                modifier = Modifier.size(glyph),
            )
        } else {
            // 未登记分类（历史脏数据）的兜底：分类色实心圆点。
            Box(
                Modifier
                    .size(glyph * 0.6f)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
