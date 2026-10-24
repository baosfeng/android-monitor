package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：底部 Tab 栏 —— 6 个 Tab 的图标/文案、选中胶囊、上边 1px 分隔线与按压触感。
// 负责「底部这一条」；显示与否仍由 MainActivity 的 bottomBarVisible 决定。

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade

/**
 * 底部 Tab 栏（原型 .tabbar / .tab，00-shell.html:174-197）。
 *
 * 底 = color-mix(surface 84%)、上边 1px --border-soft、选中胶囊 = color-mix(accent 14%)、
 * 字 = accent / muted。已关闭的功能即使被点到也不会留空页：走 resolveTab 兜底到概览。
 */
@Composable
internal fun MainBottomBar(
    tabs: List<AppTab>,
    selectedTab: AppTab,
    featureOn: (com.bsfeng.monitor.feature.Feature) -> Boolean,
    onSelectTab: (AppTab) -> Unit,
    onTapHaptic: () -> Unit,
) {
    val tokens = LocalDesignTokens.current
    val barColors = NavigationBarItemDefaults.colors(
        selectedIconColor = tokens.accent,
        selectedTextColor = tokens.accent,
        indicatorColor = tokens.accentFade(14),
        unselectedIconColor = tokens.muted,
        unselectedTextColor = tokens.muted,
    )
    NavigationBar(
        modifier = Modifier.drawWithContent {
            drawContent()
            drawLine(
                color = tokens.borderSoft,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
        },
        containerColor = tokens.surface.copy(alpha = tokens.surface.alpha * 0.84f),
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            val isSelected = selectedTab == tab
            val tabColor by animateColorAsState(
                targetValue = if (isSelected) tokens.accent else tokens.muted,
                animationSpec = tokens.springOf<Color>(MotionToken.State),
            )
            NavigationBarItem(
                colors = barColors,
                selected = isSelected,
                onClick = {
                    onSelectTab(FeatureCatalog.resolveTab(tab, featureOn))
                    onTapHaptic()
                },
                icon = {
                    Icon(
                        tab.icon(isSelected),
                        contentDescription = tab.title,
                        modifier = Modifier.size(22.dp),
                        tint = tabColor,
                    )
                },
                label = {
                    // 6 个 Tab 时单项宽度约 65dp：labelSmall(12sp) + 单行不折行
                    Text(
                        text = tab.title,
                        color = tabColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
        }
    }
}

/** Tab 图标（选中态用实心图标，未选中用线描；与改造前一一对应）。 */
internal fun AppTab.icon(selected: Boolean): ImageVector = when (this) {
    AppTab.OVERVIEW -> if (selected) Icons.Filled.DateRange else Icons.Outlined.DateRange
    AppTab.LIVE -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    AppTab.AD -> Icons.Outlined.Visibility
    AppTab.FINANCE -> if (selected) Icons.Filled.AddCircle else Icons.Outlined.AddCircle
    AppTab.TRANSFER -> Icons.Outlined.SwapHoriz
    AppTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    AppTab.QUOTA -> if (selected) Icons.Filled.AccountBalanceWallet
        else Icons.Outlined.AccountBalanceWallet
}
