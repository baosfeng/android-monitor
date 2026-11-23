package com.bsfeng.monitor.parity

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.MonitorState
import com.bsfeng.monitor.ui.SettingsIndexPage
import com.bsfeng.monitor.ui.SettingsScreenRuntime
import com.bsfeng.monitor.ui.SettingsSubPage
import com.bsfeng.monitor.ui.SettingsSubPageScreen
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.MotionTierStore
import com.bsfeng.monitor.ui.rememberLocationLaunchers
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 设置页（s09）的「实现真值」导出。
 *
 * 实现把原型的一张长页拆成「一级页 + 二级页」，所以导出分两层做，不拼假屏：
 *
 * 1. [exportSettingsIndexS09] 导出一级页 —— Tab 落到「设置」时**真实渲染**的那一屏
 *    （页头 + 快捷区 + 六组入口），写成 impl-s09.json 与 proto s09 逐锚点对照。
 *    原型里画在这一层的分组卡（监控时长 / 电池优化 / 定位 / 额度 / 验证器 / 跳过 / 记账 / 外观 /
 *    调试 / 关于），实现已搬进二级页 —— 它们的锚点在这里必然「缺块」，这是信息架构决定的，
 *    不是排版缺陷。
 * 2. 其余方法各导出一个二级页（真实打开时的整屏：ScreenHead + 该组正文），
 *    写成 impl-s09-<slug>.json，用来回答「这些锚点到底落在哪一屏、几何还差多少」。
 *
 * 假数据照原型铺满：无障碍已授权已运行、今日 128 条事件、规则 42 条 / 订阅源 2 个 / 白名单 9 个、
 * 额度 2 个 provider、验证器 5 个账户、最近备份 1 份 —— 缺块时才分得清「数据没铺满」与「真没实现」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S09SettingsParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 一级页：页头 + 快捷区 + 六组入口（与 [com.bsfeng.monitor.ui.SettingsScreen] 同一份组合）。 */
    @Test
    fun exportSettingsIndexS09() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s09")) {
                    SettingsIndexPage(
                        rt = rememberFakeRuntime(debugUi = true),
                        onOpenSubPage = {},
                        bodyScroll = rememberScrollState(),
                        headFocus = remember { FocusRequester() },
                    )
                }
            }
        }
        dump("s09", "build/parity/impl-s09.json")
    }

    // ── 二级页：每屏一个方法（一个 test = 一次 setContent）────────────────

    @Test
    fun exportSubPageGeneral() = exportSubPage("general", SettingsSubPage.GENERAL, locationOn = true)

    @Test
    fun exportSubPageData() = exportSubPage("data", SettingsSubPage.DATA)

    @Test
    fun exportSubPageAppearance() = exportSubPage("appearance", SettingsSubPage.APPEARANCE)

    @Test
    fun exportSubPageAbout() = exportSubPage("about", SettingsSubPage.ABOUT)

    @Test
    fun exportSubPageDebug() = exportSubPage("debug", SettingsSubPage.DEBUG)

    @Test
    fun exportSubPageLocation() = exportSubPage("location", SettingsSubPage.LOCATION_DETAIL, locationOn = true)

    @Test
    fun exportSubPageQuota() = exportSubPage("quota", SettingsSubPage.QUOTA_DETAIL)

    @Test
    fun exportSubPageTotp() = exportSubPage("totp", SettingsSubPage.TOTP_DETAIL)

    /** 跳过细则的关键锚点（规则 / 白名单两行）在二级页；这里只导功能开关页下的这一组。 */
    @Test
    fun exportSubPageSkip() = exportSubPage("skip", SettingsSubPage.SKIP_DETAIL)

    @Test
    fun exportSubPageFinance() = exportSubPage("finance", SettingsSubPage.FINANCE_DETAIL)

    private fun exportSubPage(slug: String, page: SettingsSubPage, locationOn: Boolean = false) {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s09-" + slug)) {
                    SettingsSubPageScreen(
                        page = page,
                        rt = rememberFakeRuntime(debugUi = true, locationOn = locationOn),
                        onBack = {},
                        onOpenSubPage = {},
                    )
                }
            }
        }
        dump("s09-" + slug, "build/parity/impl-s09-" + slug + ".json")
    }

    private fun dump(screen: String, path: String) {
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf(screen to items), File(path))
        println("PARITY(" + screen + ") tags=" + items.size + " :: " + items.joinToString(",") { it.id })
    }
}

