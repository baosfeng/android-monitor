package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.finance.model.Category
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentSoftFill
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.fgFade
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/**
 * 记账五屏（s04–s08）共用的屏幕基元。
 *
 * 全部取值来自 [LocalDesignTokens]，本文件不含任何色值 / 字号 / 间距字面量；
 * 动效一律走 [springOf] 的 token 配方。五态演示开关见 [rememberFinanceStateControl]。
 */

// ─────────────────────────── 金额与计数格式化 ───────────────────────────

/** 千分位金额，[decimals] 位小数（原型 kpi 用 0 位、列表行用 2 位）。 */
internal fun formatMoney(value: Double, decimals: Int = 0): String =
    String.format(Locale.CHINA, "%,.${decimals.coerceIn(0, 6)}f", value)

/** 千分位整数（笔数 / 记录数）。 */
internal fun formatCount(value: Int): String = String.format(Locale.CHINA, "%,d", value)

/** 带 ¥ 前缀的金额。 */
internal fun yen(value: Double, decimals: Int = 0): String = "¥" + formatMoney(value, decimals)

/** 月份标签：2026-10 → 2026 年 10 月（与原型一致，保留空格）。 */
internal fun monthLabelOf(yearMonth: String): String {
    val parts = yearMonth.split("-")
    if (parts.size < 2) return yearMonth
    val month = parts[1].toIntOrNull() ?: return yearMonth
    return parts[0] + " 年 " + month + " 月"
}

// ─────────────────────────── 屏幕头 / 月份条 / 图例点 ───────────────────────────

/**
 * 屏幕大标题（左对齐 h1 + 副标题 meta + 右侧动作）。
 *
 * 长按标题可唤出 DEBUG 五态开关（见 [FinanceStateBar]）——正式包不生效。
 */
@Composable
internal fun FinanceScreenHead(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    consumeTopInset: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onLongPress != null) {
                        Modifier.pointerInput(onLongPress) {
                            detectTapGestures(onLongPress = { onLongPress() })
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 原型 .screen-head .h1 = 28px/700/-0.42px → headlineMedium（设计规范 §3.1 映射表）
            Text(title, style = MaterialTheme.typography.headlineMedium, color = t.fg)
            if (subtitle != null) {
                Spacer(Modifier.height(t.space1))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space2))
            trailing()
        }
    }
}

/**
 * 圆形图标按钮（屏幕头右侧动作 / 月份切换）。
 *
 * 造型对齐原型 `.icon-btn`（00-shell:1117-1126）：--icon-size 44×44、radius-pill、
 * 底 fg 5%、描边 --border-soft。月份条内的同类按钮原型显式去掉底与边框
 * （`.s04-month .icon-btn{border-color:transparent;background:transparent}`，00-shell:1929），
 * 由 [chrome] 关闭，避免与月份胶囊底叠成两层。
 */
