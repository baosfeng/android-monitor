package com.bsfeng.monitor.ui.quota

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.quota.QuotaScheduler
import com.bsfeng.monitor.quota.QuotaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * 「额度」Tab 的状态。
 *
 * 唯一真值源是 [QuotaRepository]（节流 / 退避 / 快照缓存都在数据层），本 VM 只做三件事：
 * 把 Flow 转成界面需要的形态、把点击转成数据层调用、把用户偏好落盘。
 *
 * 依赖从构造注入：`QuotaRepository` 需要 provider 注册表与加密存储，不能在这里 new
 * （新建出来的 registry 是空的，页面会永远显示「未接入」）。
 */
class QuotaViewModel(
    application: Application,
    private val repository: QuotaRepository,
    private val settings: QuotaSettings = QuotaSettings(application),
) : AndroidViewModel(application) {

    /** 预警开关与阈值复用预警通知自己的 prefs（见 [QuotaAlertPrefs] 的说明）。 */
    private val alertPrefs = QuotaAlertPrefs(application)

    init {
        // 节流基准来自设置页的「每 provider 刷新间隔」，默认 1 分钟
        repository.intervalSeconds = { id -> settings.refreshIntervalMinutes(id) * 60L }
    }

    private val _selectedProviderId = MutableStateFlow(defaultProviderId())
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()

    /** 全部 provider 的原始状态（设置页要显示每个 provider 的凭据配置情况）。 */
    val providerStates: StateFlow<Map<String, QuotaRepository.ProviderState>> = repository.states

    /** chip / 设置列表用；provider 注册与凭据变化时自动重算。 */
    val providers: StateFlow<List<QuotaProviderInfo>> = repository.states
        .map { buildProviders(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildProviders(repository.states.value))

    /** 当前选中 provider 的状态（额度页主体）。 */
    val state: StateFlow<QuotaRepository.ProviderState> =
        combine(repository.states, _selectedProviderId) { states, id ->
            states[id] ?: QuotaRepository.ProviderState(id)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            repository.state(_selectedProviderId.value),
        )

    private val _ollamaPlan = MutableStateFlow(settings.ollamaPlan)
    val ollamaPlan: StateFlow<OllamaPlanMode> = _ollamaPlan.asStateFlow()

    private val _alertEnabled = MutableStateFlow(settings.alertsEnabled)
    val alertEnabled: StateFlow<Boolean> = _alertEnabled.asStateFlow()

    private val _lowBalanceCny = MutableStateFlow(alertPrefs.lowBalance(QuotaUnit.CNY))
    val lowBalanceCny: StateFlow<BigDecimal> = _lowBalanceCny.asStateFlow()

    private val _lowBalanceUsd = MutableStateFlow(alertPrefs.lowBalance(QuotaUnit.USD))
    val lowBalanceUsd: StateFlow<BigDecimal> = _lowBalanceUsd.asStateFlow()

    private val _refreshHours = MutableStateFlow(settings.refreshIntervalHours)

    /** 聚合视图（true = 榜单，一家一行）还是单家视图；偏好落盘，见 [QuotaSettings.aggregateMode]。 */
    private val _aggregateMode = MutableStateFlow(settings.aggregateMode)
    val aggregateMode: StateFlow<Boolean> = _aggregateMode.asStateFlow()

    /** 聚合榜单 / 灰行 / 更新时间，逻辑全在 [QuotaAggregateDelegate] 与 [buildAggregateSnapshot]。 */
    private val aggregateDelegate = QuotaAggregateDelegate(repository, viewModelScope)
    val aggregateSnapshot: StateFlow<QuotaAggregateSnapshot> = aggregateDelegate.snapshot

    /** 当前选中 provider 的前台刷新间隔（分钟），改动后立即生效。 */
    private val _refreshMinutes = MutableStateFlow(
        settings.refreshIntervalMinutes(repository.providers().firstOrNull()?.id.orEmpty()),
    )
    val refreshMinutes: StateFlow<Int> = _refreshMinutes.asStateFlow()
    val refreshHours: StateFlow<Int> = _refreshHours.asStateFlow()

    /**
     * 凭据的保存 / 清除：在途锁、失败原因、完成信号都在 [QuotaCredentialActions] 里。
     *
     * 两者共用同一把在途锁（同一时刻只有一行展开）：允许交叉连点会出现
     * 「保存请求在跑时清除，随后保存成功又把凭据写回」的竞态。
     */
    private val credentialActions = QuotaCredentialActions(repository, viewModelScope)

    /** 正在保存 / 清除凭据的 provider（设置页据此把按钮锁一次）。 */
    val savingProviderId: StateFlow<String?> = credentialActions.savingProviderId

    /** 上次操作后就地提示的失败原因（按 provider）；重新保存或成功时清掉。 */
    val saveErrors: StateFlow<Map<String, String>> = credentialActions.errors

    /** 进页面时拉一次（repository 内部 60 秒节流，切 Tab 不会打满限流）。 */
    fun onProviderVisible() {
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(id) }
    }

    fun onRefresh() {
        val id = _selectedProviderId.value
        // 聚合视图没有「当前选中家」的概念：刷全部（非 force，节流在数据层）
        if (_aggregateMode.value) {
            aggregateDelegate.refreshAll()
            return
        }
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(id, force = true) }
    }

    /** 聚合 / 单家切换：只影响展示口径，不触发重拉（两份视图读的是同一份 states）。 */
    fun onAggregateModeChange(enabled: Boolean) {
        if (_aggregateMode.value == enabled) return
        settings.aggregateMode = enabled
        _aggregateMode.value = enabled
    }

    /** 从聚合榜单点进某一家：切到单家视图并选中它，让下钻落在同一家的详情上。 */
    fun onOpenProviderFromAggregate(providerId: String) {
        onAggregateModeChange(false)
        onSelectProvider(providerId)
    }

    /** 进入聚合视图时拉一次：榜单要的是全部家，只拉当前选中家会让其他行停在旧值。 */
    fun onAggregateVisible() = aggregateDelegate.refreshAll()

    /** 前台定时刷新：受节流约束，间隔没到就直接返回（不 force）。 */
    fun onTick() {
        if (_aggregateMode.value) {
            aggregateDelegate.refreshAll()
            return
        }
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(id, force = false) }
    }

    /** 某个 provider 的前台刷新间隔（分钟）。 */
    fun refreshMinutesFor(providerId: String): Int = settings.refreshIntervalMinutes(providerId)

    /** 修改某个 provider 的刷新间隔（分钟）；同时刷新 repository 的节流基准。 */
    fun onRefreshIntervalChange(providerId: String, minutes: Int) {
        // 点已选中的 chip 不重复落盘（锚点 s19 · 点已选中的 chip 不重复落盘也不发请求）
        if (settings.refreshIntervalMinutes(providerId) == minutes) return
        settings.setRefreshIntervalMinutes(providerId, minutes)
        repository.intervalSeconds = { id -> settings.refreshIntervalMinutes(id) * 60L }
        // 只同步「当前选中家」的前台定时器：改了别家的间隔不该重置当前家的节奏
        // （节奏由 QuotaScreen 的 LaunchedEffect 按 refreshMinutes 重启，见 shouldSyncRefreshMinutes）
        if (shouldSyncRefreshMinutes(providerId, _selectedProviderId.value)) {
            _refreshMinutes.value = settings.refreshIntervalMinutes(providerId)
        }
    }

    fun onSelectProvider(id: String) {
        if (_selectedProviderId.value == id) return
        _refreshMinutes.value = settings.refreshIntervalMinutes(id)
        _selectedProviderId.value = id
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(id) }
    }

    fun onRangeChange(range: QuotaRange) {
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) { repository.setRange(id, range) }
    }

    /** 单条凭据保存（Ollama 一个输入 / DeepSeek 的两行各自调用）。 */
    fun onSaveCredential(kind: QuotaCredentialKind, value: String?) {
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) { repository.saveCredential(id, kind, value) }
    }

    /** DeepSeek 的两个输入一起保存；空串由 repository 视作清除。 */
    fun onSaveCredentials(balanceValue: String?, usageValue: String?) {
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) {
            val provider = repository.provider(id) ?: return@launch
            saveProviderCredentials(repository, provider, balanceValue, usageValue)
        }
    }

    /**
     * 设置页保存某个 provider 的两个凭据（不改当前选中项）。
     *
     * 三件事都要给设置页一个可观察的结果：在途锁（连点只存一次）、失败原因（就地提示）、
     * 完成信号（成功后收起展开行，失败则留展开并保留输入，见 [saveRowState]）。
     */
    fun onSaveCredentialsFor(providerId: String, balanceValue: String?, usageValue: String?) {
        credentialActions.save(providerId, balanceValue, usageValue)
    }

    /**
     * 设置页断开某个 provider：清凭据 + 清快照 + 状态回落到「未配置」。
     *
     * 与保存共用同一把在途锁与同一份失败原因；成功后由设置页收起该行并给一次 toast（见 [saveRowState]）。
     */
    fun onClearCredentialsFor(providerId: String) {
        credentialActions.clear(providerId)
    }


    /** 计费模务只影响展示口径，不触发重拉：同一份数据两种排版，没必要多打一次接口。 */
    fun onOllamaPlanChange(mode: OllamaPlanMode) {
        settings.ollamaPlan = mode
        _ollamaPlan.value = mode
    }

    fun onAlertEnabledChange(enabled: Boolean) {
        settings.alertsEnabled = enabled
        // 预警通知目前读自己那份 prefs；两处同时写，避免「设置里关了、通知照发」。
        alertPrefs.enabled = enabled
        _alertEnabled.value = enabled
    }

    fun onLowBalanceChange(unit: QuotaUnit, value: BigDecimal) {
        alertPrefs.setLowBalance(unit, value)
        when (unit) {
            QuotaUnit.CNY -> _lowBalanceCny.value = value
            QuotaUnit.USD -> _lowBalanceUsd.value = value
            else -> Unit
        }
    }

    /** 刷新频率写 [QuotaSettings] 后立即重排闹钟，不然新频率要等下次开机才生效。 */
    fun onRefreshHoursChange(hours: Int) {
        settings.refreshIntervalHours = hours
        _refreshHours.value = settings.refreshIntervalHours
        QuotaScheduler.reschedule(getApplication())
    }

    private fun defaultProviderId(): String = repository.providers().firstOrNull()?.id.orEmpty()

    private fun buildProviders(states: Map<String, QuotaRepository.ProviderState>): List<QuotaProviderInfo> =
        repository.providers().map { provider ->
            val providerState = states[provider.id]
            QuotaProviderInfo(
                id = provider.id,
                displayName = provider.displayName,
                connected = providerState?.let { it.balanceConfigured || it.usageConfigured } ?: false,
                balanceConfigured = providerState?.balanceConfigured ?: false,
                usageConfigured = providerState?.usageConfigured ?: false,
                supportsUsage = provider.supportsUsage,
                supportsModelBreakdown = provider.supportsModelBreakdown,
                balanceKind = provider.balanceCredentialKind,
                usageKind = provider.usageCredentialKind,
                credentialHint = provider.credentialHint,
                credentialHelp = provider.credentialHelp,
                usageCredentialHint = provider.usageCredentialHint,
                usageCredentialHelp = provider.usageCredentialHelp,
            )
        }
}

/**
 * 改了某家的刷新间隔后，是否同步「当前选中家」的前台定时器。
 *
 * 额度页只有一个前台定时器（QuotaScreen 的 LaunchedEffect 读 [QuotaViewModel.refreshMinutes]），
 * 它代表的是**当前选中家**的节奏：在设置页调了别家的间隔却去改写它，会让当前家按别家的频率刷
 * （回归：串改选中家）。
 */
internal fun shouldSyncRefreshMinutes(changedProviderId: String, selectedProviderId: String): Boolean =
    changedProviderId == selectedProviderId

