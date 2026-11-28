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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.quota.postQuotaTestNotification
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
 * Ollama 计费模式是源码独有的第二组以外设置，排在「刷新」之后，保持口径分组顺序不被插入。
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
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 每个账号的刷新间隔（分钟）：进入页面读一次，改动就地更新并落盘。
    // 键是实例键（第一实例 == 类型 id），所以老用户的 refresh_minutes 偏好天然命中。
    var intervals by remember {
        mutableStateOf(providers.associate { it.instanceId to viewModel.refreshMinutesFor(it.instanceId) })
    }
    // 新增账号时补一条默认值：不补的话 intervalOf 会退回类型默认值（行为相同，但选中态会闪一次）
    LaunchedEffect(providers) {
        val missing = providers.filter { it.instanceId !in intervals }
        if (missing.isNotEmpty()) {
            intervals = intervals + missing.associate { it.instanceId to viewModel.refreshMinutesFor(it.instanceId) }
        }
    }
    fun intervalOf(instanceId: String): Int = intervals[instanceId] ?: viewModel.refreshMinutesFor(instanceId)
    fun setInterval(instanceId: String, minutes: Int) {
        intervals = intervals + (instanceId to minutes)
    }
    var thresholdOpen by remember { mutableStateOf(false) }
    // 弹窗关闭后焦点回「低余额提醒」行（口径「弹窗关闭后焦点回本行」）
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

    // 进入子页焦点落标题（锚点 s18-settings · 键盘「进入子页后焦点落在子页标题」）；
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
    // 收行的两个来源：保存（成功收起 / 失败留展开）与清除（成功后收起并 toast）
    var pendingClearId by remember { mutableStateOf<String?>(null) }
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
    // 清除沿用同一套收行判据：在途等、失败留展开就地提示、成功收起 + 一次 toast
    LaunchedEffect(savingProviderId, saveErrors, pendingClearId) {
        val id = pendingClearId ?: return@LaunchedEffect
        when (saveRowState(savingProviderId, id, saveErrors[id])) {
            QuotaSaveRowState.WAIT -> Unit
            QuotaSaveRowState.KEEP_OPEN -> pendingClearId = null
            QuotaSaveRowState.COLLAPSE -> {
                pendingClearId = null
                expandedId = null
                toast.show(CLEAR_CREDENTIALS_DONE)
            }
        }
    }

    Box(modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize().testTag("screen-s19")) {
            IOSNavigationBar(
                title = "额度设置",
                onBack = requestBack,
                // 标题接住进入子页的焦点（IOSNavigationBar 的唯一调用点，见其形参说明）
                modifier = Modifier.focusRequester(titleFocus).focusable(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("s19-content")
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = t.space8),
                // 原型 s19 的纵向节奏写在各段自己的 padding / margin 上（.s19-group + .s19-group = 16），
                // 统一 item 间距会与它重复叠加
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // 原型 .s19-intro：内容区顶 8，下方 12 由 padding 承担（Column 的 item 间距已归 0，
                // 纵向节奏全按原型写在各段自己的 padding 上）
                Text(
                    "凭据只保存在本机 · 设置随时可改",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(start = t.space4, end = t.space4, top = t.space2, bottom = t.space3),
                )
                AccountGroups(
                    providers = providers,
                    expandedId = expandedId,
                    savingProviderId = savingProviderId,
                    saveErrors = saveErrors,
                    intervalOf = ::intervalOf,
                    onToggle = { instanceId ->
                        expandedId = if (expandedId == instanceId) null else instanceId
                    },
                    onAdd = { providerTypeId ->
                        val created = viewModel.onAddAccount(providerTypeId)
                        if (created != null) {
                            // 新账号默认展开：加完账号的下一步一定是填凭据
                            expandedId = created
                            toast.show("已添加账号 · 填写凭据后开始查询")
                        } else {
                            toast.show("添加失败 · 该 provider 未接入或账号已达上限")
                        }
                    },
                    onSave = { instanceId, balance, usage ->
                        // 不在这里立即收行：等保存 + 查询有结果再决定（失败要留展开就地提示）
                        pendingCollapseId = instanceId
                        viewModel.onSaveCredentialsFor(instanceId, balance, usage)
                    },
                    onClear = { instanceId ->
                        pendingClearId = instanceId
                        viewModel.onClearCredentialsFor(instanceId)
                    },
                    onRename = { instanceId, label ->
                        viewModel.onRenameAccount(instanceId, label)
                        toast.show(if (label.isBlank()) "已恢复默认名称" else "已改名为「$label」")
                    },
                    onDelete = { instanceId ->
                        viewModel.onDeleteAccount(instanceId) { removed ->
                            toast.show(if (removed) "账号已删除" else "至少要保留一个账号")
                        }
                    },
                    onIntervalChange = { instanceId, minutes ->
                        setInterval(instanceId, minutes)
                        viewModel.onRefreshIntervalChange(instanceId, minutes)
                    },
                )
                Text(
                    "API Key 与平台令牌都加密后只存本机，不写日志、不导出；卸载 App 即随之删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
                )
                TitledSettingsGroup(title = "预警", modifier = Modifier.testTag("s19-alerts")) {
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
                    // 原型 .s19-actions：按钮与卡片同列（卡片内 16 已由 .group 给出），
                    // 上下再各留 --space-3 与 --space-2
                    Box(Modifier.fillMaxWidth().padding(vertical = t.space2)) {
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
                // 原型 s19-refresh 是「组标题 + 组卡 + 组尾说明」一整块：说明要放进同一锚点，
                // 否则锚点高度少一行（对照判据里是 P1 几何差）
                // 原型 s19-refresh 是「组标题 + 卡片 + 组尾说明」一整块，锚点宽度 = 卡片宽 361（x=16）：
                // 与 s18-mode（锚点包住 padding）相反，这里 padding 在前、testTag 在后，
                // 量到的才是内缩后的块，而不是整屏 393
                Column(Modifier.padding(horizontal = LocalDesignTokens.current.space4).testTag("s19-refresh")) {
                    TitledSettingsGroup(title = "刷新", inset = false) {
                        RefreshRow(
                            selectedHours = refreshHours,
                            onSelect = { hours ->
                                // 点已选中项不重复写盘、不重排闹钟（口径边界）
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
                }
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


/** 分组容器：分组小标题 + 卡片底 + 行间分割线（与设置页 iOS 分组一致）。 */
@Composable
private fun TitledSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    /** 左右 16 由本组提供（默认）；外层已经给过内边距时传 false，避免叠加成 32。 */
    inset: Boolean = true,
    content: @Composable () -> Unit,
) {
    SectionHeader(title = title)
    // 原型 .content 的左右 --space-4 落在这里：分组卡宽 361（x=16），
    // 内边距必须先于锚点 modifier 应用，锚点才量到卡片本体而不是整屏
    val pad = if (inset) Modifier.padding(horizontal = LocalDesignTokens.current.space4) else Modifier
    SettingsGroup(modifier = pad.then(modifier), content = content)
}

/** 后台刷新频率（chip 组；0 = 关闭）。选中态互斥由数据层保证，这里只负责把选择交出去。 */
@Composable
private fun RefreshRow(selectedHours: Int, onSelect: (Int) -> Unit) {
    val t = LocalDesignTokens.current
    // 原型 .s19-chips{padding: var(--space-3) 0}：左右不再内缩（卡片内 16 已由 .group 提供），
    // 否则 chip 与下方说明会整体右移 12、可点宽度少 24
    Column(Modifier.fillMaxWidth().padding(vertical = t.space3)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            QuotaSettings.REFRESH_OPTIONS.forEach { hours ->
                Chip(
                    label = QuotaSettings.labelOf(hours),
                    selected = hours == selectedHours,
                    onClick = { onSelect(hours) },
                    modifier = Modifier.refreshChipTagOf(hours),
                )
            }
        }
        // 原型 .s19-refresh-note{border-top:1px solid var(--border-soft);padding-top:var(--space-3)}：
        // 细线 + 上留 12 是它比两行文字高出 12dp 的来源，缺了这段说明块会矮 13.9dp
        // 原型 .s19-refresh-note{border-top:1px solid var(--border-soft);padding-top:var(--space-3);
        // line-height:1.45}：整块高 = 1 + 12 + 两行 40.6 = 53.6，与原型一致。
        // 早先只写了 Spacer(12) + 默认行盒，少 1px 细线且行盒矮 1.2dp/行 → 整块矮 13.9
        HorizontalDivider(thickness = 1.dp, color = t.borderSoft)
        Text(
            "前台自动刷新 60 秒一次（Ollama 限流 10 次/分钟）；息屏与锁屏不触发后台请求，解锁后不补跑。",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.3.sp),
            color = t.muted,
            modifier = Modifier.testTag("s19-foreground-note").padding(top = t.space3),
        )
    }
}

