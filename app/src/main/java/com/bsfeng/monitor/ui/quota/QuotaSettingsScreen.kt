package com.bsfeng.monitor.ui.quota

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.quota.postQuotaTestNotification
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.math.BigDecimal

/**
 * 额度设置（push 屏，原型 s19）。
 *
 * 三组：账号（同家可多账号）→ 恢复提醒 → 刷新节奏。
 * 开关 / 阈值 / 频率全部落在数据层已有的偏好上（QuotaSettings / 预警 prefs），页面不持默认值。
 *
 * @param nowSeconds 当前时间（epoch 秒），只用于「X 分钟前更新」这类副行；测试注入固定时钟。
 */
@Composable
fun QuotaSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuotaViewModel = viewModel(factory = QuotaViewModelFactory),
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val states by viewModel.providerStates.collectAsStateWithLifecycle()
    val alertEnabled by viewModel.alertEnabled.collectAsStateWithLifecycle()
    val lowCny by viewModel.lowBalanceCny.collectAsStateWithLifecycle()
    val lowUsd by viewModel.lowBalanceUsd.collectAsStateWithLifecycle()
    // 后台刷新：ViewModel 仍叫 refreshHours，值已是分钟（见 QuotaSettings 的兼容属性）
    val backgroundMinutes by viewModel.refreshHours.collectAsStateWithLifecycle()
    val settings = remember { QuotaSettings(context) }
    val screenPrefs = remember { QuotaScreenPrefs(context) }
    var resetMode by remember { mutableStateOf(screenPrefs.resetMode) }
    var sleepSkip by remember { mutableStateOf(settings.skipRefreshWhenScreenOff) }
    // 进页面默认展开第一个账号（原型 s19 主号展开）：加完账号的下一步一定是填凭据
    var expandedId by remember { mutableStateOf(providers.firstOrNull()?.instanceId) }
    var thresholdOpen by remember { mutableStateOf(false) }
    var returnFocusToList by remember { mutableStateOf(false) }
    val lowBalanceFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val toast = rememberToastHostState()
    val savingProviderId by viewModel.savingProviderId.collectAsStateWithLifecycle()
    val saveErrors by viewModel.saveErrors.collectAsStateWithLifecycle()
    var pendingCollapseId by remember { mutableStateOf<String?>(null) }
    var pendingClearId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(thresholdOpen) {
        if (!thresholdOpen && returnFocusToList) {
            returnFocusToList = false
            lowBalanceFocus.requestFocus()
        }
    }
    // 进入子页焦点落标题（覆盖层里每次打开都会重新组合，正好一次）
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }

    /** 退出额度设置：先登记「焦点回额度页齿轮」的一次性信号，再退页。 */
    val requestBack: () -> Unit = {
        QuotaSettingsFocusReturn.request()
        onBack()
    }
    BackHandler { requestBack() }

    // 保存 / 清除结束后这一行的处置：等 → 失败留展开 → 成功收起
    LaunchedEffect(savingProviderId, saveErrors, pendingCollapseId) {
        val id = pendingCollapseId ?: return@LaunchedEffect
        when (saveRowState(savingProviderId, id, saveErrors[id])) {
            QuotaSaveRowState.WAIT -> Unit
            QuotaSaveRowState.KEEP_OPEN -> pendingCollapseId = null
            QuotaSaveRowState.COLLAPSE -> {
                pendingCollapseId = null
                expandedId = null
            }
        }
    }
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

    val frontMinutes = viewModel.refreshMinutesFor(expandedId ?: providers.firstOrNull()?.instanceId.orEmpty())

    Box(modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize().testTag("screen-s19")) {
            // 覆盖层整屏：状态栏避让只能页头自己来（与 ScreenHead 的 consumeTopInset 同一写法）
            Column(Modifier.statusBarsPadding()) {
                IOSNavigationBar(
                    title = "额度设置",
                    onBack = requestBack,
                    modifier = Modifier.focusRequester(titleFocus).focusable(),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("s19-content")
                    .verticalScroll(rememberScrollState()),
            ) {
                IntroBlock()
                AccountsGroup(
                    providers = providers,
                    states = states,
                    nowEpochSecond = nowSeconds(),
                    expandedId = expandedId,
                    savingProviderId = savingProviderId,
                    saveErrors = saveErrors,
                    onToggle = { instanceId ->
                        expandedId = if (expandedId == instanceId) null else instanceId
                    },
                    onAdd = { providerTypeId ->
                        val created = viewModel.onAddAccount(providerTypeId)
                        if (created != null) {
                            expandedId = created
                            toast.show("已添加账号 · 填写凭据后开始查询")
                        } else {
                            toast.show("添加失败 · 该 provider 未接入或账号已达上限")
                        }
                    },
                    onSave = { instanceId, value ->
                        pendingCollapseId = instanceId
                        viewModel.onSaveCredentialsFor(instanceId, value, value)
                    },
                    onClear = { instanceId ->
                        pendingClearId = instanceId
                        viewModel.onClearCredentialsFor(instanceId)
                    },
                    onToast = toast::show,
                )
                Spacer(Modifier.height(t.space3))
                RecoverGroup(
                    alertEnabled = alertEnabled,
                    lowCny = lowCny,
                    lowUsd = lowUsd,
                    lowBalanceFocus = lowBalanceFocus,
                    onAlertChange = { enabled ->
                        viewModel.onAlertEnabledChange(enabled)
                        toast.show(if (enabled) "额度预警已开启" else "额度预警已关闭")
                    },
                    onRecoverChange = { enabled ->
                        toast.show(if (enabled) "额度恢复提醒已开启" else "额度恢复提醒已关闭")
                    },
                    onThresholdClick = { thresholdOpen = true },
                    onTestNotification = {
                        postQuotaTestNotification(context)
                        toast.show("已发送一条示例恢复通知")
                    },
                )
                Spacer(Modifier.height(t.space3))
                RefreshRhythmGroup(
                    backgroundMinutes = backgroundMinutes,
                    frontMinutes = frontMinutes,
                    sleepSkip = sleepSkip,
                    onSelect = { minutes ->
                        // 点已选中项不重复写盘、不重排闹钟
                        if (minutes != backgroundMinutes) viewModel.onRefreshHoursChange(minutes)
                    },
                    onSleepChange = { checked ->
                        sleepSkip = checked
                        settings.skipRefreshWhenScreenOff = checked
                        toast.show(if (checked) "息屏后不再后台刷新" else "息屏后仍按后台间隔刷新")
                    },
                )
                Spacer(Modifier.height(t.space3))
                DisplayGroup(
                    resetMode = resetMode,
                    onResetModeChange = { next ->
                        resetMode = next
                        screenPrefs.resetMode = next
                        toast.show(
                            if (next == QuotaResetMode.ALL) {
                                "每行都显示重置时刻"
                            } else {
                                "只给即将重置或快见底的行显示重置"
                            },
                        )
                    },
                )
                Spacer(Modifier.height(t.space3))
                FootNote()
                SaveAllButton(
                    onClick = {
                        // 各档位已即时落盘；这里再显式重排一次，保证「保存全部」不是空动作
                        viewModel.onRefreshHoursChange(backgroundMinutes)
                        toast.show("已保存全部设置")
                    },
                )
                Spacer(Modifier.height(t.space6))
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

/** 组间说明：三句都在讲「凭据/数据去哪了」（原型 s19-intro）。 */
@Composable
private fun IntroBlock() {
    val t = LocalDesignTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = t.space2, bottom = 10.dp), // 原型 .settings-intro: margin-bottom 10
    ) {
        Text(
            "同一家可以挂多个账号：每行独立刷新、独立预警，榜单里也会分开展示。凭据只存本机。",
            // 原型 .settings-intro：11px / line-height 1.4（两行 30.8）
            // 目标两行 30.8：11sp × 1.4 = 15.4 会多 1.2 的行盒余量，收到 14.8
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = 11.sp, lineHeight = 14.8.sp)
                .quotaTight(),
            color = t.muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = t.space4)
                .testTag("s19-intro"),
        )
    }
}