@Composable
internal fun FinanceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** 是否画 `.icon-btn` 的浅灰圆底 + 描边；月份条内传 false（原型是透明态）。 */
    chrome: Boolean = true,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .size(t.tapMin)
            .clip(shape)
            .then(
                if (chrome) {
                    Modifier
                        .background(t.fgFade(5))
                        .border(1.dp, t.borderSoft, shape)
                } else {
                    Modifier
                },
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 月份切换条：整宽胶囊（浅灰底 + 1px 描边）内「左右 chevron + 中间月份标签」，
 * 切换后标签做一次 --ease-spring 弹一下。
 *
 * 造型对齐原型 `.s04-month` / `.s06-month` / `.s07-month`（00-shell:1928/1997/2036）：
 * padding 3px、radius-pill、底 fg 4%、描边 --border-soft；高度 52px 由
 * 内部 44px 图标按钮 + 上下 4dp 内边距自然撑出（原型的 3px 内边距 ≈ space-1）。
 */
@Composable
internal fun FinanceMonthBar(
    monthText: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val bump = remember { Animatable(1f) }
    LaunchedEffect(monthText) {
        bump.snapTo(1.08f)
        bump.animateTo(1f, t.springOf(MotionToken.Press))
    }
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(shape)
            .background(t.fgFade(4))
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinanceIconButton(onClick = onPrevious, contentDescription = "上个月", chrome = false) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
        Text(
            monthText,
            style = MaterialTheme.typography.titleLarge,
            color = t.fg,
            modifier = Modifier.graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            },
        )
        FinanceIconButton(onClick = onNext, contentDescription = "下个月", chrome = false) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

/**
 * 分类图例色点：颜色一律由 [categoryColorOf] 决定（s04/s05/s07 同一个口径）。
 *
 * 圆角固定 3dp —— 原型 `.list-row .legend-dot{width:10px;height:10px;border-radius:3px}`
 * 是圆角小方块（不是圆点），与统计页图例 [com.bsfeng.monitor.ui.components.Legend] 的 3dp 口径一致。
 */
@Composable
internal fun LegendDot(color: Color, modifier: Modifier = Modifier, size: Dp? = null) {
    val t = LocalDesignTokens.current
    Box(
        modifier
            .size(size ?: t.dotSize)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

/** 记账环直径：原型 s04:71-73 / s06:62-64 / s07:57-59 为 104px（小于全局 --ring-size = 128px）。 */
private val FINANCE_RING_DIAMETER = 104.dp

/** 圆环仪表（预算使用率）：RingProgress + 中心大字 + 小标签。 */
@Composable
internal fun FinanceRingGauge(
    fraction: Float,
    big: String,
    small: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val t = LocalDesignTokens.current
    Box(modifier.size(FINANCE_RING_DIAMETER), contentAlignment = Alignment.Center) {
        RingProgress(
            fraction = fraction.coerceIn(0f, 1f),
            color = color,
            animated = rememberMotionEnabled(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                big,
                style = MaterialTheme.typography.titleMedium,
                color = t.fg,
                fontWeight = FontWeight.Bold,
            )
            Text(small, style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
    }
}

/**
 * 取当前宿主 Activity 作用域内的 [FinanceViewModel]。
 *
 * s05 / s07 的入参由 MainActivity 传入（本轮不修改 MainActivity），需要额外派生数据
 * （近 7 天、24 小时分布、加载态）时回退到宿主同一实例；宿主不是 ViewModelStoreOwner 时返回 null。
 */
@Composable
internal fun rememberFinanceViewModel(): FinanceViewModel? {
    val context = LocalContext.current
    return if (context is ViewModelStoreOwner) viewModel<FinanceViewModel>() else null
}

// ─────────────────────────── 分类色（三屏唯一口径） ───────────────────────────

/**
 * 分类 → 颜色的**唯一**映射：s04 交易行色点 / s05 分类网格 / s07 图例色点共用同一函数，
 * 因此同一分类在任何一屏取到同一个颜色。
 *
 * 色板是跨主题固定的 chart-1..chart-16：切主题时分类身份色不变（只有序列色跟着主题走）。
 */
@Composable
internal fun categoryColorOf(name: String): Color =
    LocalDesignTokens.current.categoryDot(categorySlotOf(name))

/**
 * 分类槽位（分类色板 chart-1..chart-16 的下标）。
 *
 * 支出 / 收入分类按 [Category] 的预设顺序取槽 —— 该顺序与 finance_categories.sort_order
 * 一致，因此相邻分类天然不重色；自定义分类再按名称稳定哈希兜底（跨屏 / 跨进程结果一致）。
 */
internal fun categorySlotOf(name: String): Int {
    val expense = Category.defaultExpenseCategories.indexOf(name)
    if (expense >= 0) return expense
    val income = Category.defaultIncomeCategories.indexOf(name)
    if (income >= 0) return Category.defaultExpenseCategories.size + income
    var hash = 0
    for (ch in name) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return hash % CHART_SLOT_COUNT
}

/** 分类色板共 16 槽（chart-1..chart-16）。 */
private const val CHART_SLOT_COUNT = 16

// ─────────────────────────── 空态卡（带行动按钮） ───────────────────────────

/**
 * 记账五屏的空态卡：图标 + 标题 + 说明 + 行动按钮。
 *
 * 文案逐屏取原型 `st-title` / `st-desc`（s04:163-165 / s05:108-110 / s06:117-119 /
 * s07:134-136 / s08:103-105），每屏文案与 CTA 都不同；自绘而不改
 * [com.bsfeng.monitor.ui.components.StateContainer]（该组件由 W1 维护）。
 */
@Composable
internal fun FinanceEmptyState(
    title: String,
    desc: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .padding(t.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(t.space12)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.accentSoftFill()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = t.accent, modifier = Modifier.size(t.iconSize))
        }
        Spacer(Modifier.height(t.space3))
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(t.space1))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = t.muted, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(t.space3))
            FinanceCtaButton(text = actionText, onClick = onAction)
        }
    }
}

/** 原型 `.btn-ghost` 的空态行动按钮（surface-warm 底 + accent 文字）。 */
@Composable
internal fun FinanceCtaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .clickable { onClick() }
            .padding(horizontal = t.space6, vertical = t.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = t.accent, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────── 五态演示开关（DEBUG） ───────────────────────────

/**
 * 五态覆盖：[value] 非空时屏幕按该态渲染，为空时按真实数据推导。
 * 仅 DEBUG 包可唤出切换条，正式包恒为 null。
 */
@Stable
internal class FinanceStateControl(
    private val override: MutableState<FiveState?>,
    private val barVisible: MutableState<Boolean>,
) {
    val value: FiveState? get() = override.value
    val barShown: Boolean get() = barVisible.value

    fun toggleBar() {
        barVisible.value = !barVisible.value
    }

    fun select(state: FiveState?) {
        override.value = state
    }
}

@Composable
internal fun rememberFinanceStateControl(): FinanceStateControl {
    val override = remember { mutableStateOf<FiveState?>(null) }
    val barVisible = remember { mutableStateOf(false) }
    return remember { FinanceStateControl(override, barVisible) }
}

/** 五态 + 自动 的横向切换条（演示件）；统一判据 [rememberDemoVisible] + 长按过标题才出现。 */
@Composable
internal fun FinanceStateBar(control: FinanceStateControl, modifier: Modifier = Modifier) {
    if (!control.barShown) return
    if (!com.bsfeng.monitor.ui.components.rememberDemoVisible()) return
    val t = LocalDesignTokens.current
    val options: List<Pair<String, FiveState?>> = listOf(
        "自动" to null,
        "加载" to FiveState.LOADING,
        "空" to FiveState.EMPTY,
        "失败" to FiveState.ERROR,
        "正常" to FiveState.POPULATED,
        "边界" to FiveState.EDGE,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.surfaceWarm)
            .padding(t.space1),
        horizontalArrangement = Arrangement.spacedBy(t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (label, state) ->
            val selected = control.value == state
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(if (selected) t.accent else t.surface)
                    .clickable { control.select(state) }
                    .padding(horizontal = t.space2, vertical = t.space1),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) t.accentOn else t.fg2,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
