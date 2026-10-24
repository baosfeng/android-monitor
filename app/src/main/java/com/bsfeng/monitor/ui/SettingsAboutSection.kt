package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「关于」分组 —— 版本（长按解锁调试）/ 检查更新 / 开源许可 / 调试模式 / 组件与状态演示入口。负责「这个 App 是什么版本、能自检什么」。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.update.UpdateCheckResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateUiState
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.ui.components.ToastHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.MainViewModel

/**
 * 设置「关于」分组 —— 版本（长按解锁调试）/ 检查更新 / 开源许可 / 调试模式 / 组件与状态演示入口。负责「这个 App 是什么版本、能自检什么」。
 */
@Composable
internal fun SettingsAboutSection(
    t: DesignTokens,
    view: View,
    toast: ToastHostState,
    scope: CoroutineScope,
    viewModel: MainViewModel,
    updateState: MutableStateFlow<UpdateUiState>,
    debugUi: Boolean,
    demoVisible: Boolean,
    debugEnabled: Boolean,
    localGalleryState: MutableState<Boolean>,
    onNavigateToGallery: (() -> Unit)?,
) {
    var localGallery by localGalleryState

// ===== 关于：版本 / 更新 / 调试入口（调试入口受 DebugConfig 两层闸控制）=====
SectionHeader("关于")
// ⑩ 关于（分组标题已由上面的 SectionHeader 承担，卡内不再重复）
Column(Modifier.fillMaxWidth()) {
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "版本",
            subtitle = "行为记录 · 本地优先，数据不出设备",
            icon = Icons.Outlined.Info,
            // 长按版本行解锁调试入口（release 构建里解锁后也不显示任何调试面）
            onLongClick = {
                DebugConfig.unlock()
                toast.show("调试入口已解锁：设置里出现「调试模式」")
            },
            trailing = { TailNum(BuildConfig.VERSION_NAME) },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "检查更新",
            icon = Icons.Outlined.Refresh,
            onClick = {
                if (updateState.value !is UpdateUiState.Checking) {
                    updateState.value = UpdateUiState.Checking
                    scope.launch {
                        when (val result = withContext(Dispatchers.IO) { UpdateChecker().check() }) {
                            is UpdateCheckResult.Available ->
                                updateState.value = UpdateUiState.Available(result.manifest)
                            is UpdateCheckResult.UpToDate -> {
                                toast.show("已是最新版本 ${result.currentVersionName}")
                                updateState.value = UpdateUiState.Idle
                            }
                            is UpdateCheckResult.Failed -> {
                                toast.show("检查更新失败：${result.message}")
                                updateState.value = UpdateUiState.Idle
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
            onClick = { toast.show("开源许可：MIT") },
            trailing = { TailChevron() },
        )
        Spacer(Modifier.height(t.space1))
    }
}
// ⑨ 调试模式（默认不显示：仅 debug 构建 + 关于页长按版本解锁后出现）
if (debugUi) {
    Column(Modifier.fillMaxWidth()) {
        GroupLabel("调试模式")
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            TokenRow(
                title = "调试模式",
                subtitle = "记录详细日志与诊断计数",
                icon = Icons.Outlined.BugReport,
                trailing = {
                    AppSwitch(debugEnabled, {
                        viewModel.toggleDebug()
                        view.performConfirmHaptic()
                        toast.show("调试模式" + if (debugEnabled) "已关闭" else "已开启，日志级别 DEBUG")
                    })
                },
            )
            // 组件与状态演示屏 = 演示件：debug 构建 + 已解锁 + 调试模式已开才出现
            if (demoVisible) {
                TokenDivider(first = false)
                TokenRow(
                    title = "组件与状态演示",
                    subtitle = "按钮 / 图表 / 骨架屏 / 空态 / 边界态",
                    icon = Icons.Outlined.Widgets,
                    onClick = {
                        val open = onNavigateToGallery
                        if (open != null) open() else localGallery = true
                    },
                    trailing = { TailChevron() },
                )
            }
            // 自动记账浮窗调试入口：模拟器里没有支付 App，只能直接构造草稿唤起；
            // 走 debugUi 闸（debug 构建 + 已解锁），release 构建恒不显示。
            TokenDivider(first = false)
            TokenRow(
                title = "模拟自动记账浮窗",
                subtitle = "好慷在家 · ¥128.00 · 居住·家政（验证浮窗与两级分类）",
                icon = Icons.Outlined.PlayArrow,
                onClick = {
                    val shown =
                        MonitorAccessibilityService.instance?.debugShowAutoBookSheet() == true
                    toast.show(
                        if (shown) "已唤起自动记账浮窗"
                        else "无障碍服务未运行，先在首页开启服务"
                    )
                },
                trailing = { TailChevron() },
            )
            Spacer(Modifier.height(t.space1))
        }
    }
}
}