/**
 * 恢复提醒组（原型 s19-recover）：耗尽提醒 → 恢复提醒 → 快见底阈值 → 三态图例 → 示例通知。
 *
 * 五行的本体在 [QuotaSettingsAlerts]（B 任务的文件，本屏只负责组壳与顺序）；
 * 每行左右 12 由这里给（原型 .srow padding 6 12），行顶边线画进行自己的盒子里。
 */
@Composable
private fun RecoverGroup(
    alertEnabled: Boolean,
    lowCny: BigDecimal,
    lowUsd: BigDecimal,
    lowBalanceFocus: FocusRequester,
    onAlertChange: (Boolean) -> Unit,
    onRecoverChange: (Boolean) -> Unit,
    onThresholdClick: () -> Unit,
    onTestNotification: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = t.space4).testTag("s19-recover")) {
        GroupLabel(title = "恢复提醒", trailing = "")
        SettingsBox {
            RecoverRow(topBorder = false) {
                AlertSwitchRow(enabled = alertEnabled, onChange = onAlertChange)
            }
            RecoverRow { RecoverAlertSwitchRow(onChange = onRecoverChange) }
            RecoverRow {
                LowBalanceRow(
                    cny = lowCny,
                    usd = lowUsd,
                    onClick = onThresholdClick,
                    focusRequester = lowBalanceFocus,
                )
            }
            // 原型 .tristate：锚点挂整块（x=17、w=359、h=71.9），文字左右 12；图例本体自带上下 8
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(t.surfaceWarm)
                    .topHairline()
                    // 原型 .tristate 上下 10（图例本体自带 8，这里补 2 才到 71.9）
                    .padding(vertical = 2.dp)
                    .testTag("s19-tristate"),
            ) {
                Box(Modifier.padding(horizontal = t.space3)) { AlertTriadLegend() }
            }
            TestNotificationRow(onClick = onTestNotification)
        }
    }
}

