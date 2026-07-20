package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// iOS 风格色彩系统
// =============================================

/** iOS 系统色 */
object iOSColors {
    val blue = Color(0xFF007AFF)
    val red = Color(0xFFFF3B30)
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9500)
    val yellow = Color(0xFFFFCC00)
    val purple = Color(0xFFAF52DE)
    val pink = Color(0xFFFF2D55)
    val teal = Color(0xFF5AC8FA)
    val gray = Color(0xFF8E8E93)
    val gray2 = Color(0xFFAEAEB2)
    val gray3 = Color(0xFFC7C7CC)
    val gray4 = Color(0xFFD1D1D6)
    val gray5 = Color(0xFFE5E5EA)
    val gray6 = Color(0xFFF2F2F7)

    // 文本色
    val labelPrimary = Color(0xFF000000)
    val labelSecondary = Color(0x993C3C43) // 60% alpha
    val labelTertiary = Color(0x4D3C3C43) // 30% alpha

    // 背景色
    val systemBackground = Color(0xFFF2F2F7)
    val groupedBackground = Color(0xFFFFFFFF)
    val separator = Color(0xFFC6C6C8)
}

/** 分类图标颜色面板（稳定哈希用，无全局可变状态） */
val categoryPalette = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF5AC8FA), Color(0xFF007AFF),
    Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF5856D6),
    Color(0xFF00C7BE), Color(0xFFFF6482), Color(0xFF6EA8FF),
    Color(0xFFA2845E), Color(0xFF8E8E93), Color(0xFFFFD60A)
)

/** 支出/收入颜色映射 */
object FinanceColors {
    val expense = iOSColors.red
    val income = iOSColors.green
    val budget = iOSColors.blue
    val categoryColors = listOf(
        iOSColors.red, iOSColors.orange, iOSColors.yellow,
        iOSColors.green, iOSColors.teal, iOSColors.blue,
        iOSColors.purple, iOSColors.pink
    )
}

// =============================================
// iOS 风格字体尺寸
// =============================================

object iOSFontSizes {
    val largeTitle = 34.sp
    val title1 = 28.sp
    val title2 = 22.sp
    val title3 = 20.sp
    val body = 17.sp
    val subhead = 15.sp
    val footnote = 13.sp
    val caption = 12.sp
    val caption2 = 11.sp
}

// =============================================
// iOS 风格组件
// =============================================

/**
 * iOS 风格的大标题导航栏。
 * 类似 iOS UINavigationBar 的 Large Title 样式。
 */
@Composable
fun iOSLargeTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = iOSFontSizes.largeTitle,
            fontWeight = FontWeight.Bold,
            color = iOSColors.labelPrimary
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = iOSFontSizes.subhead,
                color = iOSColors.labelSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * iOS 风格的分组列表行。
 * 类似 UITableViewCell 样式：左侧标签 + 右侧值 + 箭头。
 */
@Composable
fun iOSListRow(
    label: String,
    value: String = "",
    showArrow: Boolean = false,
    valueColor: Color = iOSColors.labelSecondary,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = iOSFontSizes.body,
            color = iOSColors.labelPrimary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    fontSize = iOSFontSizes.body,
                    color = valueColor
                )
            }
            if (showArrow) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "›",
                    fontSize = 20.sp,
                    color = iOSColors.gray3
                )
            }
        }
    }
}

/**
 * iOS 风格的分组列表卡片。
 * 类似 iOS 的 Inset Grouped 样式。
 */
@Composable
fun iOSGroupedList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(iOSColors.groupedBackground)
    ) {
        content()
    }
}

/**
 * iOS 风格的分段控制器（SegmentedControl）。
 * 替代 Material Tab。
 */
@Composable
fun iOSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(iOSColors.gray5),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (isSelected) Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(iOSColors.groupedBackground)
                        else Modifier
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontSize = iOSFontSizes.footnote,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) iOSColors.labelPrimary else iOSColors.gray
                )
            }
        }
    }
}

/**
 * iOS 风格的大标题金额显示。
 */
@Composable
fun iOSAmountDisplay(
    amount: String,
    label: String,
    color: Color = iOSColors.labelPrimary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "¥$amount",
            fontSize = iOSFontSizes.title1,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = iOSFontSizes.footnote,
            color = iOSColors.labelSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * iOS 风格的大圆角按钮。
 */
@Composable
fun iOSButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = iOSColors.blue,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = iOSFontSizes.body,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/**
 * 分类图标圆点（用分类名的首字符 + 颜色背景）。
 */
@Composable
fun CategoryDot(
    categoryName: String,
    color: Color,
    size: Int = 32,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 2))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = categoryName.take(1),
            color = Color.White,
            fontSize = (size / 2).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// =============================================
// iOS 全面屏安全区域适配
// =============================================

/**
 * iOS 全面屏底部安全区域守卫 — 确保内容不被 Home Indicator 遮挡。
 */
@Composable
fun iOSSafeAreaBottomSpacer() {
    Spacer(
        Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
    )
}

/**
 * 全面屏顶部安全区域守卫 — 确保内容从状态栏下方开始。
 */
@Composable
fun iOSSafeAreaTopSpacer() {
    Spacer(
        Modifier.windowInsetsTopHeight(WindowInsets.statusBars)
    )
}
