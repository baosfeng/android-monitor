package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.LineChart
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.finance.ui.categoryColorOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatHeight
import com.bsfeng.monitor.ui.theme.heatRamp
import com.bsfeng.monitor.ui.theme.sparkHeight

/**
 * 共享排版助手（W1 五屏共用：s09 设置 / s10 规则 / s11 白名单 / s12 展厅 / s13 外观）。
 *
 * 暂放本文件的原因：W1 的写 scope 不含 ui/components 目录（除 PlaceholderScreens 的删占位），
 * 因此 AppButton / AppField / ScreenHead / TokenRow 这些原型通用件先落在这里，
 * 等基元任务收编到 ui/components/ 后整体迁出（不改调用点语义）。
 * 全部走 token，本文件不含任何色值与字号字面量。
 */

// ── 交互反馈：按压 spring 统一取自 --ease-spring + --dur-press ──────────────

@Composable
internal fun pressScale(pressed: Boolean): Float {
    val t = LocalDesignTokens.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = t.springOf(MotionToken.Press),
        label = "pressScale",
    )
    return scale
}

internal enum class AppButtonVariant { Primary, Ghost }

/** 主/次按钮（原型 .btn / .btn-ghost / .btn-block / :disabled）。 */
@Composable
internal fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    block: Boolean = false,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled)
    val bg = if (variant == AppButtonVariant.Primary) t.accent else t.surface
    val fg = if (variant == AppButtonVariant.Primary) t.accentOn else t.fg
    Box(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(t.tapMin)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(t.radiusMd))
            .background(bg)
            .border(1.dp, if (variant == AppButtonVariant.Primary) t.accent else t.border, RoundedCornerShape(t.radiusMd))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onClick()
                },
            )
            .padding(horizontal = t.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) fg else t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图标按钮（原型 .icon-btn，44dp 触达）。 */
@Composable
internal fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    Box(
        modifier = modifier
            .size(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.surfaceWarm)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = t.fg, modifier = Modifier.size(t.space5))
    }
}

/** 文本输入（原型 .field：surface/surface-warm 底 + 1px 描边 + 44dp 高）。 */
@Composable
internal fun AppField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    warm: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (warm) t.surfaceWarm else t.surface)
            .border(1.dp, t.border, RoundedCornerShape(t.radiusMd))
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.fg),
            cursorBrush = SolidColor(t.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = t.muted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

// ── 屏头与行式排版（原型 .screen-head / .h1 / .h2 / .meta / .group-label / .row） ──

/** 屏头：可选返回按钮 + 标题 + 副标题 + 右侧徽标。 */
@Composable
internal fun ScreenHead(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onBack: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth().padding(start = t.space4, end = t.space4, top = t.space2, bottom = t.space3)) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.surfaceWarm)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = t.fg,
                    modifier = Modifier.size(t.space5),
                )
            }
            Spacer(Modifier.height(t.space2))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta != null) {
                    Spacer(Modifier.height(t.space1))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = t.muted)
                }
            }
            if (badge != null) {
                Spacer(Modifier.width(t.space2))
                badge()
            }
        }
    }
}

/** 分组小标题（原型 .group-label）。 */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = t.muted,
        modifier = modifier.padding(start = t.space4, end = t.space4, bottom = t.space2),
    )
}

/** 小节头：左标题 + 右说明（原型 .sec-head）。 */
@Composable
internal fun SecHead(label: String, right: String? = null, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
        if (right != null) {
            Text(right, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}

/** 1px 分隔线（原型 .row 的 border-top，首行不画）。 */
@Composable
internal fun TokenDivider(first: Boolean) {
    val t = LocalDesignTokens.current
    if (first) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
}

/** 列表行（原型 .row：左图标 + 标题 / 副标题 + 右侧尾部）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TokenRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable { onClick() }
                    else -> Modifier
                }
            )
            .padding(horizontal = t.space4, vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = t.fg2, modifier = Modifier.size(t.space5))
            }
            Spacer(Modifier.width(t.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor ?: t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space3))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                trailing()
            }
        }
    }
}

/** 紧凑行（原型 .list-row，48dp）。 */
@Composable
internal fun ListRow(title: String, value: String, subtitle: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = t.space12).padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/** 尾部数字（原型 .num：等宽、tabular）。 */
@Composable
internal fun TailNum(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
}

/** 尾部箭头（原型 .chev）。 */
@Composable
internal fun TailChevron() {
    val t = LocalDesignTokens.current
    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = t.muted, modifier = Modifier.size(t.space5))
}

/** 进度条（原型 .bar：pill 轨道 + 填充，入场 700ms --ease-out）。 */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    val t = LocalDesignTokens.current
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grown = true }
    val f by animateFloatAsState(
        targetValue = if (grown) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(t.durChart, easing = t.easeOut),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(t.space2)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.borderSoft),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(color ?: t.accent),
        )
    }
}

