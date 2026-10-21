package com.bsfeng.monitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import com.bsfeng.monitor.autobook.AutoBookConfig
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.BackupOrigin
import com.bsfeng.monitor.backup.BackupOutcome
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.backup.RestoreApplyOutcome
import com.bsfeng.monitor.backup.RestoreOutcome
import com.bsfeng.monitor.service.MonitorAccessibilityService
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.update.ApkDownloader
import com.bsfeng.monitor.update.InstallResult
import com.bsfeng.monitor.update.UpdateCheckResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateInstaller
import com.bsfeng.monitor.update.UpdateManifest
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberDebugUiAvailable
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.util.DebugConfig
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.ui.theme.fgFade
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.R
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.TransferTileService
import com.bsfeng.monitor.transfer.service.TransferTileState
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import android.annotation.SuppressLint
import java.io.InputStream

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
    modifier: Modifier = Modifier,
    onNavigateToSkipRules: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onNavigateToAppearance: (() -> Unit)? = null,
    onNavigateToGallery: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val view = LocalView.current
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    // 功能开关：一处读，四个功能卡与折叠逻辑都按它判定；关闭的功能不创建对应 ViewModel
    val featureFlags = FeatureFlags.get()
    val enabledFeatures by featureFlags.enabled.collectAsState()
    val liveOn = enabledFeatures[Feature.LIVE] == true
    val adSkipOn = enabledFeatures[Feature.AD_SKIP] == true
    val financeOn = enabledFeatures[Feature.FINANCE] == true
    val transportOn = enabledFeatures[Feature.TRANSPORT] == true
    val transferViewModel: TransferViewModel? = if (transportOn) viewModel() else null
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
    // 应用内更新：检查 → 下载 → 安装（状态用 StateFlow，下载回调线程安全）
    val updateState = remember { MutableStateFlow<UpdateUiState>(UpdateUiState.Idle) }
    val currentUpdate by updateState.collectAsState()

    // 规则数 / 订阅源数 / 白名单数：只读其它模块的既有数据源，不新增存储
    var ruleCount by remember { mutableStateOf(0) }
    var subscriptionCount by remember { mutableStateOf(0) }
    var whitelistCount by remember { mutableStateOf(0) }
    var batteryIgnored by remember { mutableStateOf(false) }
    // 通知权限（系统级）：进设置页与从系统设置返回时刷新
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    // 数据与备份：频率 / 最近一次 / 已存份数（进设置页即刷新，见 reloadCounters）
    var backupFrequency by remember { mutableStateOf(BackupConfig(context).frequency()) }
    var lastBackup by remember { mutableStateOf(BackupConfig(context).lastBackup()) }
    var backupCount by remember { mutableStateOf(0) }
    var backupBusy by remember { mutableStateOf(false) }
    var restoreBusy by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    // SAF 选包：OpenDocument 拿到的是可读 uri，minSdk 29 下无需申请存储权限
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    fun reloadCounters() {
        ruleCount = MonitorApp.instance.container.skipRuleManager.getAllRules().size
        subscriptionCount = MonitorApp.instance.container.skipRuleManager.subscriptionMetas().size
        whitelistCount = context
            .getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet())
            ?.size ?: 0
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val backupConfig = BackupConfig(context)
        backupFrequency = backupConfig.frequency()
        lastBackup = backupConfig.lastBackup()
        backupCount = BackupManager(context).listBackups().size
    }

    // 从系统设置页返回时立即刷新（不看轮询的运气）
    /** 切换功能开关：落盘 + 立即生效（Tab 与设置项同一帧变化），并给一次触觉与提示。 */
    fun setFeature(feature: Feature, enabled: Boolean) {
        if (!featureFlags.setEnabled(feature, enabled)) return
        view.performConfirmHaptic()
        toast.show(if (enabled) "已开启「" + feature.title + "」" else "已关闭「" + feature.title + "」")
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        reloadCounters()
        notificationsOn = notificationsEnabled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { reloadCounters() }

    val effective = if (demoState == FiveState.POPULATED) FiveState.POPULATED else demoState

    // 沉浸式：底衬画在 inset 之外（内容才吃 Scaffold 的 padding），顶部与状态栏连成一片
    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop()
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
                        // ===== 功能（置顶）：一项功能一张卡 = 开关 + 该功能专属设置 =====
                        // 关闭后专属设置折叠成一句「开启后可用」：不消失、不换位置，重启后也找得到
                        SectionHeader("功能")

                        // 实时监控（Tab：实时）
                        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                            TokenRow(
                                title = "实时监控",
                                subtitle = if (liveOn) {
                                    "实时事件流与前台 App 记录"
                                } else {
                                    "已关闭：不生成「实时」Tab"
                                },
                                icon = Icons.Outlined.Speed,
                                trailing = { AppSwitch(liveOn, { setFeature(Feature.LIVE, it) }) },
                            )
                            TokenDivider(first = false)
                            if (liveOn) {
                                TokenRow(
                                    title = "今日记录",
                                    subtitle = "实时页按事件类型分组展示，概览实时入口照常可进",
                                    icon = Icons.Outlined.BarChart,
                                    trailing = { TailNum(state.todayEventCount.toString() + " 条") },
                                )
                            } else {
                                TokenRow(
                                    title = "实时事件流",
                                    subtitle = FeatureCatalog.offHint(Feature.LIVE),
                                    icon = Icons.Outlined.BarChart,
                                )
                            }
                        }

                        // 广告跳过：关的是「跳过引擎」，不是「广告」记录 Tab（记录页照常可看）
                        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                            TokenRow(
                                title = "广告跳过",
                                subtitle = if (adSkipOn) {
                                    "规则 " + ruleCount + " 条 · 白名单 " + whitelistCount + " 个"
                                } else {
                                    "已关闭：切 App 后不扫描页面、不执行跳过规则"
                                },
                                icon = Icons.Outlined.PlayArrow,
                                trailing = { AppSwitch(adSkipOn, { setFeature(Feature.AD_SKIP, it) }) },
                            )
                            if (adSkipOn) {
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "监控时长窗口",
                                    subtitle = "切换 App 后 " + (watchWindowMs / 1000) + " 秒内匹配跳过按钮",
                                    icon = Icons.Outlined.Timer,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = t.space4, vertical = t.space2),
                                    horizontalArrangement = Arrangement.spacedBy(t.space2),
                                ) {
                                    SkipConfig.WINDOW_OPTIONS_MS.forEach { option ->
                                        Chip(
                                            label = (option / 1000).toString() + " 秒",
                                            selected = option == watchWindowMs,
                                            onClick = {
                                                viewModel.setWatchWindowMs(option)
                                                view.performConfirmHaptic()
                                                toast.show("监控时长窗口：" + (option / 1000) + " 秒")
                                            },
                                        )
                                    }
                                }
                                TokenDivider(first = false)
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
                            } else {
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "跳过规则 / 应用白名单",
                                    subtitle = FeatureCatalog.offHint(Feature.AD_SKIP),
                                    icon = Icons.Outlined.Shield,
                                )
                            }
                            Spacer(Modifier.height(t.space1))
                        }

                        // 记账（Tab：记账）
                        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                            TokenRow(
                                title = "记账",
                                subtitle = if (financeOn) {
                                    "手动记账、统计与自动记账"
                                } else {
                                    "已关闭：不生成「记账」Tab"
                                },
                                icon = Icons.Outlined.Payments,
                                trailing = { AppSwitch(financeOn, { setFeature(Feature.FINANCE, it) }) },
                            )
                            TokenDivider(first = false)
                            if (financeOn) {
                                val autoBookEnabled by AutoBookConfig.enabledFlow.collectAsState()
                                TokenRow(
                                    title = "支付后弹窗确认",
                                    subtitle = if (autoBookEnabled) {
                                        "识别支付页，确认后入账"
                                    } else {
                                        "已关闭：不识别、不弹窗"
                                    },
                                    icon = Icons.Outlined.Payments,
                                    trailing = {
                                        AppSwitch(autoBookEnabled, { enabled ->
                                            AutoBookConfig.setEnabled(context, enabled)
                                            view.performConfirmHaptic()
                                            toast.show(
                                                if (enabled) "已开启支付后弹窗记账" else "已关闭支付后弹窗记账"
                                            )
                                        })
                                    },
                                )
                            } else {
                                TokenRow(
                                    title = "自动记账",
                                    subtitle = FeatureCatalog.offHint(Feature.FINANCE),
                                    icon = Icons.Outlined.Payments,
                                )
                            }
                            Spacer(Modifier.height(t.space1))
                        }

                        // 传送（Tab：传送；默认关闭）
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
                                        AppSwitch(!isPaused, {
                                            viewModel.togglePause()
                                            view.performConfirmHaptic()
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

                        // ===== 数据：数据与备份 =====
                        SectionHeader("数据")
                        // ⑧ 数据与备份（立即备份 / 从备份还原 / 自动频率 / 最近一次）
                        // 备份包只含本机数据文件（两张库 + shared_prefs + 规则快照），不含任何账号凭据；
                        // 公共 Downloads 一份给用户自己拷走，换机 / 重装后靠它还原。
                        Column(Modifier.fillMaxWidth()) {
                            GroupLabel("数据与备份")
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                TokenRow(
                                    title = "立即备份",
                                    subtitle = if (backupBusy) "正在打包账目与记录…" else "打包账目、记录与设置到 Downloads",
                                    icon = Icons.Outlined.Save,
                                    trailing = {
                                        AppButton(
                                            text = if (backupBusy) "备份中" else "备份",
                                            onClick = {
                                                if (!backupBusy) {
                                                    backupBusy = true
                                                    view.performConfirmHaptic()
                                                    scope.launch {
                                                        val outcome = withContext(Dispatchers.IO) {
                                                            BackupManager(context).backupNow(BackupOrigin.MANUAL)
                                                        }
                                                        backupBusy = false
                                                        reloadCounters()
                                                        when (outcome) {
                                                            is BackupOutcome.Success -> toast.show(
                                                                "已备份：" + (outcome.publicPath ?: outcome.localName ?: ""),
                                                            )
                                                            is BackupOutcome.Failed -> toast.show(outcome.message)
                                                        }
                                                    }
                                                }
                                            },
                                            variant = AppButtonVariant.Ghost,
                                            enabled = !backupBusy,
                                        )
                                    },
                                )
                                TokenDivider(first = false)
                                // 还原走 SAF：选包 → 校验 sha256 → 自动留 pre-restore 包 → 替换文件 → 重启进程
                                TokenRow(
                                    title = "从备份还原",
                                    subtitle = if (restoreBusy) "正在校验并还原…" else "选 zip 覆盖当前数据，先自动留一份",
                                    icon = Icons.Outlined.Restore,
                                    onClick = {
                                        if (!restoreBusy) {
                                            restoreLauncher.launch(
                                                arrayOf("application/zip", "application/octet-stream"),
                                            )
                                        }
                                    },
                                    trailing = { TailChevron() },
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "自动备份频率",
                                    subtitle = "默认每周 · 最多保留 5 份，超额删最旧",
                                    icon = Icons.Outlined.Timer,
                                )
                                ChipRow(
                                    items = BackupFrequency.values().map { it.label },
                                    selectedIndex = BackupFrequency.values()
                                        .indexOf(backupFrequency).coerceAtLeast(0),
                                    onSelect = { index ->
                                        val frequency = BackupFrequency.values()[index]
                                        backupFrequency = frequency
                                        BackupConfig(context).setFrequency(frequency)
                                        BackupScheduler.apply(context, frequency)
                                        view.performConfirmHaptic()
                                        toast.show(
                                            if (frequency == BackupFrequency.OFF) {
                                                "已关闭自动备份"
                                            } else {
                                                "自动备份：" + frequency.label + "备份一次"
                                            },
                                        )
                                    },
                                    modifier = Modifier.padding(
                                        horizontal = t.space4,
                                        vertical = t.space2,
                                    ),
                                )
                                TokenDivider(first = false)
                                TokenRow(
                                    title = "最近一次备份",
                                    subtitle = lastBackup?.let {
                                        formatBackupTime(it.at) + " · 共 " + backupCount + " 份"
                                    } ?: "还没有备份",
                                    icon = Icons.Outlined.History,
                                    onClick = {
                                        toast.show(lastBackup?.publicPath ?: "还没有备份包")
                                    },
                                    trailing = { TailChevron() },
                                )
                                Spacer(Modifier.height(t.space1))
                            }
                            ConfirmDialog(
                                visible = pendingRestoreUri != null,
                                title = "从备份还原",
                                message = "将用所选备份覆盖当前的账目、记录与设置。还原前会自动把当前数据" +
                                    "备份成一份 pre-restore 包；还原完成后 App 会自动重启。",
                                confirmLabel = "还原",
                                dismissLabel = "取消",
                                onDismiss = { pendingRestoreUri = null },
                                onConfirm = {
                                    val uri = pendingRestoreUri
                                    pendingRestoreUri = null
                                    if (uri != null && !restoreBusy) {
                                        restoreBusy = true
                                        toast.show("正在校验备份包…")
                                        scope.launch {
                                            // URI 读取在 IO 线程里注入给编排层（RestoreIntake），校验失败不碰现有数据
                                            val outcome = BackupManager(context).prepareRestore {
                                                openRestoreStream(context, uri)
                                            }
                                            when (outcome) {
                                                is RestoreOutcome.Rejected -> {
                                                    restoreBusy = false
                                                    toast.show(outcome.reason)
                                                }
                                                is RestoreOutcome.Ready -> {
                                                    val applied = withContext(Dispatchers.IO) {
                                                        BackupManager(context).applyRestore(outcome.plan)
                                                    }
                                                    // 这一步已 close 数据库句柄：无论成败都要重启，
                                                    // 失败时由下次启动的 marker 自愈回滚
                                                    toast.show(
                                                        if (applied is RestoreApplyOutcome.Failed) {
                                                            "还原未完成：" + applied.reason + "，App 将重启并自动回滚"
                                                        } else {
                                                            "还原完成，App 即将重启"
                                                        }
                                                    )
                                                    delay(1200)
                                                    BackupManager(context).restartProcess()
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }


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

                        Spacer(Modifier.height(t.space6))
                        }
                }
            }
        }

        UpdateDialog(
            state = currentUpdate,
            onDismiss = { updateState.value = UpdateUiState.Idle },
            onDownload = { manifest ->
                updateState.value = UpdateUiState.Downloading(0, 0)
                scope.launch {
                    val checker = UpdateChecker()
                    val dir = File(context.cacheDir, "updates")
                    val result = withContext(Dispatchers.IO) {
                        ApkDownloader().download(
                            sources = checker.downloadSources(manifest),
                            destDir = dir,
                            expectedSha256 = manifest.apkSha256,
                        ) { downloaded, total ->
                            updateState.value = UpdateUiState.Downloading(downloaded, total)
                        }
                    }
                    result.fold(
                        onSuccess = { updateState.value = UpdateUiState.Downloaded(it) },
                        onFailure = {
                            toast.show("下载失败：${it.message ?: "网络不可用"}")
                            updateState.value = UpdateUiState.Idle
                        },
                    )
                }
            },
            onInstall = { apk ->
                when (val result = UpdateInstaller.install(context, apk)) {
                    is InstallResult.Started -> updateState.value = UpdateUiState.Idle
                    is InstallResult.NeedPermission -> toast.show("请允许安装未知来源应用后重试")
                    is InstallResult.Failed -> toast.show(result.message)
                }
            },
        )

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

/**
 * 「传送」功能卡内的专属设置（后台接收 / 提示音 / 免配对窗口 / 体积阈值 / 接收目录）。
 *
 * 只在功能开启且 ViewModel 已创建时渲染；关闭时整块折叠成一句「开启后可用」，
 * 因此传送关闭时这里连 Flow 都不订阅，更不会去拉起引擎。
 */
@Composable
private fun TransferFeatureSettings(
    transferViewModel: TransferViewModel,
    toast: ToastHostState,
    context: Context,
    scope: CoroutineScope,
    view: View,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        val sound by transferViewModel.soundSettings.collectAsState()
        val windowState by transferViewModel.openPairing.collectAsState()
        val thresholdMb by transferViewModel.autoApproveThresholdMb.collectAsState()
        val stats by transferViewModel.receivedStats.collectAsState()
        var confirmClear by remember { mutableStateOf(false) }
        var windowLeftMs by remember { mutableStateOf(windowState.remainingMs()) }
        var editingName by remember { mutableStateOf(false) }
        var nameDraft by remember { mutableStateOf("") }

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
        val autoCloseMinutes by transferViewModel.autoCloseMinutes.collectAsState()
        val myAlias by transferViewModel.aliasFlow.collectAsState()

        TokenRow(
            title = "本机名称",
            subtitle = myAlias + " · 对端设备列表里显示的名字",
            icon = Icons.Outlined.Smartphone,
            onClick = {
                nameDraft = myAlias
                editingName = true
            },
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "后台接收",
            subtitle = if (backgroundReceive) {
                "常驻前台服务 · 通知显示在线设备"
            } else {
                "仅 App 可见时运行 · 默认关闭"
            },
            icon = Icons.Outlined.NotificationsActive,
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
        // 「自动关闭接收」：到点自动停服务，避免不用时白白耗电。
        // 只持久化时长，不持久化计时起点（进程重启后重新计时）。
        TokenRow(
            title = "自动关闭接收",
            subtitle = if (autoCloseMinutes == ReceivingAutoClose.OFF) {
                "关闭：一直保持接收，直到手动关闭（默认）"
            } else {
                "开启接收后 " + ReceivingAutoClose.label(autoCloseMinutes) + "自动停止 · 省电"
            },
            icon = Icons.Outlined.Timer,
        )
        ChipRow(
            items = ReceivingAutoClose.MINUTES_OPTIONS.map {
                ReceivingAutoClose.label(it)
            },
            selectedIndex = ReceivingAutoClose.MINUTES_OPTIONS
                .indexOf(autoCloseMinutes).coerceAtLeast(0),
            onSelect = { index ->
                val minutes = ReceivingAutoClose.MINUTES_OPTIONS[index]
                transferViewModel.setAutoCloseMinutes(minutes)
                view.performConfirmHaptic()
                toast.show(
                    if (minutes == ReceivingAutoClose.OFF) {
                        "已关闭「自动关闭接收」"
                    } else {
                        "接收将在 " + ReceivingAutoClose.label(minutes) + "后自动关闭"
                    },
                )
            },
            modifier = Modifier.padding(
                horizontal = t.space4,
                vertical = t.space2,
            ),
        )
        TokenDivider(first = false)
        TokenRow(
            title = "通知栏磁贴",
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "一键添加到快捷设置，下拉即开关"
            } else {
                "下拉通知栏 → 编辑 → 添加「局域网互传」"
            },
            icon = Icons.Outlined.Widgets,
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
            icon = Icons.Outlined.VolumeUp,
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
            icon = Icons.Outlined.VolumeOff,
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
            icon = Icons.Outlined.Vibration,
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
            icon = Icons.Outlined.LockOpen,
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
        // 窗口时长：开启时切换会以新时长重新计时；
        // 未开启时整行降透明度，避免「未开启」与高亮的「10 分钟」并排读成自相矛盾
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (windowState.enabled) 1f else 0.55f }
                .padding(horizontal = t.space4, vertical = t.space2),
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
            icon = Icons.Outlined.DataUsage,
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
            icon = Icons.Outlined.Folder,
            trailing = {
                AppButton(
                    text = "清理",
                    onClick = {
                        view.performConfirmHaptic()
                        // 没有文件时不弹「删除 0 个文件」的空确认，直接给一句提示
                        if (stats.isEmpty) {
                            toast.show("暂无可清理的接收文件")
                        } else {
                            confirmClear = true
                        }
                    },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
        Spacer(Modifier.height(t.space1))

        ConfirmDialog(
            visible = editingName,
            title = "本机名称",
            message = "对端设备列表里显示的名字，最多 $ALIAS_MAX_LENGTH 个字符",
            confirmLabel = "保存",
            dismissLabel = "取消",
            onDismiss = { editingName = false },
            onConfirm = {
                val name = nameDraft.trim().take(ALIAS_MAX_LENGTH)
                if (name.isNotBlank()) {
                    transferViewModel.setAlias(name)
                    toast.show("本机名称已改为「" + name + "」")
                }
                editingName = false
            },
            extraContent = {
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { if (it.length <= ALIAS_MAX_LENGTH) nameDraft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.fg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(t.radiusMd))
                        .background(t.fgFade(6))
                        .padding(t.space3),
                )
            },
        )
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
}

/**
 * 系统通知开关状态（本 App 只有事件提醒与传送通知，不做营销推送）。
 */
private fun notificationsEnabled(context: Context): Boolean =
    runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }.getOrDefault(false)

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

/**
 * 打开所选备份包的字节流。
 *
 * 流的所有权交给 [com.bsfeng.monitor.backup.RestoreIntake]（内部 `use {}` 负责关闭），
 * 因此这里不关闭；lint 的 Recycle 检查无法跨 suspend lambda 看到关闭点，定向豁免。
 */
@SuppressLint("Recycle")
private fun openRestoreStream(context: Context, uri: Uri): InputStream? =
    context.contentResolver.openInputStream(uri)

private fun processUptimeMs(): Long =
    (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).coerceAtLeast(0L)

private fun formatBackupTime(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))

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

/** 本机名称长度上限（发现报文一行文案，过长会被对端列表截断）。 */
private const val ALIAS_MAX_LENGTH = 24

/** 应用内更新的检查 / 下载 / 安装对话框。 */
@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (File) -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${state.manifest.versionName}") },
            text = {
                Text(
                    state.manifest.changelog.ifBlank { "包含功能改进与问题修复。" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { onDownload(state.manifest) }) { Text("下载更新") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在下载更新") },
            text = {
                Column {
                    val fraction = if (state.total > 0) {
                        (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Text(
                        if (state.total > 0) {
                            "${(fraction * 100).toInt()}%  ${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                        } else {
                            formatBytes(state.downloaded)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )

        is UpdateUiState.Downloaded -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新已下载") },
            text = { Text("点击安装完成更新。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { onInstall(state.apk) }) { Text("安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        else -> Unit
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[index])
}
