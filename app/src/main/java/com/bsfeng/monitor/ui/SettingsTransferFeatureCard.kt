package com.bsfeng.monitor.ui

// 从 ui/SettingsFeatureSection.kt 拆出：设置「功能 · 传送」卡 —— 传送开关，开启时展开专属设置
// （本机名称 / 后台接收 / 提示音 / 接收目录，见 SettingsTransferCard.kt），关闭时折叠成一句提示。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.ToastHostState
import kotlinx.coroutines.CoroutineScope
import android.content.Context
import android.view.View
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 传送功能卡（Tab：传送；默认关闭）。
 *
 * 关闭时连 ViewModel 都不订阅：不启动服务、不占端口、不做设备发现。
 */
@Composable
internal fun SettingsTransferFeatureCard(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    scope: CoroutineScope,
    transportOn: Boolean,
    transferViewModel: TransferViewModel?,
    setFeature: (Feature, Boolean) -> Unit,
) {
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "传送",
            subtitle = if (transportOn) {
                "局域网互发文件 · 设置在本卡内，传送页只留收发"
            } else {
                "已关闭：不启动传送服务、不占端口、不做设备发现"
            },
            icon = Icons.Outlined.SwapHoriz,
            trailing = { AppSwitch(transportOn, { setFeature(Feature.TRANSPORT, it) }) },
        )
        val transferVm = transferViewModel
        if (transportOn && transferVm != null) {
            TokenDivider(first = false)
            TransferFeatureSettings(transferVm, toast, context, scope, view)
        } else {
            TokenDivider(first = false)
            TokenRow(
                title = "传送设置",
                subtitle = FeatureCatalog.offHint(Feature.TRANSPORT) + "：后台接收、提示音、接收目录",
                icon = Icons.Outlined.Settings,
            )
        }
        Spacer(Modifier.height(t.space1))
    }
}
