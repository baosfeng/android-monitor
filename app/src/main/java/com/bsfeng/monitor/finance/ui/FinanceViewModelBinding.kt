package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的 ViewModel 绑定；职责：在宿主 Activity 作用域内取同一个 FinanceViewModel 实例。

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 取当前宿主 Activity 作用域内的 [FinanceViewModel]。
 *
 * s05 / s07 的入参由 MainActivity 传入（本轮不修改 MainActivity），需要额外派生数据
 * （近 7 天、24 小时分布、加载态）时回退到宿主同一实例；宿主不是 ViewModelStoreOwner 时返回 null。
 */
@Composable
internal fun rememberFinanceViewModel(): FinanceViewModel? {
    val context = LocalContext.current
    return if (context is ViewModelStoreOwner) viewModel<FinanceViewModel>() else null
}
