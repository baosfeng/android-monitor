package com.bsfeng.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.staggerEnterAt
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
import com.bsfeng.monitor.ui.foundation.expandEnter
import com.bsfeng.monitor.ui.foundation.expandExit
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import androidx.compose.foundation.layout.navigationBarsPadding

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

    Box(Modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "订阅源与规则",
                meta = "从镜像源拉取规则包，本地合并后按顺序匹配",
                onBack = onBack,
                consumeTopInset = true,
            )
            Column(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
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
                    edgeContent = { SkipRuleEdgeContent() },
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
                            SkipRuleSourceCard(
                                urlInput = urlInput,
                                onUrlChange = { urlInput = it },
                                updating = updating,
                                updatingUrl = updatingUrl,
                                subscriptionCount = state.subscriptions.size,
                                lastSyncAt = state.lastSyncAt,
                                snapshotPath = state.snapshotPath,
                                onRequestUpdate = { showConfirm = true },
                                onAddSubscription = { viewModel.updateSubscription(urlInput) },
                                onRequestSnapshot = { viewModel.requestSnapshot() },
                            )
                        }

                        item(key = "kpi") {
                            SkipRuleKpiRow(
                                ruleCount = state.rules.size,
                                hits = hits,
                                modifier = Modifier.padding(
                                    start = t.space4,
                                    end = t.space4,
                                    bottom = t.space4,
                                ),
                            )
                        }

                        if (updating) {
                            item(key = "rules-loading") {
                                SkipRuleUpdatingCard(
                                    modifier = Modifier.padding(
                                        start = t.space4,
                                        end = t.space4,
                                        bottom = t.space4,
                                    ),
                                )
                            }
                        }

                        // ── 规则列表 ──
                        item(key = "rules-head") {
                            SecHead("规则列表", Modifier.padding(bottom = t.space2), right = "点击行展开选择器明细")
                        }

                        // 规则行 = 一卡一行（不是一卡一规则卡）：StackRow 首行圆上角 / 末行圆下角、
                        // 行间 0 间距，视觉上仍是改动前那一张连续卡片（相邻边框相接即原 TokenDivider）。
                        skipRuleItems(
                            rules = state.rules,
                            expandedRuleIds = expandedRuleIds,
                            onToggleExpand = { rule ->
                                expandedRuleIds[rule.id] = expandedRuleIds[rule.id] != true
                                view.performConfirmHaptic()
                            },
                            onToggleEnabled = { rule ->
                                viewModel.toggleRule(rule.id)
                                view.performConfirmHaptic()
                                toast.show((if (rule.enabled) "已停用 " else "已启用 ") + rule.name + " 跳过规则")
                            },
                        )

                        // ── 选择器种类图例 ──
                        item(key = "legend") {
                            SkipRuleLegend(
                                rules = state.rules,
                                modifier = Modifier.padding(start = t.space4, end = t.space4, top = t.space4),
                            )
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

        // 更新确认：统一走 ConfirmDialog（窗口级浮层基元 WindowOverlay），不再自绘遮罩 + 卡片
        ConfirmDialog(
            visible = showConfirm,
            title = "更新订阅源？",
            message = "将从 " + hostOf(urlInput) + " 拉取最新规则包，与本地 " + state.rules.size +
                " 条规则合并；同 id 规则以订阅源为准。",
            confirmLabel = "确认更新",
            dismissLabel = "取消",
            onConfirm = {
                showConfirm = false
                view.performConfirmHaptic()
                viewModel.updateSubscription(urlInput)
            },
            onDismiss = { showConfirm = false },
        )
    }
}

private const val DEFAULT_SUBSCRIPTION_URL =
    "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"

