package com.bsfeng.monitor.ui.quota

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.IOSDivider
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.math.BigDecimal

/**
 * 额度设置（push 屏，原型 s19）。
 *
 * 三组：Provider（凭据）→ 预警（开关 / 阈值 / 通知权限）→ 刷新（后台频率）；
 * Ollama 计费模式是源码独有的第二组以外设置，排在「刷新」之后，保持契约分组顺序不被插入。
 *
 * 开关、阈值、频率全部落在数据层已有的偏好上（`QuotaSettings` / 预警 prefs），
 * 页面本身不持有任何默认值：默认值写在数据层，后台调度与通知读到的才是同一份。
 */
@Composable
fun QuotaSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuotaViewModel = viewModel(factory = QuotaViewModelFactory),
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val alertEnabled by viewModel.alertEnabled.collectAsStateWithLifecycle()
    val lowCny by viewModel.lowBalanceCny.collectAsStateWithLifecycle()
    val lowUsd by viewModel.lowBalanceUsd.collectAsStateWithLifecycle()
    val refreshHours by viewModel.refreshHours.collectAsStateWithLifecycle()
    val plan by viewModel.ollamaPlan.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 每个 provider 的刷新间隔（分钟）：进入页面读一次，改动就地更新并落盘
    var intervals by remember {
        mutableStateOf(providers.associate { it.id to viewModel.refreshMinutesFor(it.id) })
    }
    fun intervalOf(id: String): Int = intervals[id] ?: QuotaSettings.defaultMinutesFor(id)
    fun setInterval(id: String, minutes: Int) {
        intervals = intervals + (id to minutes)
    }
    var thresholdOpen by remember { mutableStateOf(false) }
    // 弹窗关闭后焦点回「低余额提醒」行（契约「弹窗关闭后焦点回本行」）
    var returnFocusToList by remember { mutableStateOf(false) }
    val lowBalanceFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val toast = rememberToastHostState()

    LaunchedEffect(thresholdOpen) {
        if (!thresholdOpen && returnFocusToList) {
            returnFocusToList = false
            lowBalanceFocus.requestFocus()
        }
    }

    // 进入子页焦点落标题（契约 s18-settings · 键盘「进入子页后焦点落在子页标题」）；
    // 覆盖层里本页每次打开都会重新组合，LaunchedEffect(Unit) 正好一次
    LaunchedEffect(Unit) {
        runCatching { titleFocus.requestFocus() }
    }

    /**
     * 退出额度设置：先登记「焦点回额度页齿轮」的一次性信号，再退页。
     * 返回键 / 系统返回 / 手势都走这里（覆盖层里的 BackHandler 也交给同一条路径）。
     */
    val requestBack: () -> Unit = {
        QuotaSettingsFocusReturn.request()
        onBack()
    }
    // 覆盖层里本页先于 AnimatedScreenOverlay 的 BackHandler 接管返回（与 AppearanceScreen 同口径）
    BackHandler { requestBack() }

    // 保存结果决定这一行收不收起：在途等、失败留展开（就地提示 + 保留输入）、成功收起
    val savingProviderId by viewModel.savingProviderId.collectAsStateWithLifecycle()
    val saveErrors by viewModel.saveErrors.collectAsStateWithLifecycle()
    var pendingCollapseId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savingProviderId, saveErrors, pendingCollapseId) {
        val id = pendingCollapseId ?: return@LaunchedEffect
        when (saveRowState(savingProviderId, id, saveErrors[id])) {
            QuotaSaveRowState.WAIT -> Unit
            // 失败：留展开 + 保留输入，就地提示由行本体显示
            QuotaSaveRowState.KEEP_OPEN -> pendingCollapseId = null
            QuotaSaveRowState.COLLAPSE -> {
                pendingCollapseId = null
                expandedId = null
            }
        }
    }

    Box(modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize().testTag("screen-s19")) {
            IOSNavigationBar(
                title = "额度设置",
                onBack = requestBack,
                // 标题接住进入子页的焦点（IOSNavigationBar 的唯一调用点，见其形参说明）
                titleModifier = Modifier.focusRequester(titleFocus).focusable(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("s19-content")
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = t.space8),
                verticalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                Text(
                    "凭据只保存在本机 · 设置随时可改",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space3),
                )
                SettingsGroup(title = "Provider", modifier = Modifier.testTag("s19-providers")) {
                    providers.forEachIndexed { index, info ->
                        if (index > 0) IOSDivider()
                        // 行本体在 QuotaProviderRows.kt（s18 路写区）：锚点只能由本屏包一层承载，
                        // 逐字沿用契约锚点，行内点击/展开行为仍由行本体负责。
                        Box(Modifier.fillMaxWidth().then(providerRowTagOf(info.id))) {
                            ProviderSettingsRow(
                                info = info,
                                expanded = expandedId == info.id,
                                onToggle = {
                                    expandedId = if (expandedId == info.id) null else info.id
                                },
                                onSave = { balance, usage ->
                                    // 不在这里立即收行：等保存 + 查询有结果再决定（失败要留展开就地提示）
                                    pendingCollapseId = info.id
                                    viewModel.onSaveCredentialsFor(info.id, balance, usage)
                                },
                                saving = savingProviderId == info.id,
                                saveError = saveErrors[info.id],
                                intervalMinutes = intervalOf(info.id),
                                onIntervalChange = { minutes ->
                                    setInterval(info.id, minutes)
                                    viewModel.onRefreshIntervalChange(info.id, minutes)
                                },
                            )
                        }
                    }
                }
                Text(
                    "API Key 与平台令牌都加密后只存本机，不写日志、不导出；卸载 App 即随之删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
                )
                SettingsGroup(title = "预警", modifier = Modifier.testTag("s19-alerts")) {
                    AlertSwitchRow(
                        enabled = alertEnabled,
                        onChange = { enabled ->
                            viewModel.onAlertEnabledChange(enabled)
                            toast.show(
                                if (enabled) {
                                    "额度预警已开启 · 剩余 5% 起逐档提醒"
                                } else {
                                    "额度预警已关闭"
                                },
                            )
                        },
                    )
                    IOSDivider()
                    LowBalanceRow(
                        cny = lowCny,
                        usd = lowUsd,
                        onClick = { thresholdOpen = true },
                        focusRequester = lowBalanceFocus,
                    )
                    IOSDivider()
                    NotificationPermissionRow()
                    IOSDivider()
                    Box(Modifier.fillMaxWidth().padding(horizontal = t.space3, vertical = t.space2)) {
                        AppButton(
                            text = "发送测试通知",
                            variant = AppButtonVariant.Ghost,
                            block = true,
                            modifier = Modifier.testTag("s19-test"),
                            onClick = {
                                // 不写任何 prefs：只往系统额度预警渠道投递一条测试通知
                                postQuotaTestNotification(context)
                                toast.show("测试通知已发送 · 额度预警渠道")
                            },
                        )
                    }
                }
                Text(
                    "一次刷新命中多档只合并成一条通知；总开关关闭时仍刷新额度，但不评估预警。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
                )
                SettingsGroup(title = "刷新", modifier = Modifier.testTag("s19-refresh")) {
                    RefreshRow(
                        selectedHours = refreshHours,
                        onSelect = { hours ->
                            // 点已选中项不重复写盘、不重排闹钟（契约边界）
                            if (hours != refreshHours) viewModel.onRefreshHoursChange(hours)
                        },
                    )
                }
                Text(
                    "关闭后台刷新后，只在打开 App 时刷新与评估额度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
                )
                SettingsGroup(title = "Ollama 计费模式") {
                    PlanModeRow(selected = plan, onSelect = viewModel::onOllamaPlanChange)
                }
                Text(
                    "按你账户实际的计费方式选；与 Ollama 返回的结构不一致时，额度卡里会用「?」提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
                )
                Text(
                    "额度数据只用于本机展示与预警通知，不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space3),
                )
            }
        }
        AppToast(
            message = toast.message,
            onDismiss = toast::clear,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space8),
        )
    }

    if (thresholdOpen) {
        ThresholdDialog(
            initialCny = lowCny,
            initialUsd = lowUsd,
            onDismiss = {
                returnFocusToList = true
                thresholdOpen = false
            },
            onConfirm = { cny: BigDecimal, usd: BigDecimal ->
                viewModel.onLowBalanceChange(QuotaUnit.CNY, cny)
                viewModel.onLowBalanceChange(QuotaUnit.USD, usd)
                returnFocusToList = true
                thresholdOpen = false
            },
        )
    }
}

