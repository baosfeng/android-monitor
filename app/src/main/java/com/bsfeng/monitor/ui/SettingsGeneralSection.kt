package com.bsfeng.monitor.ui

// 二级页「监控与采集」：前台应用监控 / 使用时长统计（只读）/ 事件上报（只读）/
// 监控时长窗口 / 电池优化 / 系统通知。
//
// 为什么这六项放一起：它们回答的是同一个问题——「采集到底在不在跑、跑的前提缺不缺」，
// 与「开了哪个功能」（功能开关页）不是一件事。只读项用状态角标而不是开关：
// UsageTracker 随无障碍服务无条件轮询、EventReporter.uploadBatch 是二期桩，
// 做成开关只会是「拨一下就弹回」的假开关（设计规范 §5.3）。

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.Chip

/**
 * 「监控与采集」页正文：四张卡（监控开关 / 监控时长窗口 / 电池优化 / 通知），组间只靠卡间隙。
 */
@Composable
internal fun SettingsMonitorSection(rt: SettingsScreenRuntime) {
    val t = rt.t
    val state = rt.state

    // ① 监控开关：前台监控（可拨）+ 使用时长 / 事件上报（只读状态）
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "前台应用监控",
            subtitle = "今日已记录 " + state.todayEventCount + " 条事件",
            icon = Icons.Outlined.Speed,
            trailing = {
                AppSwitch(
                    checked = !rt.isPaused,
                    onCheckedChange = {
                        rt.viewModel.togglePause()
                        rt.toast.show(if (rt.isPaused) "已恢复前台应用监控" else "已暂停前台应用监控")
                    },
                    // 锚点落在开关本身：锚点 s09-monitor 的触发是「拨开关」而不是点整行
                    modifier = Modifier.testTag("s09-monitor"),
                    contentDescription = "前台应用监控",
                )
            },
        )
        TokenDivider(first = false)
        // 状态值放行尾（锚点 s09 页层级规则 5）：只读项不藏进副标题，一眼看到开没开
        TokenRow(
            title = "使用时长统计",
            subtitle = "按应用累计前台时长，用于概览曲线",
            icon = Icons.Outlined.BarChart,
            trailing = {
                Badge(
                    text = if (state.accessibilityEnabled) "已开启" else "待授权",
                    tone = if (state.accessibilityEnabled) BadgeTone.Success else BadgeTone.Warn,
                )
            },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "事件上报",
            subtitle = "每分钟心跳 · 上传通道尚未接入",
            icon = Icons.Outlined.Visibility,
            trailing = { Badge(text = "本地留存", tone = BadgeTone.Warn) },
        )
    }

    // ② 监控时长窗口：值在行尾，chip 组在下方（锚点 s09-window 落在组容器上）
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "监控时长窗口",
            subtitle = "切换 App 后 " + (rt.watchWindowMs / 1000) + " 秒内匹配跳过按钮",
            icon = Icons.Outlined.Timer,
            trailing = { TailNum((rt.watchWindowMs / 1000).toString() + " 秒") },
        )
        // Chip 内置 0.96 + LIGHT，调用点不再补一次触感（否则同一次点击震两下）
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("s09-window")
                .padding(horizontal = t.space4, vertical = t.space2),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            SkipConfig.WINDOW_OPTIONS_MS.forEach { option ->
                Chip(
                    label = (option / 1000).toString() + " 秒",
                    selected = option == rt.watchWindowMs,
                    onClick = {
                        rt.viewModel.setWatchWindowMs(option)
                        rt.toast.show("监控时长窗口：" + (option / 1000) + " 秒")
                    },
                )
            }
        }
    }

    // ③ 电池优化：冻结提示（有缺口才出现）+ 忽略电池优化跳转
    SettingsBatteryCard(rt)

    // ④ 系统通知：跳系统通知页（本 App 不发营销推送，只有事件提醒与传送通知）
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "系统通知",
            subtitle = if (rt.notificationsOn.value) {
                "已允许：事件提醒与传送通知可送达"
            } else {
                "已关闭：通知被系统拦截"
            },
            icon = Icons.Outlined.NotificationsActive,
            trailing = {
                AppButton(
                    text = "设置",
                    onClick = {
                        rt.context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, rt.context.packageName)
                        )
                    },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
}

/**
 * 电池优化卡：进程冻结提示 + 「忽略电池优化」跳转。
 *
 * 冻结提示只在心跳缺口超阈值时出现 —— MIUI/HyperOS 把进程冻进 cgroup 的典型表现是
 * 「勾选态与绑定关系都正常、但心跳停了」（广告跳过与自动记账会一起静默失效）。
 * 判定在 service/HeartbeatWatchdog.kt，踩坑见 docs/踩坑/进程被系统冻结导致无障碍全部失效.md
 */
@Composable
private fun SettingsBatteryCard(rt: SettingsScreenRuntime) {
    val t = rt.t
    rt.heartbeatNoticeState.value?.let { notice ->
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            TokenRow(
                title = "进程疑似被冻结",
                subtitle = notice,
                icon = Icons.Outlined.ErrorOutline,
                trailing = {
                    AppButton(
                        text = "去关闭",
                        onClick = {
                            rt.heartbeatNoticeState.value = null
                            HeartbeatWatchdog.clearGap(rt.context)
                            // 优先直达本应用的「应用信息」页：省电策略/自启动都在那一页，
                            // 比落到电池优化总列表少三次点击
                            val opened = runCatching {
                                rt.context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", rt.context.packageName, null),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                rt.context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                },
            )
        }
    }
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "忽略电池优化",
            subtitle = if (rt.batteryIgnored.value) {
                "已在系统白名单，后台不会被冻结"
            } else {
                "未加入白名单，息屏后可能被冻结"
            },
            icon = Icons.Outlined.BatteryFull,
            trailing = {
                AppButton(
                    text = "设置",
                    onClick = {
                        rt.context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    modifier = Modifier.testTag("s09-battery"),
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
}
