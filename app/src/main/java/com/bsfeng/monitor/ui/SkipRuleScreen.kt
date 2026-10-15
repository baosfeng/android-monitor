package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.sparkHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * s10 订阅源与规则（原型 s10-skip-rules.html）。
 *
 * 订阅源卡（URL + 更新 / 新增 / 快照）· 计数滚动（规则总数 / 已启用）· 规则行可折叠展开选择器明细 ·
 * 五态（loading / empty / error / populated / edge）。
 * 旧页面的 MaterialTheme 直取色已按 LEGACY-TOKEN-MAP 换成本地 token 快照。
 */
@Composable
fun SkipRuleScreen(
    onBack: () -> Unit,
    viewModel: SkipRuleViewModel = viewModel(),
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val toast = rememberToastHostState()
    val state by viewModel.state.collectAsState()
    // s10 摘要「累计命中」：数据源是 records 表的 AD_SKIPPED 事件（与 s03 跳过记录页同源，
    // 由 RecordCollector.onSkipAd 写入）。规则库里只有规则定义、没有命中计数器，故按事件表统计。
    val recordStore = remember { MonitorApp.instance.container.recordStore }
    var hits by remember { mutableStateOf(SkipHits.EMPTY) }
    LaunchedEffect(Unit) {
        hits = withContext(Dispatchers.IO) { loadSkipHits(recordStore, System.currentTimeMillis()) }
    }
    var demoState by remember { mutableStateOf(FiveState.POPULATED) }
    var showConfirm by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(DEFAULT_SUBSCRIPTION_URL) }

    // 已有订阅源时以第一个源为准展示在 URL 输入框
    LaunchedEffect(state.subscriptions) {
        val first = state.subscriptions.firstOrNull()?.url
        if (first != null && urlInput == DEFAULT_SUBSCRIPTION_URL) urlInput = first
    }

    val effective = if (demoState == FiveState.POPULATED && state.errorMessage != null) {
        FiveState.ERROR
    } else {
        demoState
    }

    // 更新中的订阅地址（null = 空闲）：按钮禁用态与规则列表骨架都读它
    val updatingUrl = state.updatingUrl
    val updating = updatingUrl != null
    // 规则行展开态提升到屏幕层：LazyColumn 会回收滚出视口的 item，行内 remember 会随 item 一起
    // 丢掉（旧实现是整页 Column，不存在回收），提升后滚动回来仍是展开态。
    val expandedRuleIds = remember { mutableStateMapOf<String, Boolean>() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "订阅源与规则",
                meta = "从镜像源拉取规则包，本地合并后按顺序匹配",
                onBack = onBack,
            )
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // 五态切换器（演示用）留在 content **之外**：StateContainer 的 loading / empty /
                // error / edge 分支不渲染 content（States.kt:91-106），放进去就再没有切回有数据态的入口。
                // 与 StateContainer 之间的 space4 间距挂在自身 modifier 上：未解锁演示时它不 emit
                // 任何节点，间距随之消失 —— 与改动前外层 Arrangement.spacedBy 的行为一致。
                StateSwitchRow(
                    state = demoState,
                    onSelect = { demoState = it },
                    modifier = Modifier.padding(bottom = t.space4),
                )

                // 五态统一走 StateContainer（ui/components/States.kt）；文案取自原型 s10-st-*。
                StateContainer(
                    state = effective,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    emptyTitle = "还没有任何规则",
                    emptyText = "添加订阅源后点击「更新订阅」，规则会自动合并到本地规则库。",
                    emptyActionLabel = "去添加订阅源",
                    onEmptyAction = { toast.show("请在订阅源卡片填写 URL") },
                    errorTitle = "订阅源更新失败",
                    errorText = state.errorMessage ?: "镜像源返回 502，已保留本地 " + state.rules.size + " 条规则，未做覆盖。",
                    onRetry = {
                        viewModel.clearError()
                        toast.show("正在重新拉取订阅源…")
                        viewModel.updateSubscription(urlInput)
                    },
                    edgeText = "边界态：超长选择器 / 规则缺字段",
                    edgeContent = {
                        TokenRow(
                            title = "某应用名称长到超出一行仍然需要完整展示的极端情况",
                            subtitle = "[text=\"跳过广告并关闭\"][id=\"com.example.app.long.package.name:id/skip_button_container\"]",
                            trailing = { TailNum("10,286 条") },
                        )
                        TokenDivider(first = false)
                        TokenRow(
                            title = "边界态：规则缺字段",
                            subtitle = "该规则未提供选择器与优先级，命中次数缺失",
                            trailing = { TailNum("—") },
                        )
                    },
                ) {
                    // StateContainer 的 POPULATED 分支把 content 放进 Box（States.kt:90）：
                    // Box 下多个兄弟节点会叠在同一原点（「订阅源卡 / 计数 / 规则列表 / 图例互压」的根因），
                    // 因此 content lambda 顶层只留 LazyColumn 这一个子节点。
                    //
                    // 虚拟化（本次修复的 P0）：原实现是「外层 verticalScroll(Column) + 规则列表
                    // forEachIndexed」，620 条 GKD 大规则包会在同一帧全部走组合 / 测量 / 布局 —— RuleRow
                    // 每条都含应用图标 + 徽标 + 开关 + 箭头，主线程被一次性堵死，界面表现就是点完卡死。
                    // LazyColumn 只为可视区组合 item，组合量从「规则总数」降到「可视条数」（十余条）。
                    // 结构对齐既有模式（OverviewScreen.kt:130 / AdScreen.kt:153 / MainScreen.kt:179）：
                    // 单 LazyColumn + 卡片级 item + contentPadding 处理底部留白；左右 16dp 仍由各卡片
                    // 自带 padding 提供（SecHead / GlassCard / Legend 等组件内部已含 horizontal space4）。
                    // 组间距不交给 Arrangement.spacedBy：相邻规则行必须 0 间距，StackRow 才能连成一张卡
                    // （与 MainScreen.kt:199 的时间线同一手法）。
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = t.space6),
                    ) {
                        // ── 订阅源卡 ──
                        item(key = "source") {
                            Box(Modifier.padding(start = t.space4, end = t.space4, bottom = t.space4)) {
                                GlassCard {
                                    GroupLabel("订阅源 URL", Modifier.padding(start = 0.dp))
                                    AppField(
                                        value = urlInput,
                                        onValueChange = { urlInput = it },
                                        warm = true,
                                        // URL 输入必须用 Uri 键盘：默认文本键盘会被中文输入法接管（见踩坑记录）
                                        keyboardType = KeyboardType.Uri,
                                    )
                                    Spacer(Modifier.height(t.space3))
                                    Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                                        AppButton(
                                            // 原型 s10 没有单独的「更新中」按钮态（08-prototype.html:4574-4586
                                            // 的 .src-card 内既无 loading 也无 disabled class），因此按原型既有
                                            // 规则组态：.btn[disabled]{opacity:.45;cursor:default}（同文件 1307 行）
                                            // → AppButton(enabled = false)（ComponentGalleryScreen.kt:121-152）。
                                            // 更新中按钮转禁用 + 文案「更新中…」，用户不可能重复点。
                                            text = if (updating) "更新中…" else "更新订阅",
                                            onClick = { showConfirm = true },
                                            modifier = Modifier.weight(1f),
                                            enabled = !updating,
                                        )
                                        AppButton("新增订阅", onClick = {
                                            viewModel.updateSubscription(urlInput)
                                        }, modifier = Modifier.weight(1f), variant = AppButtonVariant.Ghost, enabled = !updating)
                                        AppButton("页面快照", onClick = {
                                            viewModel.requestSnapshot()
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        }, modifier = Modifier.weight(1f), variant = AppButtonVariant.Ghost)
                                    }
                                    Spacer(Modifier.height(t.space3))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "上次同步 " + formatSyncTime(state.lastSyncAt) + " · 镜像 " + hostOf(urlInput),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = t.muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(t.space2))
                                        Badge(state.subscriptions.size.toString() + " 个源", BadgeTone.Accent)
                                    }
                                    if (updating) {
                                        Spacer(Modifier.height(t.space2))
                                        Text(
                                            "正在更新：" + hostOf(updatingUrl) + " · 大规则包需等待拉取与合并",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = t.accent,
                                        )
                                    }
                                    state.snapshotPath?.let { path ->
                                        Spacer(Modifier.height(t.space2))
                                        Text("快照：" + path, style = MaterialTheme.typography.labelSmall, color = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        // ── 计数（滚动动画）──
                        item(key = "kpi") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = t.space4, end = t.space4, bottom = t.space4),
                                horizontalArrangement = Arrangement.spacedBy(t.space2),
                            ) {
                                AppCard(Modifier.weight(1f)) {
                                    Text("规则总数", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                    Spacer(Modifier.height(t.space1))
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        AnimatedNumber(state.rules.size, style = MaterialTheme.typography.headlineMedium, color = t.fg)
                                        Spacer(Modifier.width(t.space1))
                                        Text("条", style = MaterialTheme.typography.labelMedium, color = t.muted)
                                    }
                                    Spacer(Modifier.height(t.space1))
                                    Text("较上周 +2", style = MaterialTheme.typography.labelMedium, color = t.successText)
                                }
                                // 原型 s10 摘要第二格是「累计命中 + 今日 N 次 + 近 7 天迷你走势」
                                // （08-prototype.html:4594-4599 的 .kpi-row），不再是「已启用」。
                                AppCard(Modifier.weight(1f)) {
                                    Text("累计命中", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                    Spacer(Modifier.height(t.space1))
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        AnimatedNumber(hits.total, style = MaterialTheme.typography.headlineMedium, color = t.fg)
                                        Spacer(Modifier.width(t.space1))
                                        Text("次", style = MaterialTheme.typography.labelMedium, color = t.muted)
                                    }
                                    Spacer(Modifier.height(t.space1))
                                    Text(
                                        "今日 " + hits.today + " 次",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = t.muted,
                                    )
                                    Spacer(Modifier.height(t.space2))
                                    Sparkline(hits.daily7, Modifier.fillMaxWidth().height(t.sparkHeight))
                                }
                            }
                        }

                        // 更新中的加载指示（P1）：原型 s10 的加载态就是一叠 .sk 骨架行
                        // （08-prototype.html:4695-4704 的 .st-loading），Compose 侧对应 StateSkeleton，
                        // 用法与既有调用点一致（ComponentGalleryScreen.kt:475 的
                        // FiveStateSlot：AppCard { StateSkeleton(rows = 4) }）。
                        if (updating) {
                            item(key = "rules-loading") {
                                AppCard(Modifier.padding(start = t.space4, end = t.space4, bottom = t.space4)) {
                                    Text(
                                        "正在拉取订阅源并合并规则…（下方仍为本地现有规则）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.muted,
                                    )
                                    Spacer(Modifier.height(t.space3))
                                    StateSkeleton(rows = 3)
                                }
                            }
                        }

                        // ── 规则列表 ──
                        item(key = "rules-head") {
                            SecHead("规则列表", "点击行展开选择器明细", Modifier.padding(bottom = t.space2))
                        }

                        // 规则行 = 一卡一行（不是一卡一规则卡）：StackRow 首行圆上角 / 末行圆下角、
                        // 行间 0 间距，视觉上仍是改动前那一张连续卡片（相邻边框相接即原 TokenDivider）。
                        itemsIndexed(
                            items = state.rules,
                            // 用 itemsIndexed 而非 items 是为了拿到 index：首尾圆角与错落入场都依赖它；
                            // key 仍以 rule.id 为主体。**但 id 不能单独作 key**：rule.id 只在单个来源内
                            // 唯一（GKD 订阅的 apps[] 用包名、globalGroups[] 用 gkd_global_N、内置规则用
                            // 自定义短名，多订阅源/自定义规则之间可以撞），而 getAllRules() 只是
                            // customRules + subscriptionRules + builtInRules 的拼接、不去重 —— key 撞车会
                            // 直接抛 "Key was already used"。故与 AdScreen.kt:269 同一兜底：id + 下标。
                            key = { index, rule -> rule.id + "#" + index },
                        ) { index, rule ->
                            // 入场动画只给首屏可见项：staggerEnter 会给每行挂一个 graphicsLayer，
                            // 长列表滚动时合成成本被放大（AdScreen.kt:271-278 同一处理）。
                            val enterMod = if (index < STAGGER_MAX_STEPS) rememberStaggerEnter(index) else Modifier
                            StackRow(
                                first = index == 0,
                                last = index == state.rules.lastIndex,
                                modifier = Modifier.padding(horizontal = t.space4).then(enterMod),
                            ) {
                                RuleRow(
                                    rule = rule,
                                    expanded = expandedRuleIds[rule.id] == true,
                                    onToggleExpand = {
                                        expandedRuleIds[rule.id] = expandedRuleIds[rule.id] != true
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    },
                                    onToggleEnabled = {
                                        viewModel.toggleRule(rule.id)
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        toast.show((if (rule.enabled) "已停用 " else "已启用 ") + rule.name + " 跳过规则")
                                    },
                                )
                            }
                        }

                        // ── 选择器种类图例 ──
                        item(key = "legend") {
                            // 图例计数缓存进 remember(state.rules)：旧实现每次重组都对全量规则做 4 轮
                            // 嵌套求和，620 条规则下是纯主线程开销（与 AdScreen.kt:122-129 派生值缓存同一手法）。
                            val legendItems = remember(state.rules) {
                                listOf(
                                    LegendItem("全部匹配 matches", selectorKindCount(state.rules) { it.matches.size }.toString(), t.chartRamp[0]),
                                    LegendItem("任选其一 anyMatches", selectorKindCount(state.rules) { it.anyMatches.size }.toString(), t.chartRamp[1]),
                                    LegendItem("排除 excludeMatches", selectorKindCount(state.rules) { it.excludeMatches.size }.toString(), t.chartRamp[2]),
                                    LegendItem("规则组 groups", state.rules.sumOf { it.groups.size }.toString(), t.chartRamp[3]),
                                )
                            }
                            Legend(items = legendItems, modifier = Modifier.padding(start = t.space4, end = t.space4, top = t.space4))
                        }
                    }
                }
            }
        }

        AppToast(
            message = state.message,
            onDismiss = { viewModel.consumeMessage() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )

        if (showConfirm) {
            Box(Modifier.fillMaxSize()) {
                OverlayScrim { showConfirm = false }
                AppCard(Modifier.align(Alignment.Center).padding(horizontal = t.space6)) {
                    Text("更新订阅源？", style = MaterialTheme.typography.titleMedium, color = t.fg)
                    Spacer(Modifier.height(t.space2))
                    Text(
                        "将从 " + hostOf(urlInput) + " 拉取最新规则包，与本地 " + state.rules.size + " 条规则合并；同 id 规则以订阅源为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    Spacer(Modifier.height(t.space4))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                        AppButton("取消", { showConfirm = false }, Modifier.weight(1f), AppButtonVariant.Ghost)
                        AppButton("确认更新", {
                            showConfirm = false
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            viewModel.updateSubscription(urlInput)
                        }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private const val DEFAULT_SUBSCRIPTION_URL =
    "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"

/** 原型 s10 规则行专用图标尺寸（.screen.s10 .rule-main.has-icon .app-icon = 25px）。 */
private val RULE_ICON_SIZE = 25.dp

/** 跳过事件的动作名（与 RecordCollector.onSkipAd 写入的 action 一致）。 */
private const val AD_SKIPPED_ACTION = "AD_SKIPPED"

/** 「累计命中」迷你走势的天数（与 s03 跳过趋势同窗口）。 */
private const val SKIP_TREND_DAYS = 7

/**
 * s10 摘要「累计命中」的三个数字（原型 08-prototype.html:4594-4599 的第二个 .kpi）。
 *
 * @param total 全量命中次数（kpi-value）
 * @param today 今日命中次数（kpi-delta）
 * @param daily7 近 7 天逐日命中分桶（kpi-spark 迷你走势）
 */
internal data class SkipHits(val total: Int, val today: Int, val daily7: List<Float>) {
    companion object {
        val EMPTY = SkipHits(total = 0, today = 0, daily7 = List(SKIP_TREND_DAYS) { 0f })
    }
}

/**
 * 从 records 表统计跳过命中（与 s03 跳过记录页同一数据源：SCREEN / AD_SKIPPED 事件）。
 *
 * 规则库里只有规则定义、没有命中计数器，真实命中记录只存在于跳过事件表，
 * 因此这里对事件表计数，而不是拿规则条数冒充命中数。调用方需在 IO 线程执行。
 */
private fun loadSkipHits(store: RecordStore, now: Long): SkipHits {
    val todayStart = startOfDay(now)
    val weekStart = todayStart - (SKIP_TREND_DAYS - 1) * DAY_MS
    val events = store.queryByTypeAndAction(RecordType.SCREEN, AD_SKIPPED_ACTION, 0L, now)
    var today = 0
    val buckets = FloatArray(SKIP_TREND_DAYS)
    events.forEach { record ->
        if (record.timestamp >= todayStart) today++
        val idx = ((record.timestamp - weekStart) / DAY_MS).toInt()
        if (idx in 0 until SKIP_TREND_DAYS) buckets[idx] += 1f
    }
    return SkipHits(total = events.size, today = today, daily7 = buckets.toList())
}

private fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore("/").ifEmpty { "未填写订阅源" }

/** 静态复用（线程安全）；原实现每次调用都 new SimpleDateFormat（J8 热点）。 */
private val SYNC_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatSyncTime(atMs: Long): String =
    if (atMs <= 0L) "尚未同步"
    else Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(SYNC_TIME_FORMATTER)

/** 单条规则的选择器总数（matches + anyMatches + excludeMatches）。 */
private fun selectorCountOf(rule: SkipRule): Int =
    rule.groups.sumOf { group -> group.rules.sumOf { it.matches.size + it.anyMatches.size + it.excludeMatches.size } }

private fun selectorKindCount(rules: List<SkipRule>, pick: (MatchRule) -> Int): Int =
    rules.sumOf { rule -> rule.groups.sumOf { group -> group.rules.sumOf { pick(it) } } }

/**
 * 规则行：标题 / 计数 + 启用徽标 + 可折叠的选择器明细。
 *
 * 展开态 [expanded] 与入场 modifier 都由调用方（LazyColumn 的 item）注入：item 滚出视口会被
 * 回收，行内 remember 保不住展开状态（见 SkipRuleScreen 的 expandedRuleIds）。
 */
@Composable
private fun RuleRow(
    rule: SkipRule,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "ruleChevron",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onToggleExpand()
                }
                .padding(vertical = t.space2)
                .heightIn(min = t.rowMinH - t.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 原型 s10 规则行以应用为主体：行首是该应用的图标（08-prototype.html:4610 的
            // .rule-main.has-icon > .app-icon，25px/间距 5px）；取不到真实图标时 AppIcon 走
            // 「品牌色底 + 首字」兜底，全局规则（app = "*"）没有包名，直接兜底。
            AppIcon(
                packageName = rule.app.takeIf { !rule.isGlobal },
                appName = rule.name.ifEmpty { rule.app },
                size = RULE_ICON_SIZE,
            )
            Spacer(Modifier.width(t.space1))
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name.ifEmpty { rule.app },
                    style = MaterialTheme.typography.bodyLarge,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selectorCountOf(rule).toString() + " 个选择器 · " + rule.groups.size + " 组",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(t.space2))
            Badge(if (rule.enabled) "启用" else "停用", if (rule.enabled) BadgeTone.Success else BadgeTone.Neutral)
            Spacer(Modifier.width(t.space2))
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = t.muted,
                modifier = Modifier.width(t.space5).rotate(chevron),
            )
            Spacer(Modifier.width(t.space2))
            AppSwitch(rule.enabled, { onToggleEnabled() })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(t.durOverlayIn, easing = t.easeOut)) + fadeIn(animationSpec = tween(t.durOverlayIn)),
            exit = shrinkVertically(animationSpec = tween(t.durOverlayOut, easing = t.easeIn)) + fadeOut(animationSpec = tween(t.durOverlayOut)),
        ) {
            RuleDetail(rule)
        }
        if (!expanded) Spacer(Modifier.height(t.space1))
    }
}

/** 规则明细：每个规则组的选择器逐条列出（排查「跳不过 / 误触」的唯一入口）。 */
@Composable
private fun RuleDetail(rule: SkipRule) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(bottom = t.space3)) {
        rule.groups.sortedBy { it.order }.forEach { group ->
            Text(
                group.name.ifEmpty { "规则组" } + if (group.enabled) "" else "（已停用）",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
            )
            Text(
                groupScopeText(group),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space1))
            group.rules.forEach { matchRule ->
                Text(
                    matchRuleText(matchRule),
                    style = MaterialTheme.typography.labelMedium,
                    color = t.fg2,
                    modifier = Modifier.padding(top = t.space1),
                )
                selectorLines(matchRule).forEachIndexed { i, line ->
                    Row(Modifier.fillMaxWidth().padding(top = t.space1)) {
                        Text(
                            (i + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.meta,
                            modifier = Modifier.width(t.space6),
                        )
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = t.muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
        }
    }
}

/** 规则组作用域与闸门摘要 */
internal fun groupScopeText(group: RuleGroup): String {
    val window = if (group.matchTime > 0) "matchTime=" + (group.matchTime / 1000) + "s" else "matchTime=不限"
    val scope = if (group.activityIds.isEmpty()) "界面=全部" else "界面=" + group.activityIds.joinToString("|")
    val excluded = if (group.excludeActivityIds.isEmpty()) "" else " · 排除界面=" + group.excludeActivityIds.joinToString("|")
    return window + " · actionMaximum=" + group.actionMaximum + " · actionCd=" + group.actionCd + "ms" +
        " · reset=" + group.resetMatch.value + " · " + scope + excluded
}

/** 单条匹配规则的摘要（动作 + 非常规闸门） */
internal fun matchRuleText(matchRule: MatchRule): String {
    val guards = mutableListOf<String>()
    if (matchRule.matchDelay > 0) guards.add("matchDelay=" + matchRule.matchDelay + "ms")
    if (matchRule.actionDelay > 0) guards.add("actionDelay=" + matchRule.actionDelay + "ms")
    matchRule.matchTime?.let { guards.add("matchTime=" + (it / 1000) + "s") }
    matchRule.actionMaximum?.let { guards.add("actionMaximum=" + it) }
    matchRule.actionCd?.let { guards.add("actionCd=" + it + "ms") }
    matchRule.actionMaximumKey?.let { guards.add("actionMaximumKey=" + it) }
    matchRule.actionCdKey?.let { guards.add("actionCdKey=" + it) }
    if (matchRule.preKeys.isNotEmpty()) guards.add("preKeys=" + matchRule.preKeys.joinToString(","))
    if (!matchRule.fastQuery) guards.add("fastQuery=false")
    val name = "#" + matchRule.key + " " + matchRule.name.ifEmpty { "未命名" } + " · " + matchRule.action.value
    return if (guards.isEmpty()) name else name + "（" + guards.joinToString(" · ") + "）"
}

/** 选择器明细：matches 全命中才动作，anyMatches 任一命中即动作，excludeMatches 命中则放弃 */
internal fun selectorLines(matchRule: MatchRule): List<String> {
    val lines = mutableListOf<String>()
    matchRule.matches.forEach { lines.add("matches: " + it) }
    matchRule.anyMatches.forEach { lines.add("any: " + it) }
    matchRule.excludeMatches.forEach { lines.add("exclude: " + it) }
    if (lines.isEmpty()) lines.add("（无选择器）")
    return lines
}