/** 内存态档位存储：不碰 SharedPreferences，默认档 FULL（原型动效档位是 FLU）。 */
private class FakeMotionStore : MotionTierStore {
    override fun readTier(): MotionTier? = MotionTier.FULL
    override fun writeTier(tier: MotionTier) = Unit
    override fun readAutoDowngraded(): Boolean = false
    override fun writeAutoDowngraded(downgraded: Boolean) = Unit
}

/**
 * 假运行时状态：所有可写项都是独立 MutableState，不启 ViewModel、不读写磁盘。
 *
 * 原型同一时刻的状态：无障碍已授权且服务在跑、未暂停、监控窗口 10 秒、额度 2 个 provider、
 * 验证器 5 个账户、规则 42 条 / 订阅源 2 个 / 白名单 9 个、已加入电池白名单、无冻结提示。
 */
@Composable
private fun rememberFakeRuntime(
    debugUi: Boolean = false,
    locationOn: Boolean = false,
): SettingsScreenRuntime {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val view = LocalView.current
    val toast: ToastHostState = rememberToastHostState()
    val scope = rememberCoroutineScope()
    val featureFlags = remember { FeatureFlags.get(context) }
    val quotaSettings = remember { QuotaSettings(context) }
    val locationPermission = remember { mutableStateOf(LocationPermissionStatus.NOT_GRANTED) }
    val setFeature: (Feature, Boolean) -> Unit = { _, _ -> }
    val launchers = rememberLocationLaunchers(
        context = context,
        view = view,
        toast = toast,
        featureFlags = featureFlags,
        locationPermission = locationPermission,
        setFeature = setFeature,
    )
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { _: Uri? -> }
    val motionConfig = remember { MotionConfig(FakeMotionStore()) }
    val updateState = remember { MutableStateFlow<UpdateUiState>(UpdateUiState.Idle) }
    val currentUpdate by updateState.collectAsState()
    return remember(debugUi, locationOn) {
        SettingsScreenRuntime(
            t = t,
            context = context,
            view = view,
            toast = toast,
            scope = scope,
            featureFlags = featureFlags,
            liveOn = true,
            adSkipOn = true,
            financeOn = true,
            transportOn = false,
            locationOn = locationOn,
            quotaOn = true,
            totpOn = true,
            quotaSettings = quotaSettings,
            quotaAlertsOnState = mutableStateOf(true),
            quotaRefreshHoursState = mutableStateOf(1),
            transferViewModel = null,
            state = MonitorState(
                isMonitoring = true,
                todayEventCount = 128,
                accessibilityEnabled = true,
                accessibilityServiceRunning = true,
                isLoading = false,
            ),
            isPaused = false,
            debugEnabled = true,
            debugUi = debugUi,
            demoVisible = true,
            watchWindowMs = 10_000L,
            themeId = "apple",
            motionConfig = motionConfig,
            motionTier = MotionTier.FULL,
            autoDowngraded = false,
            hapticEnabled = true,
            hapticStrength = HapticStrength.NORMAL,
            demoStateState = mutableStateOf(FiveState.POPULATED),
            localAppearanceState = mutableStateOf(false),
            localGalleryState = mutableStateOf(false),
            updateState = updateState,
            currentUpdate = currentUpdate,
            ruleCount = mutableStateOf(42),
            subscriptionCount = mutableStateOf(2),
            whitelistCount = mutableStateOf(9),
            batteryIgnored = mutableStateOf(true),
            heartbeatNoticeState = mutableStateOf(null),
            notificationsOn = mutableStateOf(true),
            locationPermission = locationPermission,
            backupFrequencyState = mutableStateOf(BackupFrequency.WEEKLY),
            lastBackup = mutableStateOf(null),
            backupCount = mutableStateOf(1),
            backupBusyState = mutableStateOf(false),
            restoreBusyState = mutableStateOf(false),
            pendingRestoreUriState = mutableStateOf(null),
            restoreLauncher = restoreLauncher,
            locationLauncher = launchers.locationLauncher,
            backgroundLauncher = launchers.backgroundLauncher,
            reloadCounters = {},
            setFeature = setFeature,
            openLocationAppSettings = {},
            deviceId = "示例设备",
            onTogglePause = {},
            onSetWatchWindowMs = {},
            onToggleDebug = {},
            effective = FiveState.POPULATED,
            onNavigateToSkipRules = {},
            onNavigateToWhitelist = {},
            onNavigateToAppearance = null,
            onNavigateToGallery = null,
            onNavigateToQuotaSettings = {},
            onNavigateToBudget = null,
            onNavigateToTotp = {},
        )
    }
}
