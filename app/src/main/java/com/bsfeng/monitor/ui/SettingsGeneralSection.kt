package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「通用」分组 —— 无障碍服务 / 监控开关 / 电池优化 / 外观 / 通知。负责「服务与系统权限这类与具体功能无关的运行前提」。

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.finance.ui.restoreFocusAfterFrames
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.theme.ThemeRepository
import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.MonitorState

/**
 * 设置「通用」分组 —— 无障碍服务 / 监控开关 / 电池优化 / 外观 / 通知。负责「服务与系统权限这类与具体功能无关的运行前提」。
 */
@Composable
internal fun SettingsGeneralSection(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    state: MonitorState,
    viewModel: MainViewModel,
    isPaused: Boolean,
    themeId: String,
    batteryIgnored: Boolean,
    notificationsOn: Boolean,
    locationOn: Boolean,
    locationPermission: LocationPermissionStatus,
    heartbeatNoticeState: MutableState<String?>,
    localAppearanceState: MutableState<Boolean>,
    onNavigateToAppearance: (() -> Unit)?,
) {
    var heartbeatNotice by heartbeatNoticeState
    var localAppearance by localAppearanceState

// ===== 通用：服务与权限 / 监控 / 耗电 / 外观 / 通知 =====
SectionHeader("通用")
// ② 无障碍服务
Column(Modifier.fillMaxWidth()) {
    GroupLabel("无障碍服务")
    // 运行时长跟随原型 s09 的「已运行 6 小时 12 分」：整句（含「已授权 · 已运行 <时长>」
    // 两段式）统一由 ui/AccessibilityCopy.kt 的 s09AccessibilityStatusText(...) 产出，
    // 本屏只传时长文案、不拼措辞（三段拼接会在窄屏被省略号截掉时长）。
    val a11ySubtitle = s09AccessibilityStatusText(
        enabled = state.accessibilityEnabled,
        running = state.accessibilityServiceRunning,
        uptimeText = formatUptime(processUptimeMs()),
    )
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "无障碍服务",
            subtitle = a11ySubtitle,
            subtitleColor = if (state.accessibilityEnabled) t.successText else t.dangerText,
            icon = Icons.Outlined.Accessibility,
            trailing = {
                AppButton(
                    text = if (state.accessibilityEnabled) "管理" else "去开启",
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
        Spacer(Modifier.height(t.space1))
    }
}

// ③ 监控开关
Column(Modifier.fillMaxWidth()) {
    GroupLabel("监控开关")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "前台应用监控",
            subtitle = "今日已记录 " + state.todayEventCount + " 条事件",
            icon = Icons.Outlined.Speed,
            trailing = {
                AppSwitch(
                    checked = !isPaused,
                    onCheckedChange = {
                        viewModel.togglePause()
                        toast.show(if (isPaused) "已恢复前台应用监控" else "已暂停前台应用监控")
                    },
                    // 锚点落在开关本身：契约 s09-monitor 的触发是「拨开关」而不是点整行
                    modifier = Modifier.testTag("s09-monitor"),
                )
            },
        )
        TokenDivider(first = false)
        // 使用时长统计（原型 s09 缺行）：已确认保留状态角标（不引入 AppSwitch）——
        // 引擎侧 UsageTracker 随无障碍服务的前台轮询无条件运行，没有独立开关与消费点，
        // 角标如实呈现开 / 未授权；做成开关只会是「拨一下就弹回」的假开关。
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
        // 事件上报（原型 s09 缺行）：已确认保留状态角标（不引入 AppSwitch）——
        // 心跳调度真实存在（AlarmReceiver 每分钟 → HealthChecker），上传是二期桩实现
        // （EventReporter.uploadBatch 直接返回 0），没有可切换的消费点；角标如实写
        // 「本地留存」，不宣称"上次上报成功"。
        // 副标题 13 汉字（16 字位）单行可容纳；原「每分钟心跳 · 上传通道二期接入，
        // 事件先本地留存」24 字位会被 TokenRow 的 maxLines = 1 截断成「…事…」。
        // 「事件先本地留存」交给右侧角标承载，副标题只留「心跳存在 + 上传未接入」。
        TokenRow(
            title = "事件上报",
            subtitle = "每分钟心跳 · 上传通道尚未接入",
            icon = Icons.Outlined.Visibility,
            trailing = { Badge(text = "本地留存", tone = BadgeTone.Warn) },
        )
        Spacer(Modifier.height(t.space1))
    }
}

