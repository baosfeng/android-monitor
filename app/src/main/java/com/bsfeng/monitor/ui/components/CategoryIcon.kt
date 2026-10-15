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
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 分类图标名 ↔ 真机 finance_categories.name 的映射与 drawable 解析。
 *
 * 图标来自原型 s05 的 22 个线描 SVG（逐条转成 res/drawable/ic_cat_*.xml，
 * 24x24 viewBox、strokeWidth 1.7、fill=none），与原型逐条对应。
 * 收入类分类原型尚未绘制图标，[keyOf] 返回 null，由 [CategoryIcon] 回落彩色圆点。
 */
internal object CategoryIcons {

    private val KEY_BY_NAME: Map<String, String> = mapOf(
        "餐饮" to "food",
        "汽车" to "car",
        "出行" to "transport",
        "手机数码" to "digital",
        "购物" to "shopping",
        "居家生活" to "home",
        "日用" to "daily",
        "教育" to "education",
        "零食" to "snack",
        "宽带话费" to "broadband",
        "水果" to "fruit",
        "地铁火车" to "metro",
        "水电" to "utility",
        "就医" to "medical",
        "通讯" to "comm",
        "衣物" to "clothing",
        "美妆" to "beauty",
        "房租还贷" to "rent",
        "休闲娱乐" to "entertain",
        "打车" to "taxi",
        "住宿" to "hotel",
        "旅游度假" to "travel",
    )

    /** 分类名 → 图标 key；未绘制图标的分类返回 null。 */
    fun keyOf(categoryName: String): String? = KEY_BY_NAME[categoryName]

    /** 图标 key → drawable 资源；key 非法返回 0（调用方回落）。 */
    fun drawableOf(key: String): Int = when (key) {
        "food" -> R.drawable.ic_cat_food
        "car" -> R.drawable.ic_cat_car
        "transport" -> R.drawable.ic_cat_transport
        "digital" -> R.drawable.ic_cat_digital
        "shopping" -> R.drawable.ic_cat_shopping
        "home" -> R.drawable.ic_cat_home
        "daily" -> R.drawable.ic_cat_daily
        "education" -> R.drawable.ic_cat_education
        "snack" -> R.drawable.ic_cat_snack
        "broadband" -> R.drawable.ic_cat_broadband
        "fruit" -> R.drawable.ic_cat_fruit
        "metro" -> R.drawable.ic_cat_metro
        "utility" -> R.drawable.ic_cat_utility
        "medical" -> R.drawable.ic_cat_medical
        "comm" -> R.drawable.ic_cat_comm
        "clothing" -> R.drawable.ic_cat_clothing
        "beauty" -> R.drawable.ic_cat_beauty
        "rent" -> R.drawable.ic_cat_rent
        "entertain" -> R.drawable.ic_cat_entertain
        "taxi" -> R.drawable.ic_cat_taxi
        "hotel" -> R.drawable.ic_cat_hotel
        "travel" -> R.drawable.ic_cat_travel
        else -> 0
    }
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
    // 原型 .cat-icon 的 color: color-mix(in srgb, var(--k) 78%, var(--fg))
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
            // 原型尚未绘制该分类的图标（如收入类）时的兜底：分类色实心圆点。
            Box(
                Modifier
                    .size(glyph * 0.6f)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