/** provider 行锚点（原型 s19 逐字）：行本体的写区不归本屏，锚点按 provider id 逐字映射。 */
private fun providerRowTagOf(providerId: String): Modifier = when (providerId) {
    "ollama" -> Modifier.testTag("s19-provider-ollama")
    "deepseek" -> Modifier.testTag("s19-provider-deepseek")
    "opencode-go" -> Modifier.testTag("s19-provider-opencode")
    "opendesign" -> Modifier.testTag("s19-provider-opendesign")
    else -> Modifier
}

/** 分组容器：分组小标题 + 卡片底 + 行间分割线（与设置页 iOS 分组一致）。 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    SectionHeader(title = title)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surface)
            .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg)),
    ) {
        content()
    }
}

/** 后台刷新频率（chip 组；0 = 关闭）。选中态互斥由数据层保证，这里只负责把选择交出去。 */
@Composable
private fun RefreshRow(selectedHours: Int, onSelect: (Int) -> Unit) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = t.space3, vertical = t.space3)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            QuotaSettings.REFRESH_OPTIONS.forEach { hours ->
                Chip(
                    label = QuotaSettings.labelOf(hours),
                    selected = hours == selectedHours,
                    onClick = { onSelect(hours) },
                    modifier = refreshChipTagOf(hours),
                )
            }
        }
        Spacer(Modifier.height(t.space3))
        Text(
            "前台自动刷新 60 秒一次（Ollama 限流 10 次/分钟）；息屏与锁屏不触发后台请求，解锁后不补跑。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.testTag("s19-foreground-note"),
        )
    }
}

/** 后台刷新四个选项的锚点（与契约逐字一致）。 */
private fun refreshChipTagOf(hours: Int): Modifier = when (hours) {
    0 -> Modifier.testTag("s19-refresh-off")
    1 -> Modifier.testTag("s19-refresh-1h")
    3 -> Modifier.testTag("s19-refresh-3h")
    6 -> Modifier.testTag("s19-refresh-6h")
    else -> Modifier
}

/** Ollama 计费模式：用户手选、持久化；页面只负责把选择交给 ViewModel。 */
@Composable
private fun PlanModeRow(selected: OllamaPlanMode, onSelect: (OllamaPlanMode) -> Unit) {
    val t = LocalDesignTokens.current
    val modes = OllamaPlanMode.entries
    Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
        SegmentedControl(
            options = modes.map { it.title },
            selectedIndex = modes.indexOf(selected),
            onSelect = { index -> onSelect(modes[index]) },
        )
    }
}
