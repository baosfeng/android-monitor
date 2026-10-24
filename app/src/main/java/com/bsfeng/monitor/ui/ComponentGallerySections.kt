package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的基础控件区 —— DemoSection 外壳、按钮组、chip 与分段控件、开关与徽标、行式排版、进度条。负责 s12 上半部分的陈列。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun DemoSection(label: String, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        GroupLabel(label)
        Box(Modifier.padding(horizontal = t.space4)) { content() }
    }
}

@Composable
internal fun GalleryButtons(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
    DemoSection("按钮 Button") {
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space2)) {
                AppButton("保存", { toast.show("主按钮：已保存") }, Modifier.weight(1f))
                AppButton("取消", { toast.show("次按钮：已取消") }, Modifier.weight(1f), AppButtonVariant.Ghost)
            }
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            Row(
                horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton("禁用态", {}, Modifier.weight(1f), AppButtonVariant.Ghost, enabled = false)
                AppIconButton(Icons.Filled.Add, "更多操作", onClick = { toast.show("图标按钮") })
            }
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            AppButton("通栏次按钮 btn-block", { toast.show("通栏次按钮") }, variant = AppButtonVariant.Ghost, block = true)
        }
    }
}

@Composable
internal fun GalleryChipsAndSegments(
    chipIndex: Int,
    onChip: (Int) -> Unit,
    segIndex: Int,
    onSeg: (Int) -> Unit,
) {
    DemoSection("胶囊 Chip 与分段控件 Segmented") {
        AppCard {
            ChipRow(listOf("全部", "前台切换", "广告跳过", "充电"), chipIndex, onChip)
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            SegmentedControl(listOf("今日", "本周", "本月"), segIndex, onSeg)
        }
    }
}

@Composable
internal fun GallerySwitchesAndBadges(
    on: Boolean,
    onOn: (Boolean) -> Unit,
    off: Boolean,
    onOff: (Boolean) -> Unit,
    toast: com.bsfeng.monitor.ui.components.ToastHostState,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    DemoSection("开关 Switch 与徽标 Badge") {
        AppCard(padding = 0.dp) {
            TokenRow(
                title = "已开启",
                subtitle = "aria-checked=\"true\"",
                trailing = {
                    AppSwitch(on, {
                        onOn(!on)
                        view.performConfirmHaptic()
                        toast.show(if (on) "开关已关闭" else "开关已开启")
                    })
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "已关闭",
                subtitle = "aria-checked=\"false\"",
                trailing = {
                    AppSwitch(off, {
                        onOff(!off)
                        view.performConfirmHaptic()
                        toast.show(if (off) "开关已关闭" else "开关已开启")
                    })
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "徽标形态",
                subtitle = "数字 / 千分位 / 文本",
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

@Composable
internal fun GalleryRows(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
    val t = LocalDesignTokens.current
    DemoSection("列表行 Row / ListRow") {
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
                    subtitle = "高度 48dp，用于密集列表",
                    icon = Icons.Filled.KeyboardArrowRight,
                    onClick = { toast.show("紧凑行：入口可点") },
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

@Composable
internal fun GalleryProgress() {
    val t = LocalDesignTokens.current
    DemoSection("进度 Progress") {
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
