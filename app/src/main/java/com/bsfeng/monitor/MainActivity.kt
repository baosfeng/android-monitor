package com.bsfeng.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.BudgetScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.FinanceStatsScreen
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel as FinanceViewModel
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.quota.QuotaScreen
import com.bsfeng.monitor.ui.quota.QuotaSettingsScreen
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.SkipRuleScreen
import com.bsfeng.monitor.ui.SkipRuleViewModel
import com.bsfeng.monitor.ui.AdScreen
import com.bsfeng.monitor.ui.AdViewModel
import com.bsfeng.monitor.ui.WhitelistScreen
import com.bsfeng.monitor.ui.WhitelistViewModel
import com.bsfeng.monitor.ui.components.AppearanceScreen
import com.bsfeng.monitor.ui.theme.DesignTokenCatalog
import com.bsfeng.monitor.ui.components.BrandSplash
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.ui.TransferScreen
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.OverviewScreen
import com.bsfeng.monitor.ui.trip.TripScreen
import com.bsfeng.monitor.ui.foundation.MonitorEdgeToEdge
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.JankMonitor
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.ui.theme.ThemeRepository
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {

    /** 从通知点进来的目标 Tab 下标（默认 0 = 概览；下标 = [AppTab] 枚举顺序）。 */
    private var startTab by mutableIntStateOf(0)

    /** 自增序号：同一条通知再点一次也要重新落点（只对比下标会漏掉「已在同一 Tab」的情况）。 */
    private var startTabRequest by mutableIntStateOf(0)

    /** 「快捷记一笔」直达序号：0 = 无待处理请求；连点同一个组件也重新打开表单。 */
    private var openAddTransactionRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTab = intent.getIntExtra(EXTRA_START_TAB, startTab)
        startTabRequest++
        if (consumeOpenAddTransaction(intent)) openAddTransactionRequest++
    }

    /**
     * 读一次「直达记一笔」标记并就地消费掉。
     *
     * 消费是必须的：同一份 Intent 在旋转 / 进程重建后会被系统原样再交给新实例，
     * 不删 extra 就会每次转屏都重新弹出一次表单（见 onCreate 的 [savedInstanceState] 判断）。
     */
    private fun consumeOpenAddTransaction(intent: Intent?): Boolean {
        val requested = intent?.getBooleanExtra(EXTRA_OPEN_ADD_TRANSACTION, false) ?: false
        if (requested) intent?.removeExtra(EXTRA_OPEN_ADD_TRANSACTION)
        return requested
    }

    private sealed class FinanceRoute {
        data object Main : FinanceRoute()
        data class AddTransaction(val fromMonth: String) : FinanceRoute()
        data class EditTransaction(val transaction: Transaction) : FinanceRoute()
        data object Stats : FinanceRoute()
        data object Budget : FinanceRoute()
        data object Import : FinanceRoute()
    }

    /**
     * 卡顿采样器：前台期间统计超标帧，超标占比超过阈值时把动效档从 FULL 自动降到 NORMAL。
     *
     * 只在 onResume/onPause 之间采样——后台/锁屏不算用户感知的卡顿；且不 start 就完全
     * 不生效（自动降档形同虚设），所以这两处必须成对存在。
     */
    private val jankMonitor by lazy { JankMonitor(config = MotionConfig.get(this)) }

    override fun onResume() {
        super.onResume()
        // 系统「动画时长缩放」可能被用户在系统设置里改过：每次回前台重读一次（进程内缓存，
        // 不重置就永远用首次读到的值）
        MotionConfig.get(this).refreshSystemPolicy()
        jankMonitor.start()
    }

    override fun onPause() {
        jankMonitor.stop()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全应用仅浅色主题：窗口外观（系统栏透明 + 图标定色）统一由 MonitorEdgeToEdge 负责
        MonitorEdgeToEdge.apply(this)

        startTab = intent?.getIntExtra(EXTRA_START_TAB, 0) ?: 0
        // 小组件「快捷记一笔」冷启动直达：只在**首次**创建时消费，旋转重建沿用旧 Intent 不该再弹一次
        if (savedInstanceState == null && consumeOpenAddTransaction(intent)) {
            openAddTransactionRequest = 1
        }

        // 主题持久化：必须在 setContent 之前读，保证首帧就是用户选定的主题
        ThemeRepository.init(applicationContext)

        // 自验钩子（演示件）：把当前主题全部 token 的运行时值打到 logcat，tag=TokenDump。
        // 用法：adb logcat -d -s TokenDump  →  逐条比对 CSS 原文值。仅「调试模式」打开时输出。
        if (com.bsfeng.monitor.util.DebugConfig.demoVisible) {
            val active = ThemeRepository.currentThemeId()
            android.util.Log.i("TokenDump", "theme=" + active + " tokens=" + DesignTokenCatalog.dumpAll(active).size)
            DesignTokenCatalog.dumpAll(active).forEach { android.util.Log.i("TokenDump", it) }
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // POST_NOTIFICATIONS 是 API 33+ 的运行时权限：29–32 没有该权限，直接申请会被拒
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            MainContent(
                startTab = startTab,
                startTabRequest = startTabRequest,
                openAddTransactionRequest = openAddTransactionRequest,
                applicationContext = applicationContext,
            )
        }
    }

    companion object {
        /** 启动/通知点击时指定目标 Tab 的 Intent extra。 */
        const val EXTRA_START_TAB = "com.bsfeng.monitor.EXTRA_START_TAB"

        /** 小组件「快捷记一笔」用：true = 打开记账 Tab 并直接进入「记一笔」表单。 */
        const val EXTRA_OPEN_ADD_TRANSACTION = "com.bsfeng.monitor.EXTRA_OPEN_ADD_TRANSACTION"

        // 历史下标（= AppTab 枚举顺序）：通知里仍按 int 传，落点由 FeatureCatalog 转成 AppTab。
        // 顺序不要改（旧通知的 int 会错位），新增 Tab 追加在末尾。
        const val LIVE_TAB = 1
        const val AD_TAB = 2
        const val FINANCE_TAB = 3
        const val TRANSFER_TAB = 4
        const val SETTINGS_TAB = 5
        const val QUOTA_TAB = 6

        /** 额度预警通知的落点：打开 App 并切到额度 Tab。 */
        fun quotaTabIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_START_TAB, QUOTA_TAB)
            }

        /** 「快捷记一笔」组件 / 其它入口的落点：记账 Tab + 直达新增页（当月）。 */
        fun addTransactionIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_START_TAB, FINANCE_TAB)
                putExtra(EXTRA_OPEN_ADD_TRANSACTION, true)
            }

        /** 旋转 / 进程重建后仍停留在原子页：把路由编码成可保存的原语列表。 */
        private val FinanceRouteSaver: Saver<FinanceRoute, Any> = listSaver(
            save = { route ->
                when (route) {
                    is FinanceRoute.Main -> listOf("main")
                    is FinanceRoute.AddTransaction -> listOf("add", route.fromMonth)
                    is FinanceRoute.EditTransaction -> with(route.transaction) {
                        listOf(
                            "edit", id, type, categoryName, amount, accountName, remark, tag,
                            date, dateTime, createdAt, updatedAt, source, sourceId,
                        )
                    }
                    is FinanceRoute.Stats -> listOf("stats")
                    is FinanceRoute.Budget -> listOf("budget")
                    is FinanceRoute.Import -> listOf("import")
                }
            },
            restore = { saved ->
                when (saved.firstOrNull() as? String) {
                    "add" -> FinanceRoute.AddTransaction(saved.getOrNull(1) as? String ?: "")
                    "edit" -> if (saved.size < 14) FinanceRoute.Main else FinanceRoute.EditTransaction(
                        Transaction(
                            id = saved[1] as Long,
                            type = saved[2] as Int,
                            categoryName = saved[3] as String,
                            amount = saved[4] as Double,
                            accountName = saved[5] as String,
                            remark = saved[6] as String,
                            tag = saved[7] as String,
                            date = saved[8] as String,
                            dateTime = saved[9] as String,
                            createdAt = saved[10] as Long,
                            updatedAt = saved[11] as Long,
                            source = saved[12] as String,
                            sourceId = saved[13] as String,
                        )
                    )
                    "stats" -> FinanceRoute.Stats
                    "budget" -> FinanceRoute.Budget
                    "import" -> FinanceRoute.Import
                    else -> FinanceRoute.Main
                }
            },
        )
    }
}

