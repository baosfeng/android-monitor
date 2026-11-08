package com.bsfeng.monitor.totp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.totp.TotpBackup
import com.bsfeng.monitor.totp.TotpClipboard
import com.bsfeng.monitor.totp.TotpQrDecoder
import com.bsfeng.monitor.totp.TotpSettings

/**
 * 验证器 ViewModel 工厂：从 `ServiceContainer` 取已装配的 TotpRepository。
 *
 * 为什么必须走容器：[com.bsfeng.monitor.totp.TotpRepository] 由账户 DAO 与 Keystore 密钥存储组合而成，
 * 在 VM 里现拼等于每个 ViewModel 一份新的 DAO/DB 句柄，也绕过了「装配只在一处」的分层口径。
 * 其余依赖（设置 / 剪贴板 / 解码器 / 备份格式）都是无状态或只包一层 prefs，就地构造没有共享语义。
 *
 * 与 QuotaViewModelFactory 同构：`viewModel(factory = TotpViewModelFactory)` 默认按 VM 类名派生 key，
 * 已展开/已选中的状态不因换装配方式而丢。
 */
object TotpViewModelFactory : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        throw UnsupportedOperationException("TotpViewModel 需要 Application，请通过 CreationExtras 创建")
    }

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
            "缺少 Application：无法定位 ServiceContainer"
        }
        @Suppress("UNCHECKED_CAST")
        return TotpViewModel(
            application = app,
            repository = app.serviceContainer.totpRepository,
            settings = TotpSettings(app),
            clipboard = TotpClipboard(app),
            qrDecoder = TotpQrDecoder(),
            backup = TotpBackup(),
        ) as T
    }
}