/**
 * 「主屏展示」组（原型 s19-display）：重置时刻策略两档。
 *
 * r5 从额度主屏搬到这里：它是排版偏好，不是每次看额度都要调的东西；
 * 放在主屏会白占 44dp 首屏，而且与「看余额」不是一类操作。
 */
@Composable
private fun DisplayGroup(
    resetMode: QuotaResetMode,
    onResetModeChange: (QuotaResetMode) -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = t.space4).testTag("s19-display")) {
        GroupLabel(title = "主屏展示", trailing = "只影响额度 Tab 的行")
        SettingsBox {
            Box(Modifier.fillMaxWidth().padding(horizontal = t.space3, vertical = t.space2)) {
                SegmentedControl(
                    options = listOf("每行都显示重置", "只显示需处理的行"),
                    selectedIndex = if (resetMode == QuotaResetMode.ALL) 0 else 1,
                    onSelect = { index ->
                        val next = if (index == 0) QuotaResetMode.ALL else QuotaResetMode.URGENT
                        if (next != resetMode) onResetModeChange(next)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionTags = listOf("s19-reset-all", "s19-reset-urgent"),
                )
            }
            Text(
                "「只显示需处理的行」会把正常行的重置时刻折叠；快见底与已耗尽的行照常显示。",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp).quotaTight(),
                color = t.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space3)
                    .padding(bottom = t.space2)
                    .testTag("s19-display-foot"),
            )
        }
    }
}

/** 恢复提醒组的一行：行顶 1px（第一行不画）+ 左右 12（原型 .srow padding 6 12）。 */
@Composable
private fun RecoverRow(topBorder: Boolean = true, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Box(Modifier.fillMaxWidth().topHairline(topBorder)) {
        Box(Modifier.padding(horizontal = t.space3)) { content() }
    }
}

/** 「发送示例通知」行（原型 s19-test）：整行可点，按当前数据造一条「已恢复」。 */
@Composable
private fun TestNotificationRow(onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .topHairline()
            .testTag("s19-test")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "发送示例通知",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = t.fg,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                "用当前数据造一条「已恢复」，不消耗真实额度",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = t.muted,
            )
        }
        Spacer(Modifier.width(t.space2))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null, // decorative：整行文本已是可读名
            tint = t.muted,
            modifier = Modifier.width(16.dp),
        )
    }
}

/** 页尾说明（原型 s19-foot）：锚点打在整块 p 上（x=0、w=393），文字左右缩进 16。 */
@Composable
private fun FootNote() {
    val t = LocalDesignTokens.current
    Text(
        "额度数据只用于本机展示与通知，不上传；通知开关受系统通知权限约束。",
        style = MaterialTheme.typography.labelSmall
            .copy(fontSize = 12.sp, lineHeight = (12 * t.leadingBody).sp)
            .quotaTight(),
        color = t.muted,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s19-foot")
            .padding(horizontal = t.space4),
    )
}

/** 「保存全部」（原型 s19-save-all）：整宽主色按钮，左右 16、上下 12/16。 */
@Composable
private fun SaveAllButton(onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = t.space4, end = t.space4, top = 8.dp, bottom = t.space4),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(t.tapMin)
                .testTag("s19-save-all")
                .clip(shape)
                .background(t.accent)
                .pressFeedback(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.SUCCESS)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "保存全部",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                color = t.accentOn,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}