package com.bsfeng.monitor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// iOS 风格 InsetGrouped 卡片 — 替代 Cupertino section()
// =============================================

/**
 * iOS 风格的分组列表卡片（InsetGrouped）。
 *
 * 替代 Cupertino 的 `section(style = SectionStyle.InsetGrouped)`。
 *
 * 用法：
 * ```
 * InsetGroupedCard {
 *     IOSRow(title = "名称", trailing = { Switch(checked, onCheckedChange) })
 *     HorizontalDivider(...)
 *     IOSRow(title = "其他", ...)
 * }
 * ```
 */
@Composable
fun InsetGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/**
 * 带标题的分组列表卡片。
 */
@Composable
fun InsetGroupedSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(
                        start = Spacing.xxl,
                        bottom = Spacing.sm,
                        top = Spacing.xxl,
                    ),
            )
        }
        InsetGroupedCard(content = content)
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.xxl,
                    top = Spacing.sm,
                    bottom = Spacing.sm,
                ),
            )
        }
    }
}

// =============================================
// iOS 风格列表行
// =============================================

/**
 * iOS 风格列表行（标题 + 尾随组件）。
 *
 * 替代各页面散乱的 Row + padding 组合。
 */
@Composable
fun IOSRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Row { trailing() }
    }
}

/**
 * iOS 风格设置行（标题 + 副标题 + 尾随开关/箭头）。
 *
 * 替代 SettingsScreen 中原有的 private SettingsRow。
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        trailing()
    }
}

// =============================================
// iOS 风格导航栏
// =============================================

/**
 * iOS 风格导航栏。
 *
 * 替代各页面手写的 Row + IconButton 组合。
 */
@Composable
fun IOSNavigationBar(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧导航区域
        Box(
            modifier = Modifier.sizeIn(minWidth = 44.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            } else {
                navigationIcon()
            }
        }

        // 标题（居中）
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        // 右侧操作区域
        Box(
            modifier = Modifier.sizeIn(minWidth = 44.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row { actions() }
        }
    }
}

// =============================================
// 分类图标圆点 — 原 iOSStyle.kt 中的 CategoryDot
// =============================================

/**
 * 分类图标圆点（显示分类名称首字 + 底色）。
 */
@Composable
fun CategoryDot(
    categoryName: String,
    color: Color,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = categoryName.take(1),
            color = Color.White,
            fontSize = (size.value / 2).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// =============================================
// iOS 风格分割线
// =============================================

/**
 * iOS 风格分割线（带左侧缩进，适用于 InsetGrouped 列表行之间）。
 */
@Composable
fun IOSDivider(
    modifier: Modifier = Modifier,
    indent: Dp = Spacing.lg,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = indent),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
