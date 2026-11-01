package com.bsfeng.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.sparkHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * s13 外观（原型 s13-appearance.html / 锚点账本）：8 套主题两列选择器 +
 * 实时预览 + 说明卡 + 五态。选项即生效：选中即时切换、无保存按钮、无二次确认。
 *
 * 主题状态的唯一来源是 W0 的 ThemeRepository（setTheme 切换 + 持久化），本屏不写任何存储、
 * 不含任何按主题分叉的样式——差异全部由 token 承载。
 *
 * 19 个锚点逐字落 `Modifier.testTag`：screen-s13 / s13-head / s13-body / s13-picker /
 * s13-picker-grid / s13-theme-{apple,glass,notion,stripe,claude,ibm,kami,bento} / s13-preview /
 * s13-note / s13-st-{loading,empty,error,edge}。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val current by ThemeRepository.themeId.collectAsState()
    var state by remember { mutableStateOf(FiveState.POPULATED) }
    var retrying by remember { mutableStateOf(false) }
    val toast = rememberToastHostState()
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    /**
     * 退出外观屏：先登记「焦点回设置页『外观与主题』行」的一次性信号，再退页。
     * 锚点 s13-head · 退出后：退出后焦点回设置页「外观与主题」行（按钮 / 返回手势等效）。
     */
    val requestBack: () -> Unit = {
        AppearanceFocusReturn.request()
        onBack()
    }
    // 覆盖层里本页先于 AnimatedScreenOverlay 的 BackHandler 接管返回：手势退页也走同一条回位信号
    BackHandler { requestBack() }

    val ids = remember { ThemeRepository.themeIds }
    val focusRequesters = remember(ids) { List(ids.size) { FocusRequester() } }
    var focusIndex by remember { mutableIntStateOf(ids.indexOf(current).coerceAtLeast(0)) }
    var keyboardMoving by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf<Int?>(null) }

    // Tab 进组落在当前选中项：选中变了，组内键盘焦点下标跟着走
    LaunchedEffect(current) { focusIndex = ids.indexOf(current).coerceAtLeast(0) }

    // 方向键移动焦点：等一帧让 canFocus 落到新项上再 requestFocus，否则请求落在不可聚焦的旧节点上
    LaunchedEffect(focusIndex, keyboardMoving) {
        if (!keyboardMoving) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequesters.getOrNull(focusIndex)?.requestFocus() }
        keyboardMoving = false
    }

    // 状态退出后的焦点迁移（口径「移除/关闭后」：空态 → Apple 项；错误态 → 当前主题项）
    LaunchedEffect(focusTarget, state) {
        val target = focusTarget ?: return@LaunchedEffect
        if (state != FiveState.POPULATED) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequesters.getOrNull(target)?.requestFocus() }
        if (focusTarget == target) focusTarget = null
    }

    // 重新应用：窗口内按钮已被骨架替换，连点不叠加请求；窗口结束按 setTheme 结果回落
    LaunchedEffect(retrying) {
        if (!retrying) return@LaunchedEffect
        delay(RETRY_APPLY_MS)
        val applied = ThemeRepository.setTheme(current)
        retrying = false
        toast.show("已重试应用 " + themeLabel(current))
        if (applied) {
            state = FiveState.POPULATED
            scroll.scrollTo(0)
            focusTarget = ids.indexOf(current).coerceAtLeast(0)
        }
    }

    /** 选项即生效：同一主题不重复写盘，仍出触感与同一条 toast（锚点 s13-theme-*）。 */
    fun pickTheme(id: String) {
        haptic(HapticTone.LIGHT)
        if (id != current) {
            // 未知 id fail-open：回落到默认主题，不抛错（口径边界「未知主题 id 一律回落到 Apple」）
            if (!ThemeRepository.setTheme(id)) ThemeRepository.setTheme(ThemeRepository.DEFAULT_THEME)
        }
        toast.show("已切换到 " + themeLabel(id))
        if (state == FiveState.ERROR) {
            // 错误态下换主题即重试：应用成功就退出错误态并回顶部
            state = FiveState.POPULATED
            scope.launch { scroll.scrollTo(0) }
            focusTarget = ids.indexOf(id).coerceAtLeast(0)
        }
    }

    /** 空态 CTA：载入默认主题、退出空态、焦点落 Apple 项、滚动回顶部。 */
    fun loadDefaultTheme() {
        ThemeRepository.setTheme(ThemeRepository.DEFAULT_THEME)
        toast.show("已回退到默认 " + ThemeRepository.displayName(ThemeRepository.DEFAULT_THEME))
        state = FiveState.POPULATED
        scope.launch { scroll.scrollTo(0) }
        focusTarget = ids.indexOf(ThemeRepository.DEFAULT_THEME).coerceAtLeast(0)
    }

    Box(modifier.fillMaxSize().pageBackdrop().testTag("screen-s13")) {
        Column(Modifier.fillMaxSize()) {
            // 页头：标题 + 副标题 + 返回；点标题与副标题无反应，返回按钮退出本页
            ScreenHead(
                title = "外观",
                meta = "切换即时生效，重启后保持",
                onBack = requestBack,
                consumeTopInset = true,
                modifier = Modifier.testTag("s13-head"),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .testTag("s13-body")
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = state, onSelect = { state = it })

                when (state) {
                    FiveState.LOADING -> AppearanceLoadingBlock(Modifier.testTag("s13-st-loading"))

                    // 本态只有 44dp 图标 + 标题 + 描述 + 一个按钮；不渲染网格与预览
                    FiveState.EMPTY -> EmptyState(
                        text = "未在本地找到任何主题 token，将回退到默认 " +
                            ThemeRepository.displayName(ThemeRepository.DEFAULT_THEME) + "。",
                        title = "主题列表为空",
                        modifier = Modifier.testTag("s13-st-empty"),
                        actionLabel = "载入默认主题",
                        onAction = { loadDefaultTheme() },
                    )

                    FiveState.ERROR -> {
                        if (retrying) {
                            AppearanceLoadingBlock(Modifier.testTag("s13-st-loading"))
                        } else {
                            ErrorState(
                                text = "token 解析异常：--accent 缺失，已保留上一个生效主题（" +
                                    ThemeRepository.displayName(current) + "）。",
                                title = "主题应用失败",
                                modifier = Modifier.testTag("s13-st-error"),
                                actionLabel = "重新应用",
                                onRetry = { if (!retrying) retrying = true },
                            )
                        }
                        // 本态下网格仍然可用：换主题即重试（口径边界）
                        AppearanceThemePicker(
                            ids = ids,
                            current = current,
                            focusIndex = focusIndex,
                            focusRequesters = focusRequesters,
                            onMoveFocus = {
                                focusIndex = it
                                keyboardMoving = true
                            },
                            onPick = { pickTheme(it) },
                        )
                        Spacer(Modifier.height(t.space6))
                    }

                    FiveState.EDGE -> AppearanceEdgeBlock(Modifier.testTag("s13-st-edge"))

                    FiveState.POPULATED -> {
                        AppearanceThemePicker(
                            ids = ids,
                            current = current,
                            focusIndex = focusIndex,
                            focusRequesters = focusRequesters,
                            onMoveFocus = {
                                focusIndex = it
                                keyboardMoving = true
                            },
                            onPick = { pickTheme(it) },
                        )
                        AppearancePreviewCard(Modifier.testTag("s13-preview"))
                        AppearanceNoteCard(currentId = current, modifier = Modifier.testTag("s13-note"))
                        Spacer(Modifier.height(t.space6))
                    }
                }
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 「重新应用」转 loading 的最长窗口（锚点 s13-st-error：最长 800ms）。 */
private const val RETRY_APPLY_MS = 800L

/** 选择器区块装配：锚点 s13-picker（区块）/ s13-picker-grid（两列网格）。 */
@Composable
private fun AppearanceThemePicker(
    ids: List<String>,
    current: String,
    focusIndex: Int,
    focusRequesters: List<FocusRequester>,
    onMoveFocus: (Int) -> Unit,
    onPick: (String) -> Unit,
) {
    ThemePicker(
        ids = ids,
        currentId = current,
        focusIndex = focusIndex,
        focusRequesters = focusRequesters,
        onMoveFocus = onMoveFocus,
        onPick = onPick,
        modifier = Modifier.testTag("s13-picker"),
        gridModifier = Modifier.testTag("s13-picker-grid"),
    )
}

/** 加载态：8 个主题位骨架（与真实网格同尺寸，落位不跳版）+ 预览卡骨架；无文字、无按钮。 */
@Composable
private fun AppearanceLoadingBlock(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = t.space4),
            verticalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            repeat(4) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    repeat(2) { Box(Modifier.weight(1f)) { SkeletonBlock(height = t.rowMinH) } }
                }
            }
        }
        Box(Modifier.padding(horizontal = t.space4)) {
            AppCard { StateSkeleton(rows = 3) }
        }
        Spacer(Modifier.height(t.space6))
    }
}