/**
 * 五态切换条（演示用）。
 * 显示条件走统一判据 [rememberDemoVisible]：debug 构建 + 已解锁 + 调试模式已开；release 恒为真数据。
 */
@Composable
internal fun StateSwitchRow(state: FiveState, onSelect: (FiveState) -> Unit, modifier: Modifier = Modifier) {
    if (!com.bsfeng.monitor.ui.components.rememberDemoVisible()) return
    val labels = listOf("加载态", "空态", "错误态", "有数据", "边界态")
    Column(modifier.fillMaxWidth().padding(horizontal = LocalDesignTokens.current.space4)) {
        ChipRow(
            items = labels,
            selectedIndex = FiveState.values().indexOf(state),
            onSelect = { onSelect(FiveState.values()[it]) },
        )
    }
}

/** 五态的通用骨架（与各屏真实数据无关的演示面）。 */
@Composable
internal fun FiveStateSlot(
    state: FiveState,
    emptyText: String,
    errorText: String,
    edgeText: String,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when (state) {
        FiveState.LOADING -> AppCard { StateSkeleton(rows = 4) }
        FiveState.EMPTY -> EmptyState(emptyText)
        FiveState.ERROR -> ErrorState(errorText, onRetry)
        FiveState.EDGE -> EdgeState(edgeText)
        FiveState.POPULATED -> content()
    }
}

/**
 * s12 组件与五态展示厅（原型 s12-components.html）。
 * 新人开工的参照屏：全量组件陈列 + 五态同屏演示 + 弹窗 / 抽屉 / Toast。
 */
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    var state by remember { mutableStateOf(FiveState.POPULATED) }
    var chipIndex by remember { mutableIntStateOf(0) }
    var segIndex by remember { mutableIntStateOf(0) }
    var switchOn by remember { mutableStateOf(true) }
    var switchOff by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "组件与状态",
                meta = "全量组件陈列 · 五种状态 · 弹窗 / 抽屉 / Toast",
                onBack = onBack,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = state, onSelect = { state = it })

                if (state == FiveState.POPULATED) {
                    GalleryButtons(toast)
                    GalleryChipsAndSegments(chipIndex, { chipIndex = it }, segIndex, { segIndex = it })
                    GallerySwitchesAndBadges(switchOn, { switchOn = it }, switchOff, { switchOff = it }, toast)
                    GalleryRows(toast)
                    GalleryProgress()
                    GalleryChartRamp()
                    GalleryCharts()
                }

                GalleryFiveStates(state, onSelect = { state = it }, toast = toast)
                if (state == FiveState.POPULATED) {
                    GalleryDots()
                    GalleryMotion(toast)
                    GalleryOverlays(
                        onDialog = { showDialog = true },
                        onSheet = { showSheet = true },
                        onToast = { toast.show("已保存 · 1.6s 后自动消失") },
                    )
                }
                Spacer(Modifier.height(t.space6))
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )

        if (showDialog) {
            Box(Modifier.fillMaxSize()) {
                OverlayScrim { showDialog = false }
                AppCard(Modifier.align(Alignment.Center).padding(horizontal = t.space6)) {
                    Text("删除这条记录？", style = MaterialTheme.typography.titleMedium, color = t.fg)
                    Spacer(Modifier.height(t.space2))
                    Text("删除后无法恢复，本地统计会在下次汇总时更新。", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Spacer(Modifier.height(t.space4))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                        AppButton("取消", { showDialog = false }, Modifier.weight(1f), AppButtonVariant.Ghost)
                        AppButton("删除", { showDialog = false; toast.show("记录已删除") }, Modifier.weight(1f))
                    }
                }
            }
        }

        if (showSheet) {
            // 遮罩由 BottomSheet 的窗口级全屏浮层承担（页内遮罩盖不住状态栏/手势条，且会叠成两层）
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter)) {
                    BottomSheetCard(
                        onDismiss = { showSheet = false },
                        onConfirm = { showSheet = false; toast.show("已选择：前台切换") },
                    )
                }
            }
        }
    }
}

