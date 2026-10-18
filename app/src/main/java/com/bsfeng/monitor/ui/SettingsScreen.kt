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
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import com.bsfeng.monitor.autobook.AutoBookConfig
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
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.R
import com.bsfeng.monitor.transfer.service.TransferTileService
import com.bsfeng.monitor.transfer.service.TransferTileState
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/**
 * s09 设置（原型 s09-settings.html）。
 *
 * 9 个分组：监控时长窗口（chip 组）· 无障碍服务 · 自动记账 · 监控开关 · 电池优化 · 开屏广告跳过 · 外观 ·
 * 局域网互传 · 调试模式 · 关于。
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
    val scope = rememberCoroutineScope()
    val transferViewModel: TransferViewModel = viewModel()
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
                        // 已确认保留 5/10/15 **秒**，不采用原型 s09 的「15 分钟 / 30 分钟 / 1 小时 / 永久」
                        // 档位：本 chip 绑定的值（SkipConfig.watchWindowMs）优先服务**开屏跳过引擎的匹配窗口**
                        // 语义 —— 自 App 切换起算，同时是内置规则的 matchTime 时间闸门
                        // （SkipRuleManager.load → BuiltInRules.getRules），放宽到分钟 / 永久会改变引擎行为
                        // （防误触闸门失效；matchTime=0 在 GKD 语义里 = 不限）。
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

                        // 自动记账：跟随无障碍服务；开关落盘 monitor_prefs，无障碍服务侧读取即时生效
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("自动记账")
                            val autoBookEnabled by AutoBookConfig.enabledFlow.collectAsState()
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                TokenRow(
                                    title = "支付后弹窗确认",
                                    // 副标题按 TokenRow 单行容量写短（16 字位上限），过长会被省略号截断
                                    subtitle = if (autoBookEnabled) {
                                        "识别支付页，确认后入账"
                                    } else {
                                        "已关闭：不识别、不弹窗"
                                    },
                                    icon = Icons.Outlined.Payments,
                                    trailing = {
                                        AppSwitch(autoBookEnabled, { enabled ->
                                            AutoBookConfig.setEnabled(context, enabled)
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            toast.show(
                                                if (enabled) "已开启支付后弹窗记账" else "已关闭支付后弹窗记账"
                                            )
                                        })
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

                        // ⑦ 局域网互传（提示音 / 免配对窗口 / 体积阈值 / 接收目录）
                        // 传送页只留「可被发现 / 附近设备 / 进行中 / 最近完成」，所有设置收敛到这里。
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("局域网互传")
                            val sound by transferViewModel.soundSettings.collectAsState()
                            val windowState by transferViewModel.openPairing.collectAsState()
                            val thresholdMb by transferViewModel.autoApproveThresholdMb.collectAsState()
                            val stats by transferViewModel.receivedStats.collectAsState()
                            var confirmClear by remember { mutableStateOf(false) }
                            var windowLeftMs by remember { mutableStateOf(windowState.remainingMs()) }

                            LaunchedEffect(windowState) {
                                while (true) {
                                    windowLeftMs = windowState.remainingMs()
                                    if (windowLeftMs <= 0L) break
                                    delay(1_000L)
                                }
                            }
                            // 进设置页即刷新接收目录占用（MediaStore 查询放 IO）
                            LifecycleResumeEffect(Unit) {
                                scope.launch { withContext(Dispatchers.IO) { transferViewModel.refreshReceivedStats() } }
                                onPauseOrDispose { }
                            }

                            // 加入磁贴：API 33+ 一键请求，低版本给文案引导（系统不允许静默添加）
                            fun requestTile() {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    toast.show("下拉通知栏 → 编辑 → 添加「局域网互传」")
                                    return
                                }
                                runCatching {
                                    val sbm = context.getSystemService(StatusBarManager::class.java)
                                    sbm?.requestAddTileService(
                                        ComponentName(context, TransferTileService::class.java),
                                        TransferTileState.LABEL,
                                        Icon.createWithResource(context, R.drawable.ic_transfer_tile),
                                        context.mainExecutor,
                                    ) { result ->
                                        toast.show(
                                            when (result) {
                                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到快捷设置"
                                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已在快捷设置里"
                                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "已取消添加"
                                                else -> "添加失败（$result），可手动从「编辑」里添加"
                                            },
                                        )
                                    }
                                }.onFailure { toast.show("添加失败，请到通知栏「编辑」里手动添加") }
                            }

                            val backgroundReceive by transferViewModel.backgroundReceive.collectAsState()
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                TokenRow(
                                    title = "后台接收",
                                    subtitle = if (backgroundReceive) {
                                        "常驻前台服务 · 通知显示在线设备"
                                    } else {
                                        "仅 App 可见时运行 · 默认关闭"
                                    },
                                    icon = Icons.Outlined.Notifications,
                                    trailing = {
                                        AppSwitch(backgroundReceive, { enabled ->
                                            transferViewModel.setBackgroundReceive(enabled)
                                            view.performConfirmHaptic()
                                            toast.show(
                                                if (enabled) "已开启后台接收（常驻通知）" else "已关闭后台接收（仅 App 内可见）"
                                            )
                                        })
                                    },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "通知栏磁贴",
                                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        "一键添加到快捷设置，下拉即开关"
                                    } else {
                                        "下拉通知栏 → 编辑 → 添加「局域网互传」"
                                    },
                                    icon = Icons.Outlined.SwapHoriz,
                                    trailing = {
                                        AppButton(
                                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "添加" else "说明",
                                            onClick = {
                                                view.performConfirmHaptic()
                                                requestTile()
                                            },
                                            variant = AppButtonVariant.Ghost,
                                        )
                                    },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "传输完成提示",
                                    subtitle = "系统通知音 + 震动",
                                    icon = Icons.Outlined.Notifications,
                                    trailing = {
                                        AppSwitch(sound.complete, { enabled ->
                                            transferViewModel.setSound(enabled, TransferViewModel.SoundKind.COMPLETE)
                                            view.performConfirmHaptic()
                                            toast.show(if (enabled) "已开启传输完成提示" else "已关闭传输完成提示")
                                        })
                                    },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "传输失败提示",
                                    subtitle = "提示音 + 震动（默认关）",
                                    icon = Icons.Outlined.Notifications,
                                    trailing = {
                                        AppSwitch(sound.failure, { enabled ->
                                            transferViewModel.setSound(enabled, TransferViewModel.SoundKind.FAILURE)
                                            view.performConfirmHaptic()
                                        })
                                    },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "新设备出现提示",
                                    subtitle = "仅震动（默认关）",
                                    icon = Icons.Outlined.Notifications,
                                    trailing = {
                                        AppSwitch(sound.newDevice, { enabled ->
                                            transferViewModel.setSound(enabled, TransferViewModel.SoundKind.NEW_DEVICE)
                                            view.performConfirmHaptic()
                                        })
                                    },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "免配对码窗口",
                                    subtitle = if (windowState.enabled) {
                                        "剩余 " + TransferFormat.windowRemaining(windowLeftMs) + " · 到期自动回到需配对"
                                    } else {
                                        "未开启：未配对设备需输入 4 位 PIN"
                                    },
                                    subtitleColor = if (windowLeftMs > 0L) t.successText else t.muted,
                                    icon = Icons.Outlined.SwapHoriz,
                                    trailing = {
                                        AppSwitch(windowState.enabled, { enabled ->
                                            transferViewModel.setOpenPairingWindow(enabled)
                                            view.performConfirmHaptic()
                                            toast.show(
                                                if (enabled) {
                                                    "免配对窗口已开启：" + windowState.minutes + " 分钟内免 PIN"
                                                } else {
                                                    "已关闭免配对窗口"
                                                }
                                            )
                                        })
                                    },
                                )
                                // 窗口时长：开启时切换会以新时长重新计时
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = t.space4, vertical = t.space2),
                                    horizontalArrangement = Arrangement.spacedBy(t.space2),
                                ) {
                                    PairingPolicy.WINDOW_MINUTES.forEach { minutes ->
                                        Chip(
                                            label = minutes.toString() + " 分钟",
                                            selected = windowState.minutes == minutes,
                                            onClick = {
                                                transferViewModel.setOpenPairingMinutes(minutes)
                                                view.performConfirmHaptic()
                                            },
                                        )
                                    }
                                }
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "小于 N MB 免配对码",
                                    subtitle = if (thresholdMb > 0) {
                                        "≤ " + TransferFormat.threshold(thresholdMb) + " 的文件直接接收"
                                    } else {
                                        "关闭：一律需要 PIN（默认）"
                                    },
                                    icon = Icons.Outlined.SwapHoriz,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = t.space4, vertical = t.space2),
                                    horizontalArrangement = Arrangement.spacedBy(t.space2),
                                ) {
                                    PairingPolicy.THRESHOLD_OPTIONS_MB.forEach { mb ->
                                        Chip(
                                            label = TransferFormat.threshold(mb),
                                            selected = thresholdMb == mb,
                                            onClick = {
                                                transferViewModel.setAutoApproveThresholdMb(mb)
                                                view.performConfirmHaptic()
                                                toast.show(
                                                    if (mb == 0) "已关闭体积阈值" else "≤ ${TransferFormat.threshold(mb)} 的文件免配对"
                                                )
                                            },
                                        )
                                    }
                                }
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "接收目录",
                                    subtitle = if (stats.isEmpty) {
                                        "暂无已接收文件"
                                    } else {
                                        "占用 " + TransferFormat.fileSize(stats.totalBytes) + " · 共 " + stats.count + " 个"
                                    },
                                    icon = Icons.Outlined.Delete,
                                    trailing = {
                                        AppButton(
                                            text = "清理",
                                            onClick = {
                                                confirmClear = true
                                                view.performConfirmHaptic()
                                            },
                                            variant = AppButtonVariant.Ghost,
                                        )
                                    },
                                )
                                Spacer(Modifier.height(t.space1))
                            }

                            ConfirmDialog(
                                visible = confirmClear,
                                title = "清理接收目录",
                                message = "将删除「Downloads/局域网互传/」下的 " + stats.count +
                                    " 个文件，并移除对应的接收记录。此操作不可撤销。",
                                confirmLabel = "删除",
                                dismissLabel = "取消",
                                onDismiss = { confirmClear = false },
                                onConfirm = {
                                    confirmClear = false
                                    scope.launch {
                                        val removed = withContext(Dispatchers.IO) {
                                            transferViewModel.clearReceivedHistory()
                                        }
                                        toast.show("已清理 $removed 个文件")
                                    }
                                },
                            )
                        }

                        // ⑧ 调试模式（默认不显示：仅 debug 构建 + 关于页长按版本解锁后出现）
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

                        // ⑨ 关于
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

// 无障碍状态文案已收敛到 ui/AccessibilityCopy.kt：状态行用 s09AccessibilityStatusText(...)
// （两段式，带运行时长）、仅状态场景用 accessibilityStatusText(...)、空态用 noEventEmptyText(...)。
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
/** API 30 的 CONFIRM 触觉在 minSdk 29 上回落 VIRTUAL_KEY（不新增 InlinedApi）。 */
private fun View.performConfirmHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

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
