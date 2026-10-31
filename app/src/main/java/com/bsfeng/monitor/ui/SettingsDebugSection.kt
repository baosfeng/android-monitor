package com.bsfeng.monitor.ui

// 二级页「调试与诊断」：调试模式 / 组件与状态演示 / 模拟自动记账浮窗。
//
// 整页由 debugUi 闸（debug 构建 + 关于页长按版本解锁）决定是否出现在一级页入口里；
// 组内三个演示件的可见性再统一走 rememberDemoVisible()（debug + 已解锁 + 调试模式已开）——
// 与一级页顶部的五态切换条同一判据，不再有「两层闸 / 三层闸」并存的两套口径。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic

/**
 * 「调试与诊断」页正文：一张卡 —— 调试模式开关 + 两个演示入口。
 */
@Composable
internal fun SettingsDebugSection(rt: SettingsScreenRuntime) {
    val t = rt.t
    AppCard(
        Modifier
            .padding(horizontal = t.space4)
            .testTag("s09-debug"),
        padding = 0.dp,
    ) {
        TokenRow(
            title = "调试模式",
            subtitle = "记录详细日志与诊断计数",
            icon = Icons.Outlined.BugReport,
            trailing = {
                AppSwitch(rt.debugEnabled, {
                    rt.viewModel.toggleDebug()
                    rt.view.performConfirmHaptic()
                    rt.toast.show(
                        "调试模式" + if (rt.debugEnabled) "已关闭" else "已开启，日志级别 DEBUG"
                    )
                }, contentDescription = "调试模式")
            },
        )
        // 演示件闸门统一：rt.demoVisible = rememberDemoVisible()（debug + 已解锁 + 调试模式已开）。
        // 调试模式关闭时两个入口一并收起 —— 与五态切换条同口径，不出现「切换条没了、入口还在」。
        if (rt.demoVisible) {
            TokenDivider(first = false)
            val demoInteraction = remember { MutableInteractionSource() }
            val demoHaptic = rememberHaptic()
            TokenRow(
                title = "组件与状态演示",
                modifier = Modifier
                    .testTag("s09-goto-demo")
                    .pressFeedback(demoInteraction)
                    .clickable(
                        interactionSource = demoInteraction,
                        indication = LocalIndication.current,
                    ) {
                        demoHaptic(HapticTone.LIGHT)
                        val open = rt.onNavigateToGallery
                        if (open != null) open() else rt.localGalleryState.value = true
                    },
                subtitle = "按钮 / 图表 / 骨架屏 / 空态 / 边界态",
                icon = Icons.Outlined.Widgets,
                trailing = { TailChevron() },
            )
            // 自动记账浮窗调试入口：模拟器里没有支付 App，只能直接构造草稿唤起
            TokenDivider(first = false)
            TokenRow(
                title = "模拟自动记账浮窗",
                subtitle = "好慷在家 · ¥128.00 · 居住·家政（验证浮窗与两级分类）",
                icon = Icons.Outlined.PlayArrow,
                onClick = {
                    val shown =
                        MonitorAccessibilityService.instance?.debugShowAutoBookSheet() == true
                    rt.toast.show(
                        if (shown) "已唤起自动记账浮窗"
                        else "无障碍服务未运行，先在首页开启服务"
                    )
                },
                trailing = { TailChevron() },
            )
        }
    }
}