/** 遮罩（token scrim；W0 Scrim 无高度，这里按同一 token 自绘，已记入缺陷回报）。 */
@Composable
internal fun OverlayScrim(onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(Modifier.fillMaxSize().background(t.scrim).clickable { onDismiss() })
}

@Composable
private fun BottomSheetCard(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalDesignTokens.current
    var chip by remember { mutableIntStateOf(0) }
    com.bsfeng.monitor.ui.components.BottomSheet(visible = true, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = t.space12, height = t.space1).clip(RoundedCornerShape(t.radiusPill)).background(t.border))
        }
        Spacer(Modifier.height(t.space3))
        Text("选择记录类型", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text("底部抽屉从下方推入，220ms 进入 / 150ms 退出。", style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space3))
        ChipRow(listOf("前台切换", "广告跳过", "亮屏解锁", "充电"), chip, { chip = it })
        Spacer(Modifier.height(t.space4))
        AppButton("确定", onConfirm, block = true)
    }
}

// ── s12 各小节 ────────────────────────────────────────────────────────────

@Composable
private fun DemoSection(label: String, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        GroupLabel(label)
        Box(Modifier.padding(horizontal = t.space4)) { content() }
    }
}

@Composable
private fun GalleryButtons(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
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
private fun GalleryChipsAndSegments(
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
private fun GallerySwitchesAndBadges(
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
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
private fun GalleryRows(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
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
private fun GalleryProgress() {
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

@Composable
private fun GalleryChartRamp() {
    val t = LocalDesignTokens.current
    // 原型 s12 色板卡（08-prototype.html:5115「图表灰阶色板 s1 – s7」）沿用其形态：色块行 + 图例
    // （色点 / 名称 / 右侧 token 名）；口径按现行 token 语义改正（docs/UI设计/设计规范.md §7.4）：
    //   ① chart-1..chart-16 是**分类色板**（彩色、跨主题固定），既不是中性灰阶，也不再是 7 槽；
    //   ② 趋势图用的序列色 chartSeq 跟随主题强调色，同一图表内所有数据点同色；
    //   ③ 热力条的强度阶 heatRamp 是序列色的 8–90% 透明阶梯，不单独占分类色板槽位。
    DemoSection("分类色板 chart-1 – chart-16") {
        AppCard {
            // 16 槽分两行 × 8：单行 16 块在 360dp 屏上会压到约 15dp 宽，失去比色意义。
            Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space1)) {
                        repeat(8) { col ->
                            val slot = row * 8 + col
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(t.space6)
                                    .clip(RoundedCornerShape(t.radiusSm))
                                    .background(t.chartRamp[slot]),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
            Text(
                "跨主题固定 · 同一分类在 8 套主题下同色",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
            Spacer(Modifier.height(t.space3))
            Legend(
                items = listOf(
                    LegendItem("分类色板 · 扇形分片", "chart-1..16", t.chartRamp.first()),
                    LegendItem("序列色 · 柱状 / 折线", "chartSeq", t.chartSeq),
                    LegendItem("强度阶 · 热力条", "heatRamp", t.heatRamp.last()),
                ),
            )
        }
    }
}

private val HOURS_24 = listOf(
    2f, 1f, 0f, 0f, 1f, 3f, 8f, 14f, 11f, 9f, 14f, 17f,
    12f, 15f, 19f, 16f, 21f, 18f, 13f, 10f, 7f, 5f, 3f, 2f,
)
private val WEEK_7 = listOf(62f, 88f, 74f, 96f, 120f, 110f, 128f)

@Composable
private fun GalleryCharts() {
    val t = LocalDesignTokens.current
    DemoSection("图表基元 Chart primitives") {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            AppCard {
                Text("面积曲线 area · 24h 事件量", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                AreaChart(HOURS_24, Modifier.fillMaxWidth().height(t.chartH))
                ChartAxisLabels(listOf("00", "04", "08", "12", "16", "20"))
            }
            AppCard {
                Text("折线图 line · 近 7 日趋势", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                LineChart(WEEK_7, Modifier.fillMaxWidth().height(t.chartH))
            }
            AppCard {
                Text("柱状图 bars · 近 7 日事件量", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                BarChart(WEEK_7, Modifier.fillMaxWidth().height(t.chartH))
                ChartAxisLabels(listOf("一", "二", "三", "四", "五", "六", "日"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                AppCard(Modifier.weight(1f)) {
                    // 原型 s12「图表基元」此卡的标签写 ring，实际渲染 data-chart="pie"（08-prototype.html:5153）：
                    // 占比一律实心扇形、数值只在外部图例里（无 centerLabel）。
                    Text("扇形图 pie · 占比", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Spacer(Modifier.height(t.space3))
                    PieChart(listOf(42f, 31f, 24f, 18f, 13f), Modifier.fillMaxWidth())
                }
                AppCard(Modifier.weight(1f)) {
                    Text("圆环进度 gauge", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Spacer(Modifier.height(t.space3))
                    RingProgress(0.68f, Modifier.fillMaxWidth(), centerLabel = "68%")
                }
            }
            AppCard {
                Text("迷你走势 spark + 热力条 heat", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                Sparkline(WEEK_7, Modifier.fillMaxWidth().height(t.sparkHeight))
                Spacer(Modifier.height(t.space3))
                HeatBar(HOURS_24, Modifier.fillMaxWidth().height(t.heatHeight))
            }
        }
    }
}

@Composable
private fun GalleryFiveStates(
    state: FiveState,
    onSelect: (FiveState) -> Unit,
    toast: com.bsfeng.monitor.ui.components.ToastHostState,
) {
    val t = LocalDesignTokens.current
    DemoSection("五种状态 State coverage") {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            StateCard("loading · 骨架屏", show = true) {
                StateSkeleton(rows = 5)
            }
            StateCard("empty · 空态", show = true) {
                EmptyState("开启监控后，事件会按小时汇总到这里。")
            }
            StateCard("error · 错误态", show = true) {
                ErrorState("数据库连接已断开（SQLITE_BUSY），未返回任何记录。", onRetry = { toast.show("正在重试…") })
            }
            StateCard("edge · 超长文案与计数", show = true) {
                EdgeState("边界态：超长文案 / 缺失字段 / 10000+ 计数")
            }
            AppCard(padding = 0.dp) {
                Text(
                    "populated · 有数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(start = t.space4, top = t.space3),
                )
                Spacer(Modifier.height(t.space2))
                ListRow("微信", "42 分钟", "前台切换 · 9:12")
                TokenDivider(first = false)
                ListRow("哔哩哔哩", "3 次", "广告跳过 · 9:05")
                TokenDivider(first = false)
                ListRow("高德地图", "16 分钟", "前台切换 · 8:41")
                Spacer(Modifier.height(t.space1))
            }
            if (state != FiveState.POPULATED) {
                Text(
                    "当前只看 " + state.name.lowercase() + " 一个状态块（其余四块在有数据态下同屏陈列）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                    modifier = Modifier.padding(horizontal = t.space4),
                )
                AppButton("回到有数据态", { onSelect(FiveState.POPULATED) }, Modifier.padding(horizontal = t.space4), AppButtonVariant.Ghost, block = true)
            }
        }
    }
}

@Composable
private fun StateCard(label: String, show: Boolean, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    if (!show) return
    AppCard {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space3))
        content()
    }
}

@Composable
private fun GalleryDots() {
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

/** 展台演示用分类：原型 s05 前 6 个支出分类，顺序与 finance_categories.sort_order 一致。 */
private val GALLERY_CATEGORIES = listOf("餐饮", "出行", "购物", "日用", "零食", "水果")

/** 展台演示用应用：原型 s03 跳过记录的前三个品牌 + 一个表外包名（走兜底）。 */
private val GALLERY_APPS = listOf(
    "com.tencent.mm" to "微信",
    "tv.danmaku.bili" to "哔哩哔哩",
    "com.taobao.taobao" to "淘宝",
    "com.example.unknown" to "未知",
)

@Composable
private fun GalleryMotion(toast: com.bsfeng.monitor.ui.components.ToastHostState) {
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
                            .then(rememberStaggerEnter(i))
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
            Text("计数动画已落地：" + animatedCount(42) + " 条（rememberStaggerEnter / springOf 见 s10 规则列表与按钮）", style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
    }
}

@Composable
private fun GalleryOverlays(onDialog: () -> Unit, onSheet: () -> Unit, onToast: () -> Unit) {
    DemoSection("浮层 Overlays") {
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space2)) {
                AppButton("弹窗", onDialog, Modifier.weight(1f), AppButtonVariant.Ghost)
                AppButton("底部抽屉", onSheet, Modifier.weight(1f), AppButtonVariant.Ghost)
            }
            Spacer(Modifier.height(LocalDesignTokens.current.space3))
            AppButton("Toast 轻提示", onToast, variant = AppButtonVariant.Ghost, block = true)
        }
    }
}