/** 实时预览（锚点 s13-preview）：KPI + 趋势 + 占比环 + 图例 + 一行；点卡内任意位置无操作。 */
@Composable
private fun AppearancePreviewCard(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    GlassCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("实时预览", style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
            Text("底色 / 文字 / 描边 / 灰阶同步变化", style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
        Spacer(Modifier.height(t.space3))
        Text("今日事件", style = MaterialTheme.typography.labelMedium, color = t.meta)
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.Bottom) {
            // 首次进入 0 → 128 滚动（时长取 --dur-chart），换主题只换颜色、不重播
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
            // 占比用实心扇形图（原型 s13 .pv-ring data-chart="pie"），不叠中心文案。
            // 分片色与右图例**同源**：两侧都按 s1..s5 下标取 chartRamp；图例只列前 4 项，与原型一致。
            PieChart(
                segments = listOf(42f, 31f, 24f, 18f, 13f),
                modifier = Modifier.size(t.ringSize),
                colors = t.chartRamp,
                size = t.ringSize,
            )
            Spacer(Modifier.width(t.space4))
            Legend(
                // 原型图例 = 色点 + 名称 + 数值（分类色 s1..s4 = chart-1..4）
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

/** 说明卡（锚点 s13-note）：两句说明 + 当前主题的 token 取值徽标（bg / accent / fg）实时刷新。 */
@Composable
private fun AppearanceNoteCard(currentId: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
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
            // 徽标取当前主题 token：切主题时随外壳同帧刷新
            val preview = DesignTokens(currentId)
            Badge("bg " + hex(preview.bg), tone = BadgeTone.Neutral)
            Badge("accent " + hex(preview.accent), tone = BadgeTone.Accent)
            Badge("fg " + hex(preview.fg), tone = BadgeTone.Neutral)
        }
    }
}

/** 边界态（锚点 s13-st-edge）：两行逐字样例，不展开、不跳转；超长名两行截断、计数等宽右对齐。 */
@Composable
private fun AppearanceEdgeBlock(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier, padding = 0.dp) {
        EdgeSampleRow(
            title = "超长主题名：墨青 · Kami（含中日韩字形回退与等宽数字变体）",
            subtitle = "含 10,286 个 token 覆盖测试",
            trailing = "10,286",
        )
        TokenDivider(first = false)
        EdgeSampleRow(
            title = "边界态：主题缺少 --text-xl 槽位",
            subtitle = "标题回退到 --text-lg，数值 10,000 条",
            trailing = "10,000",
        )
        Spacer(Modifier.height(t.space1))
    }
}

/**
 * 边界态样例行：与共享 [TokenRow] 同构，但标题允许两行（原型 .row-title 的 wrap），
 * 尾部计数走等宽 + 右对齐。共享 TokenRow 的标题固定单行（写区外），故本页局部实现。
 */
@Composable
private fun EdgeSampleRow(title: String, subtitle: String, trailing: String) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = t.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space3))
        Text(
            trailing,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum",
            ),
            color = t.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/**
 * s13 退出后「焦点回设置页『外观与主题』行」的一次性信号（锚点 s13-head · 退出后）。
 *
 * 为什么需要跨文件信号：外观屏由宿主导航以**覆盖层**打开（MainOverlays / SettingsScreen 的
 * 兜底桥），设置页拿不到「外观屏已关闭」的回调；两个页面都在本批写区内，用最小的代次信号衔接，
 * 不动共享导航件。
 *
 * 用代次（Int）而不是布尔：设置页只在**自己组合期间**观察到新代次才回焦。进入设置页之前遗留的
 * 旧代次不抢占焦点（例如用户先在别处打开过外观屏，之后再进设置页）。
 */
internal object AppearanceFocusReturn {
    private var generationState by mutableIntStateOf(0)
    val generation: Int get() = generationState

    /** 外观屏退出时调用一次（按钮返回与系统返回 / 手势共用）。 */
    fun request() {
        generationState += 1
    }
}

/** 代次有变化才需要回焦；同一代次重复重组不抢焦点。 */
internal fun appearanceReturnFocusPending(currentGeneration: Int, seenGeneration: Int): Boolean =
    currentGeneration != seenGeneration
