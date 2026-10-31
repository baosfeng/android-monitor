package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的图标与动效区 —— 分类图标网格、应用图标兜底、数字滚动/spring/错落入场演示及其演示数据。负责 s12 的图标与动效陈列。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.finance.ui.categoryColorOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot

@Composable
internal fun GalleryDots() {
    val t = LocalDesignTokens.current
    DemoSection("分类图标 CategoryIcon / 应用图标 AppIcon / 分类色板") {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            AppCard {
                Text("分类图标 · 22 个支出分类线描图标", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                // 原型 .cat-icon（08-prototype.html:1509）：34px 圆角方块 + 分类色 12% 淡底 + 78% 描边图标；
                // 取色与 s04 / s05 / s07 同一口径（categoryColorOf → chart-1..16 槽位循环，跨主题固定）。
                Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    GALLERY_CATEGORIES.forEach { name ->
                        CategoryIcon(name = name, color = categoryColorOf(name))
                    }
                }
            }
            AppCard {
                Text("应用图标 · 真实图标，取不到回落品牌色首字", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                // 原型 .app-icon（08-prototype.html:1366）：30px 方块，background 取品牌色、无品牌色回落中性色。
                Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    GALLERY_APPS.forEach { (pkg, name) ->
                        AppIcon(packageName = pkg, appName = name)
                    }
                }
            }
            AppCard {
                Text("分类色板 · 图例色点 categoryDot", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    repeat(8) { i ->
                        Box(
                            Modifier
                                .size(t.space6)
                                .clip(RoundedCornerShape(t.radiusSm))
                                .background(t.categoryDot(i)),
                        )
                    }
                }
                Spacer(Modifier.height(t.space2))
                Text("按 chart-1..chart-16 循环取色（替代旧 categoryPalette）", style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
    }
}

@Composable
internal fun GalleryMotion(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
    val t = LocalDesignTokens.current
    DemoSection("动效 Motion · 数字滚动 / 按压 spring / 错落入场") {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedNumber(value = 1286, suffix = " 条", style = MaterialTheme.typography.headlineSmall, color = t.fg)
                Spacer(Modifier.width(t.space3))
                AnimatedNumber(value = 686.5f, prefix = "¥", style = MaterialTheme.typography.titleMedium, color = t.accent)
            }
            Spacer(Modifier.height(t.space2))
            Text("AnimatedNumber：--dur-chart + --ease-out，首次出现从 0 滚上去", style = MaterialTheme.typography.labelSmall, color = t.meta)
            Spacer(Modifier.height(t.space3))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale = pressScale(pressed)
            Box(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm)
                    .testTag("card.press")
                    .clickable(interactionSource = interaction, indication = null) { toast.show("按压：spring 规格来自 --ease-spring + --dur-press") }
                    .padding(horizontal = t.space4, vertical = t.space3),
            ) {
                Text("按住我：springOf(MotionToken.Press)", style = MaterialTheme.typography.labelLarge, color = t.fg2)
            }
            Spacer(Modifier.height(t.space3))
            Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
                repeat(5) { i ->
                    Row(
                        Modifier
                            .staggerEnterAt(i)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(t.radiusSm))
                            .background(t.surfaceWarm)
                            .padding(horizontal = t.space3, vertical = t.space2),
                    ) {
                        Text(
                            "第 " + (i + 1) + " 项 · delay = index × --stagger(" + t.stagger + "ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.fg2,
                        )
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
            Text("计数动画已落地：" + animatedCount(42) + " 条（staggerEnterAt / springOf 见 s10 规则列表与按钮）", style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
    }
}

/** 展台演示用分类：原型 s05 前 6 个支出分类，顺序与 finance_categories.sort_order 一致。 */
private val GALLERY_CATEGORIES = listOf("餐饮", "出行", "购物", "日用", "零食", "水果")

/** 展台演示用应用：原型 s03 跳过记录的前三个品牌 + 一个表外包名（走兜底）。 */
private val GALLERY_APPS = listOf(
    "com.tencent.mm" to "微信",
    "tv.danmaku.bili" to "哔哩哔哩",
    "com.taobao.taobao" to "淘宝",
    "com.example.unknown" to "未知",
)
