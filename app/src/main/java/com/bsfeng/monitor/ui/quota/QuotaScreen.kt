package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.instanceSeq
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/** 列表里账号之前的固定项数：sticky 结论条。 */
private const val ACCOUNT_ITEM_OFFSET = 1

/**
 * 「额度」Tab（原型 s18 · r5：账号卡列表 + 点击展开图表）。
 *
 * 信息架构：页头 → sticky 结论条 → 账号卡列表 → 额度提醒（点行定位账号）→ 说明。
 *
 * r5 收口（用户反馈「小组件不该出现在页面里 / 排版与点击混乱」）：
 * - 桌面组件样张整块移除：小组件是桌面上的东西，预览归「设置 → 桌面小组件」；
 * - 重置时刻显示策略是展示偏好，移到额度设置页「主屏展示」组，不再占首屏；
 * - 额度提醒的行从「跳设置」改成「定位到对应账号并展开」，与账号行同一个打开路径。
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
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val states by viewModel.providerStates.collectAsStateWithLifecycle()
    val triad by viewModel.alertTriad.collectAsStateWithLifecycle()
    val lowCny by viewModel.lowBalanceCny.collectAsStateWithLifecycle()
    val lowUsd by viewModel.lowBalanceUsd.collectAsStateWithLifecycle()
    val refreshMinutes by viewModel.refreshMinutes.collectAsStateWithLifecycle()
    val toast = rememberToastHostState()
    val context = LocalContext.current
    val prefs = remember(context) { QuotaScreenPrefs(context) }

    // 重置时刻策略在额度设置页改（原型 s19-display）：主屏只读，进页与从设置返回时各读一次
    var resetMode by remember { mutableStateOf(prefs.resetMode) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var helpVisible by remember { mutableStateOf(false) }
    // 同一账号被「额度提醒」再次定位时 expandedId 不变，用计次把滚动重新触发一次
    var focusTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var stickyHeightPx by remember { mutableIntStateOf(0) }

    val now = nowEpochSecond
    val thresholds = remember(lowCny, lowUsd) { mapOf(QuotaUnit.CNY to lowCny, QuotaUnit.USD to lowUsd) }
    // 未配置凭据的实例不上主屏：QuotaInstances.normalize 会给每个注册 provider 补一个默认实例，
    // 照单全列就是一堆「未配置 / —」的占位卡（用户反馈：没配置的 provider 也在列表里）
    val accounts = remember(providers, states, triad, thresholds, now) {
        buildQuotaAccounts(providers, states, triad, thresholds, now).filter { it.configured }
    }
    val leader = remember(accounts) { quotaLeader(accounts) }
    val (accountCount, providerCount) = remember(accounts) { quotaHeadCounts(accounts) }
    val updatedText = remember(states, now) {
        quotaUpdatedText(quotaLatestFetch(states), now, failed = quotaHasFailure(states))
    }
    // 一个 Key 都没配：列表位置换成最轻的引导（原型没有这一屏，见 QuotaEmptyAccounts 注释）
    val anyConfigured = accounts.isNotEmpty()

    // 进页面拉一次（节流在数据层）；定时器按最短的账号间隔刷全部
    LaunchedEffect(Unit) { viewModel.onVisible() }
    LaunchedEffect(refreshMinutes) {
        while (true) {
            delay(refreshMinutes.coerceAtLeast(1) * 60_000L)
            viewModel.onTick()
        }
    }

    /**
     * 打开一个账号：账号行点击与「额度提醒」行定位共用同一条路径。
     *
     * 首次展开且还没有用量柱图时，按该账号当前范围补拉一次；范围本身不做任何隐式切换
     * （r5 之前会偷偷改成 7 天，用户手动选过的范围在下次进页面时被打回）。
     */
    val openAccount: (String) -> Unit = open@{ instanceId ->
        val account = accounts.firstOrNull { it.instanceId == instanceId } ?: return@open
        val state = states[instanceId]
        if (account.supportsUsage && state?.usageConfigured == true && state.usage == null) {
            viewModel.onRangeChangeFor(instanceId, state.range)
        }
        expandedId = instanceId
        focusTick += 1
    }

    // 展开后把该行顶到结论条下沿：sticky 高度在运行期量出来，偏移量取负值（不写死魔法数）
    LaunchedEffect(expandedId, focusTick) {
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
            // 用户在设置里可能改了「重置时刻」档位
            resetMode = prefs.resetMode
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
                contentPadding = PaddingValues(bottom = t.space8),
            ) {
                if (anyConfigured) {
                    stickyHeader(key = "concl") {
                        Box(Modifier.onSizeChanged { stickyHeightPx = it.height }) {
                            QuotaConclBar(leader)
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
                                if (expandedId == account.instanceId) {
                                    expandedId = null
                                } else {
                                    openAccount(account.instanceId)
                                }
                            },
                            onRangeChange = { range -> viewModel.onRangeChangeFor(account.instanceId, range) },
                        )
                    }
                } else {
                    // 空态：没有账号就没有「最低剩余」与「额度提醒」，一屏只留一条空信息（页头 + 引导 + foot）
                    item(key = "empty") {
                        QuotaEmptyAccounts(onOpenSettings = onOpenSettings)
                    }
                }
                item(key = "alerts") {
                    // 自带组标题与左右 16dp；空态整块不渲染
                    QuotaAlertTriadSection(
                        triad = triad,
                        onFocusAccount = { instanceId ->
                            if (accounts.any { it.instanceId == instanceId }) {
                                openAccount(instanceId)
                            } else {
                                toast.show("该账号已不在额度列表里")
                            }
                        },
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                item(key = "foot") {
                    Text(
                        if (anyConfigured) {
                            "点账号行可展开全部窗口与用量图；额度数据来自各 provider 官方接口，只存在本机，不上传。"
                        } else {
                            "额度数据来自各 provider 官方接口，只存在本机，不上传。"
                        },
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
