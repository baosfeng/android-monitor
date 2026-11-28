package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.instanceSeq
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/** 刷新图标转一圈的时长（原型 720ms 一圈，不是设计 token，按原型取值）。 */
private const val REFRESH_SPIN_MS = 720

/** 列表里账号之前的固定项数：sticky 结论条 + 重置策略分段。 */
private const val ACCOUNT_ITEM_OFFSET = 2

/**
 * 「额度」Tab（原型 s18 · r4：账号卡列表 + 点击展开图表）。
 *
 * 信息架构：页头 → sticky 结论条 → 重置时刻策略 → 账号卡列表（7 行）→ 恢复提醒三态
 * → 桌面组件样张 → 说明。点账号行展开该账号的全部窗口与用量柱图，再点收起。
 *
 * 推导全在 [buildQuotaAccounts]（纯函数）：徽标优先级、两列选择、紧急判定都在那里，
 * 组合层只做摆放与交互。
 */
@Composable
fun QuotaScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuotaViewModel = viewModel(factory = QuotaViewModelFactory),
    /**
     * 「N 分钟前 / 今天 17:00」这类相对时间与重置文案的基准时刻。
     *
     * 生产取系统时钟；对照测试把数据层与界面钉在同一时刻（否则两边各读一个时钟，
     * 相对时间与「今天 / 周一」判定会互相打架）。默认值就是生产行为。
     */
    nowEpochSecond: Long = System.currentTimeMillis() / 1000,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val prefs = remember(context) { QuotaScreenPrefs(context) }
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val states by viewModel.providerStates.collectAsStateWithLifecycle()
    val triad by viewModel.alertTriad.collectAsStateWithLifecycle()
    val lowCny by viewModel.lowBalanceCny.collectAsStateWithLifecycle()
    val lowUsd by viewModel.lowBalanceUsd.collectAsStateWithLifecycle()
    val refreshMinutes by viewModel.refreshMinutes.collectAsStateWithLifecycle()
    val toast = rememberToastHostState()

    var resetMode by remember { mutableStateOf(prefs.resetMode) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var helpVisible by remember { mutableStateOf(false) }
    // 原型默认 7 天：数据层缺省是 24 小时，首次展开时切一次；用户手动选过就不再改
    val autoRangeDone = remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    var stickyHeightPx by remember { mutableIntStateOf(0) }

    val now = nowEpochSecond
    val thresholds = remember(lowCny, lowUsd) { mapOf(QuotaUnit.CNY to lowCny, QuotaUnit.USD to lowUsd) }
    val accounts = remember(providers, states, triad, thresholds, now) {
        buildQuotaAccounts(providers, states, triad, thresholds, now)
    }
    val leader = remember(accounts) { quotaLeader(accounts) }
    val (accountCount, providerCount) = remember(accounts) { quotaHeadCounts(accounts) }
    val updatedText = remember(states, now) {
        quotaUpdatedText(quotaLatestFetch(states), now, failed = quotaHasFailure(states))
    }
    // 一个 Key 都没配：列表位置换成最轻的引导（原型没有这一屏，见 QuotaEmptyAccounts 注释）
    val anyConfigured = remember(accounts) { accounts.any { it.configured } }

    // 进页面拉一次（节流在数据层）；定时器按最短的账号间隔刷全部
    LaunchedEffect(Unit) { viewModel.onVisible() }
    LaunchedEffect(refreshMinutes) {
        while (true) {
            delay(refreshMinutes.coerceAtLeast(1) * 60_000L)
            viewModel.onTick()
        }
    }

    // 展开后把该行顶到结论条下沿：sticky 高度在运行期量出来，偏移量取负值（不写死魔法数）
    LaunchedEffect(expandedId) {
        val id = expandedId ?: return@LaunchedEffect
        val index = accounts.indexOfFirst { it.instanceId == id }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(ACCOUNT_ITEM_OFFSET + index, -stickyHeightPx)
    }

    // 从额度设置返回：齿轮接回焦点（跨覆盖层信号，见 QuotaSettingsFocusReturn）
    val refreshFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val settingsReturnGeneration = QuotaSettingsFocusReturn.generation
    var seenReturnGeneration by remember { mutableIntStateOf(settingsReturnGeneration) }
    LaunchedEffect(settingsReturnGeneration) {
        if (quotaReturnFocusPending(settingsReturnGeneration, seenReturnGeneration)) {
            seenReturnGeneration = settingsReturnGeneration
            runCatching { settingsFocus.requestFocus() }
        }
    }

    Box(modifier.fillMaxSize().testTag("screen-s18")) {
        Column(Modifier.fillMaxSize()) {
            QuotaHead(
                accountCount = accountCount,
                providerCount = providerCount,
                updatedText = updatedText,
                refreshing = states.values.any { it.loading },
                onHelp = { helpVisible = true },
                onRefresh = {
                    viewModel.onRefresh()
                    toast.show("正在刷新 " + accountCount + " 个账号")
                },
                onOpenSettings = onOpenSettings,
                refreshFocus = refreshFocus,
                settingsFocus = settingsFocus,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("s18-content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = t.space8),
            ) {
                stickyHeader(key = "concl") {
                    Box(Modifier.onSizeChanged { stickyHeightPx = it.height }) {
                        QuotaConclBar(leader)
                    }
                }
                if (!anyConfigured) {
                    item(key = "empty") {
                        QuotaEmptyAccounts(onOpenSettings = onOpenSettings)
                    }
                } else {
                    item(key = "mode") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = t.space4)
                                .padding(bottom = 6.dp),
                        ) {
                        SegmentedControl(
                            options = listOf("每行都显示重置", "只显示需处理的行"),
                            selectedIndex = if (resetMode == QuotaResetMode.ALL) 0 else 1,
                            onSelect = { index ->
                                val next = if (index == 0) QuotaResetMode.ALL else QuotaResetMode.URGENT
                                if (next != resetMode) {
                                    resetMode = next
                                    prefs.resetMode = next
                                    toast.show(
                                        if (next == QuotaResetMode.ALL) {
                                            "每行都显示重置时刻"
                                        } else {
                                            "只给即将重置或快见底的行显示重置"
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.testTag("s18-reset-mode"),
                            optionTags = listOf("s18-mode-all", "s18-mode-urgent"),
                        )
                    }
                }
                itemsIndexed(accounts, key = { _, account -> account.instanceId }) { index, account ->
                    val key = quotaAccountKey(account.providerTypeId, instanceSeq(account.instanceId, account.providerTypeId))
                    val state = states[account.instanceId]
                    QuotaAccountItem(
                        account = account,
                        key = key,
                        index = index,
                        total = accounts.size,
                        state = state,
                        expanded = expandedId == account.instanceId,
                        resetMode = resetMode,
                        nowEpochSecond = now,
                        onToggle = {
                            val opening = expandedId != account.instanceId
                            expandedId = if (opening) account.instanceId else null
                            if (opening) {
                                // 第一次展开：没有用量就拉一次；数据层缺省 24 小时，按原型切到 7 天
                                if (account.supportsUsage && state?.usageConfigured == true &&
                                    state.usage == null
                                ) {
                                    viewModel.onRangeChangeFor(account.instanceId, state.range)
                                }
                                if (account.instanceId !in autoRangeDone.value &&
                                    state?.range == QuotaRange.LAST_24_HOURS
                                ) {
                                    autoRangeDone.value = autoRangeDone.value + account.instanceId
                                    viewModel.onRangeChangeFor(account.instanceId, QuotaRange.LAST_7_DAYS)
                                }
                            }
                        },
                        onRangeChange = { range -> viewModel.onRangeChangeFor(account.instanceId, range) },
                    )
                }
                }
                item(key = "alerts") {
                    // B 的组件自带「恢复提醒 · 三态」标题与左右 16dp；空态整块不渲染
                    QuotaAlertTriadSection(
                        triad = triad,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                if (anyConfigured) {
                    item(key = "widget") {
                        QuotaWidgetSample(accounts = accounts, updatedText = updatedText)
                    }
                }
                item(key = "foot") {
                    Text(
                        "演示数据：账号、窗口与用量柱图均为原型示例值，不含真实凭据。点账号行可展开全部窗口与用量图。",
                        style = quotaTextSmall,
                        color = t.muted,
                        modifier = Modifier
                            .padding(horizontal = t.space4)
                            .padding(top = t.space2)
                            .testTag("s18-foot"),
                    )
                }
            }
        }
        AppToast(
            message = toast.message,
            onDismiss = toast::clear,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space8),
        )
    }
    QuotaHelpDialog(visible = helpVisible, onDismiss = { helpVisible = false })
}
