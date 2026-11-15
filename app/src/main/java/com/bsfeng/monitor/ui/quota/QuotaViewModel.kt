package com.bsfeng.monitor.ui.quota

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
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
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * 「额度」Tab 的状态。
 *
 * 唯一真值源是 [QuotaRepository]（节流 / 退避 / 快照缓存都在数据层），本 VM 只做三件事：
 * 把 Flow 转成界面需要的形态、把点击转成数据层调用、把用户偏好落盘。
 *
 * 口径：界面上的「一家」现在是**账号实例**（[QuotaProviderInfo.instanceId]），
 * provider 类型只决定卡片排版与分组标题。选中态、刷新间隔、凭据操作全部按实例键走。
 */
class QuotaViewModel(
    application: Application,
    private val repository: QuotaRepository,
    private val settings: QuotaSettings = QuotaSettings(application),
) : AndroidViewModel(application) {

    /** 预警开关与阈值复用预警通知自己的 prefs（见 [QuotaAlertPrefs] 的说明）。 */
    private val alertPrefs = QuotaAlertPrefs(application)

    init {
        // 节流基准来自设置页的「每账号刷新间隔」，默认走该类型的默认值
        repository.intervalSeconds = { instanceId ->
            val typeId = repository.instance(instanceId)?.providerTypeId ?: instanceId
            settings.refreshIntervalMinutes(instanceId, typeId) * 60L
        }
    }

    private val _selectedProviderId = MutableStateFlow(defaultInstanceId())
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()

    /** 全部账号的原始状态（设置页要显示每个账号的凭据配置情况）。 */
    val providerStates: StateFlow<Map<String, QuotaRepository.ProviderState>> = repository.states

    /** chip / 设置列表用；实例清单或凭据变化时自动重算。 */
    val providers: StateFlow<List<QuotaProviderInfo>> = repository.states
        .combine(repository.instances) { states, instances -> buildProviders(instances, states) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            buildProviders(repository.instances.value, repository.states.value),
        )

    /** 当前选中账号的状态（额度页主体）。 */
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

    /** 聚合视图（true = 榜单，一实例一行）还是单家视图；偏好落盘，见 [QuotaSettings.aggregateMode]。 */
    private val _aggregateMode = MutableStateFlow(settings.aggregateMode)
    val aggregateMode: StateFlow<Boolean> = _aggregateMode.asStateFlow()

    /** 聚合榜单 / 灰行 / 更新时间，逻辑全在 [QuotaAggregateDelegate] 与 [buildAggregateSnapshot]。 */
    private val aggregateDelegate = QuotaAggregateDelegate(repository, viewModelScope)
    val aggregateSnapshot: StateFlow<QuotaAggregateSnapshot> = aggregateDelegate.snapshot

    /** 当前选中账号的前台刷新间隔（分钟），改动后立即生效。 */
    private val _refreshMinutes = MutableStateFlow(
        settings.refreshIntervalMinutes(
            repository.instances.value.firstOrNull()?.instanceId.orEmpty(),
            repository.instances.value.firstOrNull()?.providerTypeId.orEmpty(),
        ),
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

    /** 正在保存 / 清除凭据的账号（设置页据此把按钮锁一次）。 */
    val savingProviderId: StateFlow<String?> = credentialActions.savingProviderId

    /** 上次操作后就地提示的失败原因（按实例）；重新保存或成功时清掉。 */
    val saveErrors: StateFlow<Map<String, String>> = credentialActions.errors

    /** 进页面时拉一次（repository 内部按实例节流，切 Tab 不会打满限流）。 */
    fun onProviderVisible() {
        val id = _selectedProviderId.value
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(id) }
    }

    fun onRefresh() {
        val id = _selectedProviderId.value
        // 聚合视图没有「当前选中账号」的概念：刷全部（非 force，节流在数据层）
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

    /** 从聚合榜单点进某个账号：切到单家视图并选中它，让下钻落在同一个账号的详情上。 */
    fun onOpenProviderFromAggregate(instanceId: String) {
        onAggregateModeChange(false)
        onSelectProvider(instanceId)
    }

    /** 进入聚合视图时拉一次：榜单要的是全部账号，只拉当前选中账号会让其他行停在旧值。 */
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

    /** 某个账号的前台刷新间隔（分钟）。 */
    fun refreshMinutesFor(instanceId: String): Int {
        val typeId = repository.instance(instanceId)?.providerTypeId ?: instanceId
        return settings.refreshIntervalMinutes(instanceId, typeId)
    }

    /** 修改某个账号的刷新间隔（分钟）；同时刷新 repository 的节流基准。 */
    fun onRefreshIntervalChange(instanceId: String, minutes: Int) {
        // 点已选中的 chip 不重复落盘（锚点 s19 · 点已选中的 chip 不重复落盘也不发请求）
        if (refreshMinutesFor(instanceId) == minutes) return
        settings.setRefreshIntervalMinutes(instanceId, minutes)
        repository.intervalSeconds = { id ->
            val typeId = repository.instance(id)?.providerTypeId ?: id
            settings.refreshIntervalMinutes(id, typeId) * 60L
        }
        // 只同步「当前选中账号」的前台定时器：改了别的账号的间隔不该重置当前的节奏
        // （节奏由 QuotaScreen 的 LaunchedEffect 按 refreshMinutes 重启，见 shouldSyncRefreshMinutes）
        if (shouldSyncRefreshMinutes(instanceId, _selectedProviderId.value)) {
            _refreshMinutes.value = refreshMinutesFor(instanceId)
        }
    }

    fun onSelectProvider(instanceId: String) {
        if (_selectedProviderId.value == instanceId) return
        _refreshMinutes.value = refreshMinutesFor(instanceId)
        _selectedProviderId.value = instanceId
        viewModelScope.launch(Dispatchers.IO) { repository.refresh(instanceId) }
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
            val provider = repository.providerOf(id) ?: return@launch
            saveProviderCredentials(repository, id, provider, balanceValue, usageValue)
        }
    }

    /**
     * 设置页保存某个账号的两个凭据（不改当前选中项）。
     *
     * 三件事都要给设置页一个可观察的结果：在途锁（连点只存一次）、失败原因（就地提示）、
     * 完成信号（成功后收起展开行，失败则留展开并保留输入，见 [saveRowState]）。
     */
    fun onSaveCredentialsFor(instanceId: String, balanceValue: String?, usageValue: String?) {
        credentialActions.save(instanceId, balanceValue, usageValue)
    }

    /**
     * 设置页断开某个账号：清凭据 + 清快照 + 状态回落到「未配置」。
     *
     * 与保存共用同一把在途锁与同一份失败原因；成功后由设置页收起该行并给一次 toast（见 [saveRowState]）。
     */
    fun onClearCredentialsFor(instanceId: String) {
        credentialActions.clear(instanceId)
    }

    // MARK: - 账号实例

    /** 在某类型下新增一个账号；返回新实例（类型未注册 / 超上限为 null）。 */
    fun onAddAccount(providerTypeId: String): String? =
        repository.addInstance(providerTypeId)?.instanceId

    /** 改账号名（空串 = 回到默认名）。 */
    fun onRenameAccount(instanceId: String, label: String) =
        repository.renameInstance(instanceId, label)

    /** 删账号：清它自己的凭据 + 快照 + 状态；只剩一个账号时拒绝并返回 false。 */
    fun onDeleteAccount(instanceId: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = repository.deleteInstance(instanceId)
            if (removed && _selectedProviderId.value == instanceId) {
                _selectedProviderId.value = defaultInstanceId()
            }
            onDone(removed)
        }
    }

    /** 计费模式只影响展示口径，不触发重拉：同一份数据两种排版，没必要多打一次接口。 */
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

    private fun defaultInstanceId(): String =
        repository.instances.value.firstOrNull()?.instanceId
            ?: repository.providers().firstOrNull()?.id.orEmpty()

    /**
     * 实例清单 → 界面模型：同类型的多个账号只在这里汇总一次「是否多账号」，
     * chip / 榜单 / 设置页分组都用同一个判据，不会各说各的。
     */
    private fun buildProviders(
        instances: List<QuotaInstance>,
        states: Map<String, QuotaRepository.ProviderState>,
    ): List<QuotaProviderInfo> {
        val byType = instances.groupBy { it.providerTypeId }
        return instances.mapNotNull { instance ->
            val provider = repository.provider(instance.providerTypeId) ?: return@mapNotNull null
            val sameType = byType[instance.providerTypeId].orEmpty()
            QuotaProviderInfo(
                instanceId = instance.instanceId,
                providerTypeId = instance.providerTypeId,
                displayName = QuotaInstances.accountName(instance, provider.displayName, sameType.size > 1),
                typeDisplayName = provider.displayName,
                label = instance.label,
                multiAccount = sameType.size > 1,
                connected = states[instance.instanceId]?.let { it.balanceConfigured || it.usageConfigured }
                    ?: false,
                balanceConfigured = states[instance.instanceId]?.balanceConfigured ?: false,
                usageConfigured = states[instance.instanceId]?.usageConfigured ?: false,
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
}

/**
 * 改了某个账号的刷新间隔后，是否同步「当前选中账号」的前台定时器。
 *
 * 额度页只有一个前台定时器（QuotaScreen 的 LaunchedEffect 读 [QuotaViewModel.refreshMinutes]），
 * 它代表的是**当前选中账号**的节奏：在设置页调了别的账号的间隔却去改写它，会让当前账号按别人的频率刷
 * （回归：串改选中账号）。
 */
internal fun shouldSyncRefreshMinutes(changedInstanceId: String, selectedInstanceId: String): Boolean =
    changedInstanceId == selectedInstanceId
