package com.bsfeng.monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.bsfeng.monitor.finance.ui.restoreFocusAfterFrames
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
import kotlinx.coroutines.launch
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
 * 空态（规则总数 = 0）复用订阅源卡与计数卡：契约 s10-st-empty 要求 CTA 把焦点与视口带到
 * URL 输入框，输入框必须真实存在；空态卡接在两张卡下方，空态本身不关闭，锚点仍落 s10-st-empty。
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
    // s10 只有一个 toast 宿主（下方 AppToast 绑它）。页面级动作（开关 / 复制 / 新增 / 快照）
    // 直接 toast.show(...)；ViewModel 侧的一次性结果（订阅更新 / 拉取失败）经 LaunchedEffect
    // 搬进同一个宿主 —— 原实现把 AppToast 绑在 state.message 上，toast 状态永远没人渲染，
    // 契约要求的四句提示一句都出不来。
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

    // 五态的真实来源（契约 s10-st-empty · 前置「规则总数 = 0」）：demoState 仍可覆盖演示；
    // 规则库为空（含「有订阅源但规则为 0」）即空态，而不是渲染一张 0 行的有数据态。
    val effective = skipRuleEffectiveState(
        demoState = demoState,
        hasError = state.errorMessage != null,
        ruleCount = state.rules.size,
    )

    // ViewModel 的一次性结果（订阅拉取成功 / 失败、地址非法）搬进页面 toast 宿主：
    // 这些动作在 VM 里跑，屏幕层只能靠 state.message 收结果；把 AppToast 绑在 toast 上之后，
    // 必须在这里做一次转投，否则「订阅已更新 / 订阅更新失败」照样看不见。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        toast.show(message)
        viewModel.consumeMessage()
    }

    // 更新中的订阅地址（null = 空闲）：按钮禁用态与规则列表骨架都读它
    val updatingUrl = state.updatingUrl
    val updating = updatingUrl != null
    // 规则行展开态提升到屏幕层：LazyColumn 会回收滚出视口的 item，行内 remember 会随 item 一起
    // 丢掉（旧实现是整页 Column，不存在回收），提升后滚动回来仍是展开态。
    val expandedRuleIds = remember { mutableStateMapOf<String, Boolean>() }

    val scope = rememberCoroutineScope()
    // 空态与有数据态各用一份列表状态：空态 CTA 要把视口滚回顶部让订阅源卡可见（契约 s10-st-empty）。
    val emptyListState = rememberLazyListState()
    val bodyListState = rememberLazyListState()
    // 三处焦点归位落点（契约 s10-st-empty / s10-confirm / s10-st-error）
    val urlFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }
    val errorRetryFocus = remember { FocusRequester() }
    var pendingUrlFocus by remember { mutableStateOf(false) }
    var pendingUpdateFocus by remember { mutableStateOf(false) }
    var pendingErrorRetryFocus by remember { mutableStateOf(false) }

    // 空态 CTA：焦点与视口带到订阅源 URL 输入框；请求放在复位之前（见 restoreFocusAfterFrames）。
    LaunchedEffect(pendingUrlFocus) {
        if (!pendingUrlFocus) return@LaunchedEffect
        restoreFocusAfterFrames(
            frames = 2,
            awaitFrame = { withFrameNanos { } },
            requestFocus = { runCatching { urlFocus.requestFocus() }.isSuccess },
            reset = { pendingUrlFocus = false },
        )
    }

    // 确认浮层关闭后焦点回「更新订阅」（契约 s10-confirm · 取消 / 确认更新）。
    // 确认更新会让按钮转禁用（不可聚焦）→ 等更新结束再请求，不提前消费信号。
    LaunchedEffect(pendingUpdateFocus, showConfirm, updating, effective) {
        if (!pendingUpdateFocus) return@LaunchedEffect
        when (updateButtonFocusGate(showConfirm, updating, effective)) {
            UpdateFocusGate.WAIT -> Unit
            UpdateFocusGate.DROP -> pendingUpdateFocus = false
            UpdateFocusGate.REQUEST -> restoreFocusAfterFrames(
                frames = 2,
                awaitFrame = { withFrameNanos { } },
                requestFocus = { runCatching { updateFocus.requestFocus() }.isSuccess },
                reset = { pendingUpdateFocus = false },
            )
        }
    }

    // 错误态「重试」：仍失败 → 焦点回「重试」；恢复成有数据态 / 空态（两者都有订阅源卡）→ 回
    // 「更新订阅」；边界态没有触发按钮的落点 → 作废（契约 s10-st-error · 错误态被新结果替换后消失）。
    LaunchedEffect(pendingErrorRetryFocus, effective, updating) {
        if (!pendingErrorRetryFocus) return@LaunchedEffect
        when (val next = retryFocusTarget(effective, updating)) {
            RetryFocus.DROP -> pendingErrorRetryFocus = false
            RetryFocus.WAIT -> Unit
            RetryFocus.RETRY_BUTTON, RetryFocus.UPDATE_BUTTON -> {
                val target = if (next == RetryFocus.RETRY_BUTTON) errorRetryFocus else updateFocus
                restoreFocusAfterFrames(
                    frames = 2,
                    awaitFrame = { withFrameNanos { } },
                    requestFocus = { runCatching { target.requestFocus() }.isSuccess },
                    reset = { pendingErrorRetryFocus = false },
                )
            }
        }
    }

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

                        FiveState.ERROR -> ErrorState(
                            text = state.errorMessage
                                ?: ("镜像源返回 502，已保留本地 " + state.rules.size + " 条规则，未做覆盖。"),
                            title = "订阅源更新失败",
                            onRetry = {
                                pendingErrorRetryFocus = true
                                viewModel.clearError()
                                toast.show("正在重新拉取订阅源…")
                                viewModel.updateSubscription(urlInput)
                            },
                            // 重试后仍在错态 → 焦点回这颗「重试」按钮（契约 s10-st-error · 移除后）
                            actionModifier = Modifier.focusRequester(errorRetryFocus),
                            modifier = Modifier.testTag("s10-st-error"),
                        )

                        FiveState.EDGE -> EdgeState(
                            text = "边界态：超长选择器 / 规则缺字段",
                            modifier = Modifier.testTag("s10-st-edge"),
                        ) {
                            SkipRuleEdgeContent()
                        }

                        // 空态与有数据态共用同一张订阅源卡：契约 s10-st-empty 要求 CTA 把
                        // 「焦点与视口带到订阅源 URL 输入框」，URL 输入框必须真实存在；字段由本卡的
                        // urlFocusRequester 接入。空态卡接在订阅源卡下方，空态本身不关闭。
                        FiveState.POPULATED, FiveState.EMPTY -> {
                            val empty = effective == FiveState.EMPTY
                            LazyColumn(
                                state = if (empty) emptyListState else bodyListState,
                                // s10-body 是有数据态的内容列锚点：空态不渲染（契约 s10-body 边界）
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (empty) Modifier else Modifier.testTag("s10-body")),
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
                                        // 新增订阅走本地语义（判重 + 清单 +1），不发起网络拉取；
                                        // 需要拉规则时用户再点「更新订阅」（契约 s10-source · 新增订阅行）
                                        onAddSubscription = { viewModel.addSubscriptionLocally(urlInput) },
                                        onRequestSnapshot = { viewModel.requestSnapshot() },
                                        urlFocusRequester = urlFocus,
                                        updateFocusRequester = updateFocus,
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

                                if (empty) {
                                    // 规则总数为 0（含「有订阅源但规则为 0」）：同一文案，计数卡仍显示 0
                                    item(key = "empty") {
                                        EmptyState(
                                            text = "添加订阅源后点击「更新订阅」，规则会自动合并到本地规则库。",
                                            title = "还没有任何规则",
                                            actionLabel = "去添加订阅源",
                                            // 契约 s10-st-empty：只把焦点与视口带到 URL 输入框，不建规则、不关空态
                                            onAction = {
                                                toast.show("请在订阅源卡片填写 URL")
                                                pendingUrlFocus = true
                                                scope.launch { emptyListState.animateScrollToItem(0) }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = t.space3)
                                                .testTag("s10-st-empty"),
                                        )
                                    }
                                } else {
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
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
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
                // 浮层关闭后焦点回「更新订阅」；确认更新会让按钮转禁用，等更新结束再落点
                pendingUpdateFocus = true
                // 确认类操作给 SUCCESS 触感（契约 s10-confirm「确认更新」行）；取消不给触感。
                haptic(HapticTone.SUCCESS)
                viewModel.updateSubscription(urlInput)
            },
            onDismiss = {
                showConfirm = false
                pendingUpdateFocus = true
            },
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

/**
 * s10 五态的真实来源（契约 s10-st-empty · 前置「规则总数 = 0」）。
 *
 * demoState 仍可覆盖演示（切到 loading / error / edge）；真实数据里只有「规则库为空」会进空态 ——
 * 契约边界写明「有订阅源但规则为 0 时用同一文案」，故判据只看规则数、不看订阅源数。
 */
internal fun skipRuleEffectiveState(
    demoState: FiveState,
    hasError: Boolean,
    ruleCount: Int,
): FiveState = when {
    demoState != FiveState.POPULATED -> demoState
    hasError -> FiveState.ERROR
    ruleCount == 0 -> FiveState.EMPTY
    else -> FiveState.POPULATED
}

/** 「更新订阅」按钮的一次性回位闸（s10-confirm / s10-st-error）。 */
internal enum class UpdateFocusGate { WAIT, REQUEST, DROP }

/**
 * 更新订阅按钮回位闸：浮层未关 / 按钮因更新中禁用时等待；错态与边界态没有订阅源卡、信号作废；
 * 空态与有数据态都渲染订阅源卡，按钮在，可以回焦。
 */
internal fun updateButtonFocusGate(
    dialogOpen: Boolean,
    updating: Boolean,
    state: FiveState,
): UpdateFocusGate = when {
    dialogOpen -> UpdateFocusGate.WAIT
    state == FiveState.ERROR || state == FiveState.EDGE -> UpdateFocusGate.DROP
    state == FiveState.LOADING -> UpdateFocusGate.WAIT
    updating -> UpdateFocusGate.WAIT
    else -> UpdateFocusGate.REQUEST
}

/** 错误态「重试」后的一次性回位目标（s10-st-error）。 */
internal enum class RetryFocus { WAIT, RETRY_BUTTON, UPDATE_BUTTON, DROP }

/**
 * 重试后的落点：仍失败 → 「重试」按钮；恢复成有数据态或空态（两者都渲染订阅源卡）→ 「更新订阅」；
 * 扫描 / 更新中等待；边界态没有触发按钮的落点 → 作废。
 */
internal fun retryFocusTarget(state: FiveState, updating: Boolean): RetryFocus = when {
    state == FiveState.ERROR -> RetryFocus.RETRY_BUTTON
    state == FiveState.LOADING -> RetryFocus.WAIT
    state == FiveState.POPULATED || state == FiveState.EMPTY ->
        if (updating) RetryFocus.WAIT else RetryFocus.UPDATE_BUTTON
    else -> RetryFocus.DROP
}

/** 契约 s10-url：URL 输入上限 2048 字符（超长截断，不报错）。 */
private const val URL_MAX_CHARS = 2048

private const val DEFAULT_SUBSCRIPTION_URL =
    "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"
