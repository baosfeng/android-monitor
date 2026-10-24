package com.bsfeng.monitor.ui

// 从 ui/SettingsFeatureSection.kt 拆出：设置「功能 · 页面轨迹」卡 —— 页面切换开关与记录内容说明。负责「只存指纹的页面轨迹开不开」。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「功能 · 页面轨迹」卡 —— 页面切换开关与记录内容说明。负责「只存指纹的页面轨迹开不开」。
 */
@Composable
internal fun SettingsPageTraceFeatureCard(
    t: DesignTokens,
    pageTraceOn: Boolean,
    setFeature: (Feature, Boolean) -> Unit,
) {
// 页面轨迹（指纹档）：零新权限，detail 只存包名/应用名/指纹，不存页面文字
AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
TokenRow(
    title = "页面轨迹",
    subtitle = if (pageTraceOn) {
        "记录 App 内页面切换，只存指纹"
    } else {
        "已关闭：不记录页面切换"
    },
    icon = Icons.Outlined.Article,
    trailing = { AppSwitch(pageTraceOn, { setFeature(Feature.PAGE_TRACE, it) }) },
)
TokenDivider(first = false)
if (pageTraceOn) {
    TokenRow(
        title = "记录内容",
        subtitle = "包名 / 应用名 / 页面指纹 / 停留时长",
        icon = Icons.Outlined.Visibility,
    )
} else {
    TokenRow(
        title = "页面轨迹明细",
        subtitle = FeatureCatalog.offHint(Feature.PAGE_TRACE),
        icon = Icons.Outlined.Visibility,
    )
}
Spacer(Modifier.height(t.space1))
    Spacer(Modifier.height(t.space1))
}
}
