package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.quota.QuotaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 凭据的写入与断开 —— [QuotaViewModel] 的一条支线，独立成类只为守 300 行硬线。
 *
 * 三件事对设置页都必须**可观察**，否则用户点了按钮看不出发生了什么：
 * - 在途锁（[savingProviderId]）：连点只执行一次，且保存与清除共锁；
 * - 失败原因（[errors]）：按 provider 就地提示，不清空用户的输入；
 * - 完成信号：`busy` 从 providerId 回到 null，设置页据此收行 / 弹 toast（见 [saveRowState]）。
 *
 * 为什么保存与清除共用一把锁：两者改的是同一份凭据与同一条状态，交叉执行会出现
 * 「保存在跑 → 用户确认识别到一半 → 保存成功把刚清掉的 Key 又写回」。
 */
internal class QuotaCredentialActions(
    private val repository: QuotaRepository,
    private val scope: CoroutineScope,
) {

    private val _savingProviderId = MutableStateFlow<String?>(null)

    /** 正在保存 / 清除的 provider；null = 空闲。 */
    val savingProviderId: StateFlow<String?> = _savingProviderId.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 上次操作后的失败原因（按 provider）；重新操作时先清掉自己那条。 */
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** 保存两个凭据（空串不写：与「清除」区分开，清除走 [clear]）。 */
    fun save(providerId: String, balanceValue: String?, usageValue: String?) {
        if (!acquire(providerId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val provider = repository.provider(providerId)
                if (provider == null) {
                    _errors.update { it + (providerId to "provider 未接入") }
                    return@launch
                }
                val failure = runCatching { saveFor(provider, balanceValue, usageValue) }.exceptionOrNull()
                if (failure != null) {
                    _errors.update { it + (providerId to (failure.message ?: "保存失败")) }
                    return@launch
                }
                // 凭据写成功但随后的查询失败（如 Key 无效）：数据层的结论就是这一行的结论
                repository.state(providerId).errorMessage?.let { message ->
                    _errors.update { it + (providerId to message) }
                }
            } finally {
                _savingProviderId.value = null
            }
        }
    }

    /**
     * 断开：清凭据 + 清快照，状态回落到「未配置」。失败时保留原状并就地提示。
     *
     * 成功后也写一次 [errors]？不写 —— 成功由设置页的 toast 表达；这里只负责失败原因。
     */
    fun clear(providerId: String) {
        if (!acquire(providerId)) return
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { repository.clearCredentials(providerId) }.onFailure { error ->
                    _errors.update { it + (providerId to (error.message ?: "清除失败")) }
                }
            } finally {
                _savingProviderId.value = null
            }
        }
    }

    /** 抢占在途锁：已有一行在跑时直接返回 false（连点只执行一次）。 */
    private fun acquire(providerId: String): Boolean {
        if (_savingProviderId.value != null) return false
        _savingProviderId.value = providerId
        _errors.update { it - providerId }
        return true
    }

    private suspend fun saveFor(provider: QuotaProvider, balanceValue: String?, usageValue: String?) =
        saveProviderCredentials(repository, provider, balanceValue, usageValue)
}

/**
 * 保存一家 provider 凭据的唯一实现（设置页与单家额度页两条入口共用）。
 *
 * 两种凭据可能同属一个类型（DeepSeek 的余额与用量共用平台登录令牌）：
 * 这时只写一次，否则「先写令牌、再写空串」会把刚存的令牌抹掉。
 */
internal suspend fun saveProviderCredentials(
    repository: QuotaRepository,
    provider: QuotaProvider,
    balanceValue: String?,
    usageValue: String?,
) {
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
