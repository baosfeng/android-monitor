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
import androidx.compose.ui.platform.testTag
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
    // 选中态用 accentText 而不是 accent：accent 是填充色，当文字色时掉到 4.5:1 以下
    // （apple #0071e3 在 surface 上 4.31:1、notion 4.19:1）；accent-text 8 套主题实测 4.78–12.24:1。
    val barColors = NavigationBarItemDefaults.colors(
        selectedIconColor = tokens.accentText,
        selectedTextColor = tokens.accentText,
        indicatorColor = tokens.accentFade(14),
        unselectedIconColor = tokens.muted,
        unselectedTextColor = tokens.muted,
    )
    NavigationBar(
        // 锚点 s21 §tabbar：底部 Tab 栏容器锚点
        modifier = Modifier.testTag("tabbar").drawWithContent {
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
            // 图标与文案都吃这个 tabColor（会压过 barColors 的同名字段），所以选中态也走 accentText。
            val tabColor by animateColorAsState(
                targetValue = if (isSelected) tokens.accentText else tokens.muted,
                animationSpec = tokens.springOf<Color>(MotionToken.State),
            )
            NavigationBarItem(
                // 锚点 s21 的 tab-* 锚点；传送 Tab 账本未定义，返回 null 时不自增
                modifier = tab.contractAnchor()?.let { Modifier.testTag(it) } ?: Modifier,
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

/**
 * 锚点 s21 定义的底部 Tab 锚点（原型 `.tabbar / .tab-*`）。
 *
 * 只映射账本里写明的 6 个：传送 Tab 在 s21 账本中未定义锚点，返回 null 而不是自造一个
 * `tab-transfer`——锚点集合要能与锚点逐字对账。
 */
internal fun AppTab.contractAnchor(): String? = when (this) {
    AppTab.OVERVIEW -> "tab-overview"
    AppTab.LIVE -> "tab-live"
    AppTab.AD -> "tab-ad"
    AppTab.FINANCE -> "tab-finance"
    AppTab.SETTINGS -> "tab-settings"
    AppTab.QUOTA -> "tab-quota"
    AppTab.TRANSFER -> null
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
