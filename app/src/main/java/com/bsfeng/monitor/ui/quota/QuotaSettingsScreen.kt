package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.IOSDivider
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.math.BigDecimal

/**
 * 额度设置（push 屏，原型 s19）。
 *
 * 三组：Provider（凭据）→ 预警（开关 / 阈值 / 通知权限）→ 刷新（后台频率）。
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

    Column(modifier.fillMaxSize().pageBackdrop()) {
        IOSNavigationBar(title = "额度设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
            SettingsGroup(title = "Provider") {
                providers.forEachIndexed { index, info ->
                    if (index > 0) IOSDivider()
                    ProviderSettingsRow(
                        info = info,
                        expanded = expandedId == info.id,
                        onToggle = {
                            expandedId = if (expandedId == info.id) null else info.id
                        },
                        onSave = { balance, usage ->
                            viewModel.onSaveCredentialsFor(info.id, balance, usage)
                            expandedId = null
                        },
                        intervalMinutes = intervalOf(info.id),
                        onIntervalChange = { minutes ->
                            setInterval(info.id, minutes)
                            viewModel.onRefreshIntervalChange(info.id, minutes)
                        },
                    )
                }
            }
            Text(
                "API Key 与平台令牌都加密后只存本机，不写日志、不导出；卸载 App 即随之删除。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
            )
            SettingsGroup(title = "预警") {
                AlertSwitchRow(enabled = alertEnabled, onChange = viewModel::onAlertEnabledChange)
                IOSDivider()
                LowBalanceRow(
                    cny = lowCny,
                    usd = lowUsd,
                    onClick = { thresholdOpen = true },
                )
                IOSDivider()
                NotificationPermissionRow()
            }
            SettingsGroup(title = "Ollama 计费模式") {
                PlanModeRow(selected = plan, onSelect = viewModel::onOllamaPlanChange)
            }
            Text(
                "按你账户实际的计费方式选；与 Ollama 返回的结构不一致时，额度卡里会用「?」提示。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
            )
            SettingsGroup(title = "刷新") {
                RefreshRow(selectedHours = refreshHours, onSelect = viewModel::onRefreshHoursChange)
            }
            Text(
                "关闭后台刷新后，只在打开 App 时刷新与评估额度。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(horizontal = t.space4, vertical = t.space2),
            )
        }
    }

    if (thresholdOpen) {
        ThresholdDialog(
            initialCny = lowCny,
            initialUsd = lowUsd,
            onDismiss = { thresholdOpen = false },
            onConfirm = { cny, usd ->
                viewModel.onLowBalanceChange(QuotaUnit.CNY, cny)
                viewModel.onLowBalanceChange(QuotaUnit.USD, usd)
                thresholdOpen = false
            },
        )
    }
}

/** 分组容器：分组小标题 + 卡片底 + 行间分割线（与设置页 iOS 分组一致）。 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    SectionHeader(title = title)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surface)
            .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg)),
    ) {
        content()
    }
}

/** 后台刷新频率（chip 组；0 = 关闭）。 */
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
                )
            }
        }
        Spacer(Modifier.height(t.space3))
        Text(
            "前台自动刷新 60 秒一次（Ollama 限流 10 次/分钟）；息屏与锁屏不触发后台请求，解锁后不补跑。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
    }
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
