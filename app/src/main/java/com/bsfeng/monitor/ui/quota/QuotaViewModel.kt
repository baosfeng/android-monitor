package com.bsfeng.monitor.ui.quota

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
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
import kotlinx.coroutines.flow.update
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
     * 正在保存凭据 / 查询的 provider（设置页据此把「保存并查询」锁一次）。
     *
     * 同一时刻只有一行展开，所以用单值而不是集合：在途期间不再接受任何一行的保存请求
     * （契约 s19 · 连点只存一次）。
     */
    private val _savingProviderId = MutableStateFlow<String?>(null)
    val savingProviderId: StateFlow<String?> = _savingProviderId.asStateFlow()

    /** 上次保存后就地提示的失败原因（按 provider）；重新保存或保存成功时清掉。 */
    private val _saveErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val saveErrors: StateFlow<Map<String, String>> = _saveErrors.asStateFlow()

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
        // 点已选中的 chip 不重复落盘（契约 s19 · 点已选中的 chip 不重复落盘也不发请求）
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
            saveFor(provider, balanceValue, usageValue)
        }
    }

    /**
     * 设置页保存某个 provider 的两个凭据（不改当前选中项）。
     *
     * 三件事都要给设置页一个可观察的结果：在途锁（连点只存一次）、失败原因（就地提示）、
     * 完成信号（成功后收起展开行，失败则留展开并保留输入，见 [saveRowState]）。
     */
    fun onSaveCredentialsFor(providerId: String, balanceValue: String?, usageValue: String?) {
        if (_savingProviderId.value != null) return
        _savingProviderId.value = providerId
        _saveErrors.update { it - providerId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val provider = repository.provider(providerId)
                if (provider == null) {
                    _saveErrors.update { it + (providerId to "provider 未接入") }
                    return@launch
                }
                val failure = runCatching { saveFor(provider, balanceValue, usageValue) }.exceptionOrNull()
                if (failure != null) {
                    _saveErrors.update { it + (providerId to (failure.message ?: "保存失败")) }
                    return@launch
                }
                // 凭据写成功但随后的查询失败（如 Key 无效）：数据层的结论就是这一行的结论
                repository.state(providerId).errorMessage?.let { message ->
                    _saveErrors.update { it + (providerId to message) }
                }
            } finally {
                _savingProviderId.value = null
            }
        }
    }

    /**
     * 保存凭据。
     *
     * 两种凭据可能同属一个类型（DeepSeek 的余额与用量共用平台登录令牌）：
     * 这时只写一次，否则「先写令牌、再写空串」会把刚存的令牌抹掉。
     */
    private suspend fun saveFor(provider: QuotaProvider, balanceValue: String?, usageValue: String?) {
        val balanceKind = provider.balanceCredentialKind
        val usageKind = provider.usageCredentialKind
        val sameKind = usageKind == balanceKind
        val balance = balanceValue?.trim().orEmpty()
        val usage = usageValue?.trim().orEmpty()
        if (balance.isNotEmpty()) {
            repository.saveCredential(provider.id, balanceKind, balance)
        }
        if (!sameKind && usageKind != null && usage.isNotEmpty()) {
            repository.saveCredential(provider.id, usageKind, usage)
        }
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

/**
 * ViewModel 工厂：从 `ServiceContainer` 取已装配的 [QuotaRepository]。
 *
 * 不在 VM 里 `QuotaRepository(QuotaProviderRegistry(), ...)` 新建一份：注册表是空的，
 * 页面会永远停在「未接入」。`quotaRepository` 由 `ServiceContainer` 在启动装配时
 * 建好（provider 注册 + Keystore 凭据 + 预警通知）。
 */
object QuotaViewModelFactory : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        throw UnsupportedOperationException("QuotaViewModel 需要 Application，请通过 CreationExtras 创建")
    }

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
            "缺少 Application：无法定位 ServiceContainer"
        }
        @Suppress("UNCHECKED_CAST")
        return QuotaViewModel(app, app.serviceContainer.quotaRepository) as T
    }
}
