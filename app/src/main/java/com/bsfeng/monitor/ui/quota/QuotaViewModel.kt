package com.bsfeng.monitor.ui.quota

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * 「额度」Tab 的状态。
 *
 * 唯一真值源是 [QuotaRepository]（节流 / 退避 / 快照缓存都在数据层），本 VM 只做三件事：
 * 把 Flow 转成界面需要的形态、把点击转成数据层调用、把用户偏好落盘。
 *
 * 口径：界面上的「一家」是**账号实例**（[QuotaProviderInfo.instanceId]），provider 类型只决定
 * 展示名 / 能力（有没有用量接口）。r4 起主屏只有一份账号卡列表，没有「聚合 / 单家」切换。
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

    /** 全部账号的原始状态（主屏账号卡 + 设置页凭据状态都读它）。 */
    val providerStates: StateFlow<Map<String, QuotaRepository.ProviderState>> = repository.states

    /** 账号清单 + 展示名（顺序 = 注册顺序 + 组内序号，主屏照此渲染，不按紧急度重排）。 */
    val providers: StateFlow<List<QuotaProviderInfo>> = repository.states
        .combine(repository.instances) { states, instances -> buildProviders(instances, states) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            buildProviders(repository.instances.value, repository.states.value),
        )

    private val _alertEnabled = MutableStateFlow(settings.alertsEnabled)
    val alertEnabled: StateFlow<Boolean> = _alertEnabled.asStateFlow()

    private val _lowBalanceCny = MutableStateFlow(alertPrefs.lowBalance(QuotaUnit.CNY))
    val lowBalanceCny: StateFlow<BigDecimal> = _lowBalanceCny.asStateFlow()

    private val _lowBalanceUsd = MutableStateFlow(alertPrefs.lowBalance(QuotaUnit.USD))
    val lowBalanceUsd: StateFlow<BigDecimal> = _lowBalanceUsd.asStateFlow()

    /** 恢复事件仓库：与提醒通知器共用同一份 prefs（见 [QuotaAlertTriadStore]）。 */
    private val triadStore = QuotaAlertTriadStore(application)

    /**
     * 主屏「额度提醒」（原型 s18-alerts）。
     *
     * 恢复来自事件记录，低 / 耗尽从当前快照实时推导；[repository.states] 每次刷新都会 emit，
     * 所以通知器写完恢复事件后这里自然重算，不需要额外的 tick 源。
     */
    val alertTriad: StateFlow<QuotaAlertTriad> =
        combine(repository.states, repository.instances) { _, _ -> currentAlertTriad() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, currentAlertTriad())

    private fun currentAlertTriad(): QuotaAlertTriad = buildAlertTriad(
        states = repository.states.value,
        instances = repository.instances.value,
        recoveries = triadStore.recent(),
        lowBalanceThresholds = mapOf(
            QuotaUnit.CNY to alertPrefs.lowBalance(QuotaUnit.CNY),
            QuotaUnit.USD to alertPrefs.lowBalance(QuotaUnit.USD),
        ),
        providerNameOf = { typeId -> repository.provider(typeId)?.displayName ?: typeId },
        nowEpochSecond = System.currentTimeMillis() / 1000,
    )

    private val _refreshHours = MutableStateFlow(settings.refreshIntervalHours)
    val refreshHours: StateFlow<Int> = _refreshHours.asStateFlow()

    /**
     * 主屏前台定时器的间隔（分钟）：取所有账号里**最短**的那个。
     *
     * 主屏一次刷全部账号（不再有「当前选中家」），按最短间隔走才不会让某个设了 1 分钟的账号
     * 被别人的 60 分钟拖着不刷新；数据层还有按账号的节流兜底。
     */
    private val _refreshMinutes = MutableStateFlow(shortestRefreshMinutes())
    val refreshMinutes: StateFlow<Int> = _refreshMinutes.asStateFlow()

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

    // MARK: - 刷新

    /** 进页面拉一次全部账号（repository 内部按实例节流，切 Tab 不会打满限流）。 */
    fun onVisible() = refreshAll(force = false)

    /** 前台定时刷新：受节流约束，间隔没到就直接返回（不 force）。 */
    fun onTick() = refreshAll(force = false)

    /** 点页头刷新：跳过节流，全部账号重拉。 */
    fun onRefresh() = refreshAll(force = true)

    private fun refreshAll(force: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.instances.value.forEach { instance ->
                runCatching { repository.refresh(instance.instanceId, force = force) }
            }
        }
    }

    /**
     * 切某个账号的用量范围（展开区的分段）：只重拉这一家的用量，其它账号不动。
     *
     * 范围本来就按实例存在 [QuotaRepository.ProviderState.range] 里，失败时数据层会回滚。
     */
    fun onRangeChangeFor(instanceId: String, range: QuotaRange) {
        viewModelScope.launch(Dispatchers.IO) { repository.setRange(instanceId, range) }
    }

    /** 某个账号的前台刷新间隔（分钟）。 */
    fun refreshMinutesFor(instanceId: String): Int {
        val typeId = repository.instance(instanceId)?.providerTypeId ?: instanceId
        return settings.refreshIntervalMinutes(instanceId, typeId)
    }

    /** 最短的账号间隔：主屏定时器按它走（见 [refreshMinutes]）。 */
    private fun shortestRefreshMinutes(): Int =
        repository.instances.value.minOfOrNull { instance ->
            settings.refreshIntervalMinutes(instance.instanceId, instance.providerTypeId)
        } ?: 1

    /** 修改某个账号的刷新间隔（分钟）；同时刷新 repository 的节流基准。 */
    fun onRefreshIntervalChange(instanceId: String, minutes: Int) {
        // 点已选中的 chip 不重复落盘（锚点 s19 · 点已选中的 chip 不重复落盘也不发请求）
        if (refreshMinutesFor(instanceId) == minutes) return
        settings.setRefreshIntervalMinutes(instanceId, minutes)
        repository.intervalSeconds = { id ->
            val typeId = repository.instance(id)?.providerTypeId ?: id
            settings.refreshIntervalMinutes(id, typeId) * 60L
        }
        _refreshMinutes.value = shortestRefreshMinutes()
    }

    // MARK: - 凭据

    /** 设置页保存某个账号的两个凭据（不改当前选中项）。 */
    fun onSaveCredentialsFor(instanceId: String, balanceValue: String?, usageValue: String?) {
        credentialActions.save(instanceId, balanceValue, usageValue)
    }

    /**
     * 设置页断开某个账号：清凭据 + 清快照 + 状态回落到「未配置」。
     *
     * 与保存共用同一把在途锁与同一份失败原因；成功后由设置页收起该行并给一次 toast。
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
            onDone(removed)
        }
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

    /**
     * 实例清单 → 界面模型：同类型的多个账号只在这里汇总一次「是否多账号」，
     * 主屏徽标 / 设置页分组都用同一个判据，不会各说各的。
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
