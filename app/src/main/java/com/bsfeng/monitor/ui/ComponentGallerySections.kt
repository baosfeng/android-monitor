package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的基础控件区 —— DemoSection 外壳、按钮组、chip 与分段控件、开关与徽标、行式排版、进度条。负责 s12 上半部分的陈列。
// 契约 docs/交互设计/s12-组件与状态-交互契约.md 的 s12-buttons / s12-chip-seg / s12-switch-badge /
// s12-rows / s12-progress 五行：块级锚点逐字落 testTag，按下的时长与触感一律取共享基元，不手写常量。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 小节外壳：modifier 承载契约的块级锚点（如 s12-buttons），锚点不自带在共享件里。 */
@Composable
internal fun DemoSection(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth()) {
        GroupLabel(label)
        Box(Modifier.padding(horizontal = t.space4)) { content() }
    }
}

/**
 * 按钮组（契约 s12-buttons 五行）：主按钮 / 次按钮 / 禁用态 / 图标按钮 / 通栏次按钮 + 焦点与键盘。
 * 按钮本体的 0.96 按下、120ms、LIGHT 触感都由 AppButton / AppIconButton 内部基元承担。
 */
@Composable
internal fun GalleryButtons(toast: ToastHostState) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    DemoSection("按钮 Button", Modifier.testTag("s12-buttons")) {
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                // 主按钮：一屏至多 1 个（契约边界）
                AppButton(
                    "保存",
                    { toast.show("主按钮：已保存") },
                    Modifier.weight(1f).keyboardFocusRing(shape),
                )
                // 次按钮 ghost：与主按钮等宽各占一半、间距 8dp（space2）
                AppButton(
                    "取消",
                    { toast.show("次按钮：已取消") },
                    Modifier.weight(1f).keyboardFocusRing(shape),
                    AppButtonVariant.Ghost,
                )
            }
            Spacer(Modifier.height(t.space3))
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 禁用态：不可聚焦、无涟漪、无 toast；随原因文案出现（契约边界）
                AppButton("禁用态", {}, Modifier.weight(1f), AppButtonVariant.Ghost, enabled = false)
                AppIconButton(Icons.Filled.Add, "更多操作", onClick = { toast.show("图标按钮") })
            }
            Spacer(Modifier.height(t.space1))
            Text(
                "禁用原因：没有可导出的数据",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
            Spacer(Modifier.height(t.space3))
            AppButton(
                "通栏次按钮 btn-block",
                { toast.show("通栏次按钮") },
                Modifier.keyboardFocusRing(shape),
                AppButtonVariant.Ghost,
                block = true,
            )
            Spacer(Modifier.height(t.space2))
            Text(
                "Tab 按视觉顺序推进 · Enter / Space 等同 click · 焦点环 2dp",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}

/**
 * 胶囊 chip 组 + 分段控件（契约 s12-chip-seg 三行）。
 *
 * 分段控件的键盘口径（Tab 只进组一次 / 左右方向键即时选中 / Home、End 跳首尾 / 环落当前选中段）
 * 由共享 SegmentedControl 的 roving tabindex + focusGroup 承担；本页只补「区间」toast。
 */
@Composable
internal fun GalleryChipsAndSegments(
    chipIndex: Int,
    onChip: (Int) -> Unit,
    segIndex: Int,
    onSeg: (Int) -> Unit,
    toast: ToastHostState,
) {
    val t = LocalDesignTokens.current
    val ranges = listOf("今日", "本周", "本月")
    DemoSection("胶囊 Chip 与分段控件 Segmented", Modifier.testTag("s12-chip-seg")) {
        AppCard {
            // 单选 chip：同组互斥、永远有且只有一个选中；再点已选中保持选中（ChipRow 语义）
            ChipRow(listOf("全部", "前台切换", "广告跳过", "充电"), chipIndex, onChip)
            Spacer(Modifier.height(t.space3))
            SegmentedControl(ranges, segIndex, { index ->
                // 重复点当前段仍出 toast、但不重复请求（契约边界）
                onSeg(index)
                toast.show("区间：" + ranges[index])
            })
        }
    }
}

/** 开关与徽标（契约 s12-switch-badge 两行）：开关切换 = LIGHT 一次（控件内收敛，调用点不再补震）。 */
@Composable
internal fun GallerySwitchesAndBadges(
    on: Boolean,
    onOn: (Boolean) -> Unit,
    off: Boolean,
    onOff: (Boolean) -> Unit,
    toast: ToastHostState,
) {
    val t = LocalDesignTokens.current
    DemoSection("开关 Switch 与徽标 Badge", Modifier.testTag("s12-switch-badge")) {
        AppCard(padding = 0.dp) {
            TokenRow(
                title = "已开启",
                subtitle = "aria-checked=true",
                trailing = {
                    AppSwitch(on, { next ->
                        onOn(next)
                        toast.show(if (next) "开关已开启" else "开关已关闭")
                    })
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "已关闭",
                subtitle = "aria-checked=false",
                trailing = {
                    AppSwitch(off, { next ->
                        onOff(next)
                        toast.show(if (next) "开关已开启" else "开关已关闭")
                    })
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "徽标形态",
                subtitle = "数字 / 千分位 / 文本 · 纯展示不进 Tab 顺序",
                trailing = {
                    Badge("12")
                    Badge("1,286")
                    Badge("新")
                },
            )
            Spacer(Modifier.height(t.space1))
        }
    }
}

/**
 * 列表行（契约 s12-rows 三行）：分组行（带箭头，可点）/ 紧凑行（48dp，无箭头）/ 卡内静态行（纯展示）。
 * 行按下反馈由共享 TokenRow / ListRow 的点击语义承担，本页不重画行。
 */
@Composable
internal fun GalleryRows(toast: ToastHostState) {
    val t = LocalDesignTokens.current
    DemoSection("列表行 Row / ListRow", Modifier.testTag("s12-rows")) {
        Column {
            AppCard(padding = 0.dp) {
                TokenRow(
                    title = "分组行 group-row",
                    subtitle = "左图标 + 标题 + 副标题 + 右侧箭头",
                    icon = Icons.Filled.KeyboardArrowRight,
                    onClick = { toast.show("分组行：入口可点") },
                    trailing = { TailNum("42 条"); TailChevron() },
                )
                TokenDivider(first = false)
                TokenRow(
                    title = "紧凑行 list-row",
                    subtitle = "高度 48dp，无箭头不跳转，只演示行点击反馈",
                    onClick = { toast.show("分组行：带开关") },
                    trailing = { TailNum("1,286 次") },
                )
            }
            Spacer(Modifier.height(t.space3))
            AppCard(padding = 0.dp) {
                ListRow("微信", "42 分钟")
                TokenDivider(first = false)
                ListRow("哔哩哔哩", "31 分钟")
                TokenDivider(first = false)
                ListRow("淘宝", "18 分钟")
            }
        }
    }
}

/** 进度条（契约 s12-progress）：入屏自左向右生长一次，点按无反馈，右侧数值静态。 */
@Composable
internal fun GalleryProgress() {
    val t = LocalDesignTokens.current
    DemoSection("进度 Progress", Modifier.testTag("s12-progress")) {
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本月预算已用", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
                Text("¥2,040 / ¥3,000", style = MaterialTheme.typography.bodySmall, color = t.fg2)
            }
            Spacer(Modifier.height(t.space3))
            ProgressBar(fraction = 0.68f)
            Spacer(Modifier.height(t.space4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("今日支出占日均", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
                Text("34%", style = MaterialTheme.typography.bodySmall, color = t.fg2)
            }
            Spacer(Modifier.height(t.space3))
            ProgressBar(fraction = 0.34f, color = t.fg2)
        }
    }
}
