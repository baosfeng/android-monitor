package com.bsfeng.monitor.ui.quota

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.bsfeng.monitor.core.di.serviceContainer

/**
 * ViewModel 工厂：从 `ServiceContainer` 取已装配的 [com.bsfeng.monitor.quota.QuotaRepository]。
 *
 * 不在 VM 里 `QuotaRepository(QuotaProviderRegistry(), ...)` 新建一份：注册表是空的，
 * 页面会永远停在「未接入」。`quotaRepository` 由 `ServiceContainer` 在启动装配时
 * 建好（provider 注册 + Keystore 凭据 + 预警通知）。
 *
 * 单开一个文件只为守 [QuotaViewModel] 的 300 行硬线；对象身份与创建路径刻意不变 ——
 * `viewModel(factory = QuotaViewModelFactory)` 的默认 key 仍按 VM 类名派生，
 * 已展开的行 / 已选中的 provider 不会因为搬文件而丢。
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
