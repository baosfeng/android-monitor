package com.bsfeng.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.sparkHeight
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import androidx.compose.foundation.layout.navigationBarsPadding

/**
 * s13 外观（原型 s13-appearance.html）：8 套主题选择器 + 实时预览。
 *
 * 主题状态的唯一来源是 W0 的 ThemeRepository（setTheme 切换 + 持久化），
 * 本屏不写任何存储、不含任何按主题分叉的样式——差异全部由 token 承载。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val current by ThemeRepository.themeId.collectAsState()
    var state by remember { mutableStateOf(FiveState.POPULATED) }

    Box(modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "外观",
                meta = "切换即时生效，重启后保持",
                onBack = onBack,
                consumeTopInset = true,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = state, onSelect = { state = it })

                when (state) {
                    FiveState.LOADING -> Column(
                        Modifier.fillMaxWidth().padding(horizontal = t.space4),
                        verticalArrangement = Arrangement.spacedBy(t.space3),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                            repeat(2) { Box(Modifier.weight(1f)) { SkeletonBlock(height = t.space12) } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                            repeat(2) { Box(Modifier.weight(1f)) { SkeletonBlock(height = t.space12) } }
                        }
                        AppCard {
                            SkeletonBlock(height = t.space6)
                            Spacer(Modifier.height(t.space2))
                            SkeletonBlock(height = t.space4)
                        }
                    }

                    FiveState.EMPTY -> Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                        AppCard {
                            Text("主题列表为空", style = MaterialTheme.typography.titleMedium, color = t.fg)
                            Spacer(Modifier.height(t.space2))
                            Text(
                                "未在本地找到任何主题 token，将回退到默认 " + ThemeRepository.displayName(ThemeRepository.DEFAULT_THEME) + "。",
                                style = MaterialTheme.typography.bodySmall,
                                color = t.muted,
                            )
                            Spacer(Modifier.height(t.space3))
                            AppButton("载入默认主题", onClick = { ThemeRepository.setTheme(ThemeRepository.DEFAULT_THEME) }, variant = AppButtonVariant.Ghost, block = true)
                        }
                    }

                    FiveState.ERROR -> Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                        AppCard {
                            Text("主题应用失败", style = MaterialTheme.typography.titleMedium, color = t.dangerText)
                            Spacer(Modifier.height(t.space2))
                            Text(
                                "token 解析异常：--accent 缺失，已保留上一个生效主题（" + ThemeRepository.displayName(current) + "）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = t.muted,
                            )
                            Spacer(Modifier.height(t.space3))
                            AppButton("重新应用", { ThemeRepository.setTheme(current) }, block = true)
                        }
                    }

                    FiveState.EDGE -> Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                        AppCard(padding = 0.dp) {
                            TokenRow(
                                title = "超长主题名：墨青 · Kami（含中日韩字形回退与等宽数字变体）",
                                subtitle = "含 10,286 个 token 覆盖测试",
                                trailing = { TailNum("10,286") },
                            )
                            TokenDivider(first = false)
                            TokenRow(
                                title = "边界态：主题缺少 --text-xl 槽位",
                                subtitle = "标题回退到 --text-lg，数值 10,000 条",
                                trailing = { TailNum("10,000") },
                            )
                            Spacer(Modifier.height(t.space1))
                        }
                    }

                    FiveState.POPULATED -> {
                        // ── 主题选择器（8 套 · 两列 · 原型 .theme-picker / .theme-opt，s13-appearance.html:12-23,50-83）──
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = t.space4),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("主题", style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
                                // 原型 s13-current：apple 只显英文名，其余「中文 英文」
                                Text("当前 " + themeLabel(current), style = MaterialTheme.typography.bodySmall, color = t.muted)
                            }
                            Spacer(Modifier.height(t.space2))
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = t.space4),
                                verticalArrangement = Arrangement.spacedBy(t.space2),
                            ) {
                                ThemeRepository.themeIds.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                                        pair.forEach { id ->
                                            ThemeGridItem(
                                                id = id,
                                                selected = id == current,
                                                onClick = { ThemeRepository.setTheme(id) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        // ── 实时预览（底色 / 文字 / 描边 / 灰阶同步变化）──
                        Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                            GlassCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("实时预览", style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
                                    Text("底色 / 文字 / 描边 / 灰阶同步变化", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                }
                                Spacer(Modifier.height(t.space3))
                                Text("今日事件", style = MaterialTheme.typography.labelMedium, color = t.meta)
                                Spacer(Modifier.height(t.space1))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    AnimatedNumber(value = 128, style = MaterialTheme.typography.headlineMedium, color = t.fg)
                                    Spacer(Modifier.width(t.space1))
                                    Text("条", style = MaterialTheme.typography.labelMedium, color = t.muted)
                                    Spacer(Modifier.weight(1f))
                                    Text("较昨日 +12%", style = MaterialTheme.typography.labelMedium, color = t.successText)
                                }
                                Spacer(Modifier.height(t.space2))
                                Sparkline(
                                    values = listOf(62f, 88f, 74f, 96f, 120f, 110f, 128f),
                                    modifier = Modifier.fillMaxWidth().height(t.sparkHeight),
                                )
                                Spacer(Modifier.height(t.space3))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 占比用实心扇形图（原型 s13:5393 .pv-ring data-chart="pie"）。
                                    // 原型此图不叠中心文案（无 .s07-ringctr 那样的 .pv-ring 中心块），故无 centerLabel。
                                    // 分片色与右图例**同源**：两侧都按 s1..s5 下标取 chartRamp（原型 s13:5393-5398），
                                    // 显式传 colors 把这条同源关系钉在调用点上（图例只列前 4 项，与原型一致）。
                                    PieChart(
                                        segments = listOf(42f, 31f, 24f, 18f, 13f),
                                        modifier = Modifier.size(t.ringSize),
                                        colors = t.chartRamp,
                                        size = t.ringSize,
                                    )
                                    Spacer(Modifier.width(t.space4))
                                    Legend(
                                        // 原型图例 = 色点 + 名称 + 数值（s13:5395-5398），分类色 s1..s4 = chart-1..4。
                                        items = listOf(
                                            LegendItem("前台切换", "42", t.chartRamp[0]),
                                            LegendItem("广告跳过", "34", t.chartRamp[1]),
                                            LegendItem("亮屏解锁", "28", t.chartRamp[2]),
                                            LegendItem("充电", "24", t.chartRamp[3]),
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(t.space3))
                                TokenRow(
                                    title = "微信",
                                    subtitle = "今日前台 42 分钟 · 跳过 3 次",
                                    trailing = { TailNum("12 次") },
                                )
                            }
                        }

                        // ── 说明 ──
                        Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                            AppCard {
                                Text(
                                    "8 套主题的差异全部由 token 承载（底色、文字、描边、圆角、字号、图表灰阶），没有一屏写死颜色，也没有按主题分支的样式。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = t.fg2,
                                )
                                Spacer(Modifier.height(t.space2))
                                Text(
                                    "选择态只用描边加粗 + 字重提升表示，不占用强调色预算。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                )
                                Spacer(Modifier.height(t.space3))
                                Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                                    val preview = DesignTokens(current)
                                    Badge("bg " + hex(preview.bg), tone = BadgeTone.Neutral)
                                    Badge("accent " + hex(preview.accent), tone = BadgeTone.Accent)
                                    Badge("fg " + hex(preview.fg), tone = BadgeTone.Neutral)
                                }
                                Spacer(Modifier.height(t.space2))
                                Text(
                                    "切换入口：ThemeRepository.setTheme(id)（返回 false = 未知 id，状态不变）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(t.space6))
                    }
                }
            }
        }
    }
}

/** 主题中文名（原型 s13-appearance.html:51-82 的 .theme-cn）。 */
private val THEME_CN: Map<String, String> = mapOf(
    "apple" to "苹果",
    "glassmorphism" to "玻璃",
    "notion" to "纸面",
    "stripe" to "条纹",
    "claude" to "陶土",
    "ibm" to "工业",
    "kami" to "墨青",
    "bento" to "便当",
)