// ④ 电池优化
Column(Modifier.fillMaxWidth()) {
    GroupLabel("电池优化")
    // 进程冻结提示：勾选态与绑定关系都正常、但心跳停了 —— MIUI/HyperOS 把进程
    // 冻进 cgroup 的典型表现（广告跳过与自动记账会一起静默失效）。
    // 判定在 service/HeartbeatWatchdog.kt，踩坑见 docs/踩坑/进程被系统冻结导致无障碍全部失效.md
    heartbeatNotice?.let { notice ->
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            TokenRow(
                title = "进程疑似被冻结",
                subtitle = notice,
                icon = Icons.Outlined.ErrorOutline,
                trailing = {
                    AppButton(
                        text = "去关闭",
                        onClick = {
                            heartbeatNotice = null
                            HeartbeatWatchdog.clearGap(context)
                            // 优先直达本应用的「应用信息」页：省电策略/自启动都在那一页，
                            // 比落到电池优化总列表少三次点击
                            val opened = runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                },
            )
            Spacer(Modifier.height(t.space1))
        }
        Spacer(Modifier.height(t.space2))
    }
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "忽略电池优化",
            subtitle = if (batteryIgnored) "已在系统白名单，后台不会被冻结" else "未加入白名单，息屏后可能被冻结",
            icon = Icons.Outlined.BatteryFull,
            trailing = {
                AppButton(
                    text = "设置",
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    modifier = Modifier.testTag("s09-battery"),
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
        Spacer(Modifier.height(t.space1))
    }
}

// ⑥ 外观（入口显示当前主题名）
Column(Modifier.fillMaxWidth().testTag("s09-appearance")) {
    GroupLabel("外观")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        val themeInteraction = remember { MutableInteractionSource() }
        val haptic = rememberHaptic()
        // 契约 s13-head · 退出后：焦点回本行、滚动位置保持。外观屏是覆盖层（设置页收不到关闭回调），
        // 由 AppearanceFocusReturn 的代次信号衔接；代次在「本行组合期间」新增才回焦。
        val rowFocus = remember { FocusRequester() }
        val returnGeneration = AppearanceFocusReturn.generation
        var seenGeneration by remember { mutableIntStateOf(returnGeneration) }
        LaunchedEffect(returnGeneration) {
            if (!appearanceReturnFocusPending(returnGeneration, seenGeneration)) return@LaunchedEffect
            val generation = returnGeneration
            // 等退场动画把覆盖层卸下再请求；请求在复位之前（见 restoreFocusAfterFrames）
            restoreFocusAfterFrames(
                frames = 2,
                awaitFrame = { withFrameNanos { } },
                requestFocus = { runCatching { rowFocus.requestFocus() }.isSuccess },
                reset = { seenGeneration = generation },
            )
        }
        TokenRow(
            title = "外观与主题",
            modifier = Modifier
                .testTag("s09-goto-theme")
                .focusRequester(rowFocus)
                .pressFeedback(themeInteraction)
                .clickable(
                    interactionSource = themeInteraction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.LIGHT)
                    val open = onNavigateToAppearance
                    if (open != null) open() else localAppearance = true
                },
            subtitle = ThemeRepository.themeIds.size.toString() + " 套浅色主题 · 切换即时生效",
            icon = Icons.Outlined.Palette,
            trailing = { TailNum(ThemeRepository.displayName(themeId)); TailChevron() },
        )
        Spacer(Modifier.height(t.space1))
    }
}


// 通知（系统通知开关；本 App 不发营销推送，只有事件提醒与传送通知）
Column(Modifier.fillMaxWidth()) {
    GroupLabel("通知")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "系统通知",
            subtitle = if (notificationsOn) {
                "已允许：事件提醒与传送通知可送达"
            } else {
                "已关闭：通知被系统拦截"
            },
            icon = Icons.Outlined.NotificationsActive,
            trailing = {
                AppButton(
                    text = "设置",
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
        Spacer(Modifier.height(t.space1))
    }
}
}
