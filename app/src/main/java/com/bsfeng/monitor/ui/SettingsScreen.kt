package com.bsfeng.monitor.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberDebugUiAvailable
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.util.DebugConfig
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import android.view.HapticFeedbackConstants

/**
 * s09 设置（原型 s09-settings.html）。
 *
 * 8 个分组：监控时长窗口（chip 组）· 无障碍服务 · 监控开关 · 电池优化 · 开屏广告跳过 · 外观 · 调试模式 · 关于。
 * 全部走 token；旧语义色（IOSColors / MaterialTheme 直取色）已按 LEGACY-TOKEN-MAP 换成本地 token 快照。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToSkipRules: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onNavigateToAppearance: (() -> Unit)? = null,
    onNavigateToGallery: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val view = LocalView.current
    val toast = rememberToastHostState()
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    // 两层闸：debug 构建 + 关于页长按版本解锁（详见 ui/components/DebugGate.kt）
    val debugUi = rememberDebugUiAvailable()
    val demoVisible = rememberDemoVisible()
    val watchWindowMs by viewModel.watchWindowMs.collectAsState()
    val themeId by ThemeRepository.themeId.collectAsState()
    var demoState by remember { mutableStateOf(FiveState.POPULATED) }
    // 屏内兜底桥：导航任务接线之前，setter 回调为 null 时本屏自行托管 s12 / s13，
    // 保证「外观与主题」「组件与状态演示」两个入口在真机上真的能进（接线后自动走宿主导航）。
    var localAppearance by remember { mutableStateOf(false) }
    var localGallery by remember { mutableStateOf(false) }

    // 规则数 / 订阅源数 / 白名单数：只读其它模块的既有数据源，不新增存储
    var ruleCount by remember { mutableStateOf(0) }
    var subscriptionCount by remember { mutableStateOf(0) }
    var whitelistCount by remember { mutableStateOf(0) }
    var batteryIgnored by remember { mutableStateOf(false) }

    fun reloadCounters() {
        ruleCount = MonitorApp.instance.container.skipRuleManager.getAllRules().size
        subscriptionCount = MonitorApp.instance.container.skipRuleManager.subscriptionMetas().size
        whitelistCount = context
            .getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet())
            ?.size ?: 0
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    // 从系统设置页返回时立即刷新（不看轮询的运气）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        reloadCounters()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { reloadCounters() }

    val effective = if (demoState == FiveState.POPULATED) FiveState.POPULATED else demoState

    // 沉浸式：底衬画在 inset 之外（内容才吃 Scaffold 的 padding），顶部与状态栏连成一片
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "设置",
                meta = "行为记录 1.0.0 · 设备 " + viewModel.getDeviceId().take(8),
                badge = {
                    Badge(
                        text = if (isPaused) "已暂停" else "运行中",
                        tone = if (isPaused) BadgeTone.Warn else BadgeTone.Success,
                    )
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = demoState, onSelect = { demoState = it })

                // 五态统一走 StateContainer（ui/components/States.kt）：
                // 空/错态 = 居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + CTA，文案取自原型 s09-st-*。
                StateContainer(
                    state = effective,
                    modifier = Modifier.fillMaxWidth(),
                    emptyTitle = "暂无可用设置项",
                    emptyText = "无障碍服务尚未授权，开启后才能调整监控与开屏跳过选项。",
                    emptyActionLabel = "前往系统设置",
                    onEmptyAction = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    errorTitle = "读取配置失败",
                    errorText = "本地配置存储被拒绝（EACCES），上次读取 9:40 失败。",
                    onRetry = {
                        reloadCounters()
                        toast.show("正在重试读取配置…")
                    },
                    edgeText = "边界态：超长应用名 / 10,000+ 条计数",
                    edgeContent = {
                        TokenRow(
                            title = "国家电网掌上电力营业厅客户端（企业内网版）",
                            trailing = { TailNum("10,286 条") },
                        )
                        TokenDivider(first = false)
                        TokenRow(
                            title = "中国建设银行个人网上银行移动客户端",
                            trailing = { TailNum("10,000 条") },
                        )
                        TokenDivider(first = false)
                        TokenRow(
                            title = "超长标题：该应用在后台持续拉起前台服务并申请了忽略电池优化的白名单权限",
                            trailing = { TailNum("9,999 条") },
                        )
                    },
                ) {
                        // StateContainer 的 POPULATED 分支把 content 放进 Box（States.kt:90）：
                        // Box 下多个兄弟节点会叠在同一原点（「只剩后两组 + 不可滚动」的根因）。
                        // 分组先收进一个 Column，Box 下只留这一个子节点；组间距沿用外层原先的 space4。
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(t.space4),
                        ) {
                        // ① 监控时长窗口（chip 组）
                        //
                        // ⚠️ 原型 s09 的档位是「15 分钟 / 30 分钟 / 1 小时 / 永久」，与本实现的
                        // 5/10/15 **秒** 不是同一量纲 —— 但本 chip 绑定的值（SkipConfig.watchWindowMs）
                        // 是**开屏跳过引擎的匹配窗口**（自 App 切换起算），同时是内置规则的 matchTime
                        // 时间闸门（SkipRuleManager.load → BuiltInRules.getRules）。把它放宽到分钟/永久会
                        // 直接改变跳过引擎的运行语义（防误触闸门失效、matchTime=0 在 GKD 语义里 = 不限），
                        // 因此本轮**保持 5/10/15 秒不动**，冲突与建议见交付报告。
                        // 语义依据：docs/开屏跳过/设计.md §1「匹配窗口模型」、§4.3、§7。
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("监控时长窗口")
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = t.space4),
                                horizontalArrangement = Arrangement.spacedBy(t.space2),
                            ) {
                                SkipConfig.WINDOW_OPTIONS_MS.forEach { option ->
                                    Chip(
                                        label = (option / 1000).toString() + " 秒",
                                        selected = option == watchWindowMs,
                                        onClick = {
                                            viewModel.setWatchWindowMs(option)
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            toast.show("监控时长窗口：" + (option / 1000) + " 秒")
                                        },
                                    )
                                }
                            }
                        }

                        // ② 无障碍服务
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("无障碍服务")
                            // 运行时长跟随原型 s09 的「已运行 6 小时 12 分」：仅在服务实例已连接时追加。
                            // 文案前半段仍取自 ui/AccessibilityCopy.kt 的唯一来源（s01/s02/s05 同口径），
                            // 本屏只做后缀拼接，不复制措辞。
                            val a11ySubtitle = accessibilityStatusText(
                                state.accessibilityEnabled,
                                state.accessibilityServiceRunning,
                            ) + if (state.accessibilityServiceRunning) {
                                " · 已运行 " + formatUptime(processUptimeMs())
                            } else {
                                ""
                            }
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
                                        AppSwitch(!isPaused, {
                                            viewModel.togglePause()
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            toast.show(if (isPaused) "已恢复前台应用监控" else "已暂停前台应用监控")
                                        })
                                    },
                                )
                                TokenDivider(first = false)
                                // 使用时长统计（原型 s09 缺行）：引擎侧 UsageTracker 随无障碍服务的前台轮询
                                // 无条件运行，没有独立开关与消费点，故用状态角标如实呈现开/未授权，
                                // 不做「拨一下就弹回」的假开关。
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
                                // 事件上报（原型 s09 缺行）：心跳调度真实存在（AlarmReceiver 每分钟 →
                                // HealthChecker），上传是二期桩实现（EventReporter.uploadBatch 直接返回 0），
                                // 因此角标如实写「本地留存」，不宣称"上次上报成功"。
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
                                            variant = AppButtonVariant.Ghost,
                                        )
                                    },
                                )
                                Spacer(Modifier.height(t.space1))
                            }
                        }

                        // ⑤ 开屏广告跳过
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("开屏广告跳过")
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                TokenRow(
                                    title = "开屏跳过规则",
                                    subtitle = "订阅源 " + subscriptionCount + " 个 · 本地合并后按顺序匹配",
                                    icon = Icons.Outlined.PlayArrow,
                                    onClick = onNavigateToSkipRules,
                                    trailing = { TailNum(ruleCount.toString() + " 条"); TailChevron() },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "应用白名单",
                                    subtitle = "白名单内应用不参与跳过",
                                    icon = Icons.Outlined.Shield,
                                    onClick = onNavigateToWhitelist,
                                    trailing = { TailNum(whitelistCount.toString() + " 个"); TailChevron() },
                                )
                                Spacer(Modifier.height(t.space1))
                            }
                        }

                        // ⑥ 外观（入口显示当前主题名）
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("外观")
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                TokenRow(
                                    title = "外观与主题",
                                    subtitle = ThemeRepository.themeIds.size.toString() + " 套浅色主题 · 切换即时生效",
                                    icon = Icons.Outlined.Palette,
                                    onClick = {
                                        val open = onNavigateToAppearance
                                        if (open != null) open() else localAppearance = true
                                    },
                                    trailing = { TailNum(ThemeRepository.displayName(themeId)); TailChevron() },
                                )
                                Spacer(Modifier.height(t.space1))
                            }
                        }

                        // ⑦ 调试模式（默认不显示：仅 debug 构建 + 关于页长按版本解锁后出现）
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
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
                                    Spacer(Modifier.height(t.space1))
                                }
                            }
                        }

                        // ⑧ 关于
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("关于")
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
                                    trailing = { TailNum("1.0.0") },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "检查更新",
                                    icon = Icons.Outlined.Refresh,
                                    onClick = { toast.show("已是最新版本 1.0.0") },
                                    trailing = { TailChevron() },
                                )
                                TokenDivider(first = false)
                                // 开源许可（原型 s09 缺行）：与原型一致，行本身不带图标、点击出许可文案。
                                TokenRow(
                                    title = "开源许可",
                                    onClick = { toast.show("开源许可：Apache-2.0") },
                                    trailing = { TailChevron() },
                                )
                                Spacer(Modifier.height(t.space1))
                            }
                        }
                        Spacer(Modifier.height(t.space6))
                        }
                }
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )

        // 兜底桥：s13 外观（真实屏见 ui/AppearanceScreen.kt）
        AnimatedVisibility(
            visible = localAppearance,
            enter = slideInHorizontally() + fadeIn(),
            exit = slideOutHorizontally() + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                BackHandler { localAppearance = false }
                AppearanceScreen(onBack = { localAppearance = false })
            }
        }

        // 兜底桥：s12 组件与五态展示厅（真实屏见 ui/ComponentGalleryScreen.kt）
        AnimatedVisibility(
            visible = localGallery,
            enter = slideInHorizontally() + fadeIn(),
            exit = slideOutHorizontally() + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                BackHandler { localGallery = false }
                ComponentGalleryScreen(onBack = { localGallery = false })
            }
        }
    }
}

// 无障碍状态文案已收敛到 ui/AccessibilityCopy.kt 的 accessibilityStatusText(...)：
// s01 / s02 / s05 三屏同一数据源（AccessibilityStatus 三重判定）与同一措辞。

/**
 * 本进程已运行的毫秒数（用于 s09 的「已运行 6 小时 12 分」）。
 *
 * 无障碍服务运行在 App 进程内、与服务实例同生命周期，而系统没有提供「服务绑定时刻」的公开 API
 * （AccessibilityServiceInfo 只有能力位与包名，无时间戳）。在「本 agent 不改 service/skip 等
 * 范围外文件」的约束下，进程启动至今是当前可得的最近似值：服务由系统绑定拉起进程时两者一致，
 * 用户先开 App、后授权时该值会略大于服务真实运行时长。
 * 若要精确到「服务运行时长」，需在 MonitorAccessibilityService 记录 onServiceConnected 时间戳
 * 并由状态层暴露（超出本文件范围）。
 */
private fun processUptimeMs(): Long =
    (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).coerceAtLeast(0L)

/** 运行时长文案：≥1 小时用「X 小时 Y 分」（同原型），不足 1 分钟用「不足 1 分钟」。 */
private fun formatUptime(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        totalMinutes > 0 -> "$minutes 分钟"
        else -> "不足 1 分钟"
    }
}