/** 主题英文名（原型 .theme-en）。 */
private val THEME_EN: Map<String, String> = mapOf(
    "apple" to "Apple",
    "glassmorphism" to "Glassmorphism",
    "notion" to "Notion",
    "stripe" to "Stripe",
    "claude" to "Claude",
    "ibm" to "IBM",
    "kami" to "Kami",
    "bento" to "Bento",
)

/** 未知 id 原样回显（与 ThemeRepository.displayName 同一 fail-open 口径）。 */
private fun themeNameCn(id: String): String = THEME_CN[id] ?: id

private fun themeNameEn(id: String): String = THEME_EN[id] ?: id

/** 「当前」标签：原型 s13-current 的 NAME 表（apple 只显英文，其余「中文 英文」）。 */
private fun themeLabel(id: String): String =
    if (id == ThemeRepository.DEFAULT_THEME) themeNameEn(id) else themeNameCn(id) + " " + themeNameEn(id)

/**
 * 主题选项卡（原型 s13 的 .theme-opt / .theme-swatch，s13-appearance.html:13-23）：
 * 左 = 46×36 三段色卡（底 0-34% / 表面 34-64% / 文字 64-100%，140° 线性渐变）；
 * 右 = 中文（17sp）+ 英文（12sp muted）两行；
 * 选中态 = 描边加粗到 fg 40% + 文字转 fg + 字重 600（原型 .theme-opt[aria-pressed="true"]）。
 */
@Composable
private fun ThemeGridItem(
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val preview = DesignTokens(id)
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) t.fgFade(40) else t.borderSoft,
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 36.dp)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to preview.bg,
                            0.34f to preview.bg,
                            0.34f to preview.surface,
                            0.64f to preview.surface,
                            0.64f to preview.fg2,
                            1f to preview.fg2,
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                ),
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                themeNameCn(id),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) t.fg else t.fg2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                themeNameEn(id),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
        }
    }
}

/** 颜色 → #RRGGBB（仅用于展示当前主题取值，不参与样式）。 */
private fun hex(color: androidx.compose.ui.graphics.Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#" + r.toString(16).padStart(2, '0') + g.toString(16).padStart(2, '0') + b.toString(16).padStart(2, '0')
}
