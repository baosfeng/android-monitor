package com.bsfeng.monitor.ui

// 二级页「关于」：版本（长按解锁调试）/ 检查更新 / 开源许可。
//
// 调试入口已从本页移出（见 ui/SettingsDebugSection.kt）：release 构建里这一页与用户看到的完全一致，
// 不再有「调试模式」混在版本信息里造成的「这是什么」疑问。

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.update.UpdateCheckResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateUiState
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「关于」页正文：一张卡三行（版本 / 检查更新 / 开源许可）。
 */
@Composable
internal fun SettingsAboutSection(rt: SettingsScreenRuntime) {
    val t = rt.t
    AppCard(
        Modifier
            .padding(horizontal = t.space4)
            .testTag("s09-about"),
        padding = 0.dp,
    ) {
        TokenRow(
            title = "版本",
            subtitle = "行为记录 · 本地优先，数据不出设备",
            icon = Icons.Outlined.Info,
            // 长按版本行解锁调试入口（release 构建里解锁后也不显示任何调试面）
            onLongClick = {
                DebugConfig.unlock()
                rt.toast.show("调试入口已解锁：设置里出现「调试与诊断」")
            },
            trailing = { TailNum(BuildConfig.VERSION_NAME) },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "检查更新",
            icon = Icons.Outlined.Refresh,
            onClick = {
                if (rt.updateState.value !is UpdateUiState.Checking) {
                    rt.updateState.value = UpdateUiState.Checking
                    rt.scope.launch {
                        when (val result = withContext(Dispatchers.IO) { UpdateChecker().check() }) {
                            is UpdateCheckResult.Available ->
                                rt.updateState.value = UpdateUiState.Available(result.manifest)
                            is UpdateCheckResult.UpToDate -> {
                                rt.toast.show("已是最新版本 ${result.currentVersionName}")
                                rt.updateState.value = UpdateUiState.Idle
                            }
                            is UpdateCheckResult.Failed -> {
                                rt.toast.show("检查更新失败：${result.message}")
                                rt.updateState.value = UpdateUiState.Idle
                            }
                        }
                    }
                }
            },
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        // 开源许可（原型 s09 缺行）：与原型一致，行本身不带图标、点击出许可文案。
        TokenRow(
            title = "开源许可",
            onClick = { rt.toast.show("开源许可：MIT") },
            trailing = { TailChevron() },
        )
    }
}
