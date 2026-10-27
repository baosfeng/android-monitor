package com.bsfeng.monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * s10 订阅源与规则（原型 .open-design/src/screens/s10-开屏跳过规则.html）。
 *
 * 锚点（14 个，逐字取自原型 data-od-id）：
 * screen-s10 / s10-head / s10-body / s10-source / s10-url / s10-summary / s10-rules /
 * s10-legend / s10-st-loading / s10-st-empty / s10-st-error / s10-st-edge / s10-confirm。
 *
 * 空 / 错 / 边界态直接调共享 [EmptyState] / [ErrorState] / [EdgeState]（StateMessages.kt），
 * 不用 StateContainer：后者不接受块级锚点，本页每个态都要带契约锚点。
 *
 * 「s10-all 查看全部规则」行不渲染：源码用 LazyColumn 全量渲染规则，没有契约假设的
 * 「前 N 条」折叠，契约边界「总数 ≤ 已渲染条数时整行隐藏」恒成立（见交接文档处置表）。
 */
@Composable
fun SkipRuleScreen(
    onBack: () -> Unit,
    viewModel: SkipRuleViewModel = viewModel(),
) {
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
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

    Box(
        Modifier
            .fillMaxSize()
            .pageBackdrop()
            .testTag("screen-s10")
            // Esc：先收起全部已展开明细，再按一次才交给页面返回（契约 s10-rules 键盘行边界）。
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Escape && expandedRuleIds.isNotEmpty()) {
                    expandedRuleIds.clear()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "订阅源与规则",
                meta = "从镜像源拉取规则包，本地合并后按顺序匹配",
                onBack = onBack,
                modifier = Modifier.testTag("s10-head"),
                consumeTopInset = true,
            )
            Column(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
                // 五态切换器（演示用）留在 content **之外**：状态分支不渲染 content，
                // 放进去就再没有切回有数据态的入口（release 构建 rememberDemoVisible() 为 false，不渲染）。
                StateSwitchRow(
                    state = demoState,
                    onSelect = { demoState = it },
                    modifier = Modifier.padding(bottom = t.space4),
                )

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (effective) {
                        FiveState.LOADING -> SkipRuleStateSkeleton(Modifier.testTag("s10-st-loading"))

                        FiveState.EMPTY -> EmptyState(
                            text = "添加订阅源后点击「更新订阅」，规则会自动合并到本地规则库。",
                            title = "还没有任何规则",
                            actionLabel = "去添加订阅源",
                            onAction = { toast.show("请在订阅源卡片填写 URL") },
                            modifier = Modifier.testTag("s10-st-empty"),
                        )

                        FiveState.ERROR -> ErrorState(
                            text = state.errorMessage
                                ?: ("镜像源返回 502，已保留本地 " + state.rules.size + " 条规则，未做覆盖。"),
                            title = "订阅源更新失败",
                            onRetry = {
                                viewModel.clearError()
                                toast.show("正在重新拉取订阅源…")
                                viewModel.updateSubscription(urlInput)
                            },
                            modifier = Modifier.testTag("s10-st-error"),
                        )

                        FiveState.EDGE -> EdgeState(
                            text = "边界态：超长选择器 / 规则缺字段",
                            modifier = Modifier.testTag("s10-st-edge"),
                        ) {
                            SkipRuleEdgeContent()
                        }

                        FiveState.POPULATED -> LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("s10-body"),
                            contentPadding = PaddingValues(bottom = t.space6),
                        ) {
                            // ── 订阅源卡 ──
                            item(key = "source") {
                                SkipRuleSourceCard(
                                    urlInput = urlInput,
                                    onUrlChange = { urlInput = it.take(URL_MAX_CHARS) },
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
                                    modifier = Modifier
                                        .padding(start = t.space4, end = t.space4, bottom = t.space4)
                                        .testTag("s10-summary"),
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
                            // 容器锚点落在小节头：列表本体是 LazyColumn 的 items，没有可包住
                            // item 子集的容器节点（与 s03-records 同一处置）。
                            item(key = "rules-head") {
                                SecHead(
                                    "规则列表",
                                    Modifier.testTag("s10-rules").padding(bottom = t.space2),
                                    right = "点击行展开选择器明细",
                                )
                            }

                            // 规则行 = 一卡一行（不是一卡一规则卡）：StackRow 首行圆上角 / 末行圆下角、
                            // 行间 0 间距，视觉上仍是改动前那一张连续卡片。
                            skipRuleItems(
                                rules = state.rules,
                                expandedRuleIds = expandedRuleIds,
                                onToggleExpand = { rule ->
                                    expandedRuleIds[rule.id] = expandedRuleIds[rule.id] != true
                                },
                                onToggleEnabled = { rule ->
                                    viewModel.toggleRule(rule.id)
                                    toast.show((if (rule.enabled) "已停用 " else "已启用 ") + rule.name + " 跳过规则")
                                },
                                onCopySelector = { selector ->
                                    clipboard.setText(AnnotatedString(selector))
                                    toast.show("已复制选择器")
                                },
                            )

                            // ── 选择器种类图例 ──
                            item(key = "legend") {
                                SkipRuleLegend(
                                    rules = state.rules,
                                    modifier = Modifier
                                        .padding(start = t.space4, end = t.space4, top = t.space4)
                                        .testTag("s10-legend"),
                                )
                            }
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

        // 更新确认：统一走 ConfirmDialog（窗口级浮层基元 WindowOverlay），不再自绘遮罩 + 卡片。
        // 锚点 s10-confirm 落在浮层根（原型 .scrim 上同位置）。
        ConfirmDialog(
            visible = showConfirm,
            title = "更新订阅源？",
            message = "将从 " + hostOf(urlInput) + " 拉取最新规则包，与本地 " + state.rules.size +
                " 条规则合并；同 id 规则以订阅源为准。",
            confirmLabel = "确认更新",
            dismissLabel = "取消",
            modifier = Modifier.testTag("s10-confirm"),
            onConfirm = {
                showConfirm = false
                // 确认类操作给 SUCCESS 触感（契约 s10-confirm「确认更新」行）；取消不给触感。
                haptic(HapticTone.SUCCESS)
                viewModel.updateSubscription(urlInput)
            },
            onDismiss = { showConfirm = false },
        )
    }
}

/** s10 加载态：6 条骨架行（原型 .st-loading 的 6 个 .sk），不接收点击与焦点。 */
@Composable
private fun SkipRuleStateSkeleton(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier.padding(horizontal = t.space4)) {
        StateSkeleton(rows = 6)
    }
}

/** 契约 s10-url：URL 输入上限 2048 字符（超长截断，不报错）。 */
private const val URL_MAX_CHARS = 2048

private const val DEFAULT_SUBSCRIPTION_URL =
    "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"
