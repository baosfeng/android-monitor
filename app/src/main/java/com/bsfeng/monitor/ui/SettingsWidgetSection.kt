package com.bsfeng.monitor.ui

// 从 ui/SettingsFeatureSection.kt 接出的「功能 · 桌面小组件」卡：隐藏金额开关 + 6 个「添加到桌面」入口。
// 负责「桌面上放哪几个组件、金额给不给看」。为什么独立成文件：6 行入口各带一次 requestPinAppWidget
// 与结果校验，塞回 SettingsFeatureSection 会把已有功能卡挤到看不见。

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.widget.AdSkipWidgetReceiver
import com.bsfeng.monitor.widget.FinanceWidgetReceiver
import com.bsfeng.monitor.widget.HealthWidgetReceiver
import com.bsfeng.monitor.widget.QuickAddWidgetReceiver
import com.bsfeng.monitor.widget.QuotaWidgetReceiver
import com.bsfeng.monitor.widget.TransferWidgetReceiver
import com.bsfeng.monitor.widget.WidgetRefresher
import com.bsfeng.monitor.widget.WidgetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 发出 pin 请求后等多久再核对桌面上的数量（小米 / 红米桌面不回调，只能延时后看数量差）。 */
private const val PIN_VERIFY_DELAY_MS = 1_500L

/** 桌面（Launcher）不支持一键添加：只能引导去长按桌面手动加。 */
internal const val PIN_UNSUPPORTED_TOAST = "当前桌面不支持一键添加，请长按桌面 → 小组件 手动添加"

/** 判不了或请求落空时的兜底提示（手动路径两处共用同一句，避免两种说法）。 */
internal const val PIN_MANUAL_TOAST = "请在桌面长按 → 小组件 里手动添加"

/** 数量增加了：确认已经落到桌面。 */
internal const val PIN_ADDED_TOAST = "已添加到桌面"

/**
 * pin 结果 → 提示文案。
 *
 * 只有「桌面上真的多了一块」算成功：requestPinAppWidget 返回 true 只代表请求发出去了，
 * 厂商桌面可以既不弹确认框也不回调 onSuccess（小米 / 红米实测如此）。
 */
internal fun pinVerdictToast(added: Boolean): String =
    if (added) PIN_ADDED_TOAST else PIN_MANUAL_TOAST

/** 一行入口：标题 / 一句话说明 / 图标 / 接收器。 */
private class WidgetEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val receiver: Class<*>,
)

/**
 * 6 个组件与 docs/小组件/设计.md 的表格同序；副标题取 AndroidManifest 里的 label/description 文案。
 *
 * 用函数而不是顶层 val：入口只在设置页组合时取一次，不值得为它加一份静态初始化
 * （顶层 val 会在类加载时就构建 6 个图标对象，而静态初始化还牵着 Glance / Compose 的类）。
 */
private fun widgetEntries(): List<WidgetEntry> = listOf(
    WidgetEntry("记账月度", "本月支出、收入与预算剩余", Icons.Outlined.Payments, FinanceWidgetReceiver::class.java),
    WidgetEntry("监控健康", "监控是否在跑，含今日事件与跳过", Icons.Outlined.Speed, HealthWidgetReceiver::class.java),
    WidgetEntry("额度见底", "谁最先见底，含最近更新时间", Icons.Outlined.BatteryStd, QuotaWidgetReceiver::class.java),
    WidgetEntry("跳过战报", "今日、近 7 天与累计的跳过数", Icons.Outlined.Shield, AdSkipWidgetReceiver::class.java),
    WidgetEntry("快捷记一笔", "一键直达记一笔", Icons.Outlined.AddCircle, QuickAddWidgetReceiver::class.java),
    WidgetEntry("传送状态", "接收状态与在线设备数", Icons.Outlined.SwapHoriz, TransferWidgetReceiver::class.java),
)

/**
 * 设置「功能 · 桌面小组件」卡 —— 隐藏金额开关 + 6 个「添加到桌面」入口。
 *
 * 隐藏金额默认关：用户主动放到桌面的组件默认可读可用（[WidgetSettings] 默认 false）。
 * context / scope / toast 由调用方就地传入，本卡不持有 Activity、不新起线程。
 */
@Composable
internal fun SettingsWidgetSection(
    t: DesignTokens,
    context: Context,
    toast: ToastHostState,
    scope: CoroutineScope,
) {
    // 一次布尔读、与组件刷新走同一条同步路径（WidgetSettings 刻意不用 DataStore）：进页面即可见当前值
    var hideAmounts by remember { mutableStateOf(WidgetSettings.isAmountHidden(context)) }
    // 一次只放一个：系统确认框还没答完时再点别的，两轮提示会互相盖掉
    var pendingReceiver by remember { mutableStateOf<Class<*>?>(null) }

    SectionHeader("桌面小组件")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        Text(
            "把监控、记账、额度放到桌面，点击直达",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.padding(horizontal = t.space4, vertical = t.space3),
        )
        TokenDivider(first = false)
        TokenRow(
            title = "隐藏金额",
            subtitle = "开启后小组件里的金额显示为 ••••",
            icon = Icons.Outlined.Visibility,
            trailing = {
                // 尾部参数是 modifier，回调只能进括号（与仓库里其余 AppSwitch 调用一致）
                AppSwitch(hideAmounts, { hidden ->
                    hideAmounts = hidden
                    WidgetSettings.setAmountHidden(context, hidden)
                    // 桌面可能正显示旧金额：立刻推一轮重画，不等下一次 60 秒心跳
                    WidgetRefresher.refreshAll(context)
                    toast.show(if (hidden) "小组件已隐藏金额" else "小组件已恢复显示金额")
                })
            },
        )
        widgetEntries().forEach { entry ->
            TokenDivider(first = false)
            TokenRow(
                title = entry.title,
                subtitle = entry.subtitle,
                icon = entry.icon,
                trailing = {
                    AppButton(
                        text = if (pendingReceiver === entry.receiver) "添加中" else "添加",
                        variant = AppButtonVariant.Ghost,
                        enabled = pendingReceiver == null,
                        onClick = {
                            if (pendingReceiver == null) {
                                pendingReceiver = entry.receiver
                                requestPinWidget(context, toast, scope, entry.receiver) {
                                    pendingReceiver = null
                                }
                            }
                        },
                    )
                },
            )
        }
        Spacer(Modifier.height(t.space1))
    }
}

/**
 * 把某个组件放到桌面。
 *
 * 三条路对应三种提示：桌面不支持 pin → 只能手动；请求被系统拒了（返回 false / 抛异常）→ 同样手动；
 * 请求发出去了 → 等 [PIN_VERIFY_DELAY_MS] 用 [AppWidgetManager.getAppWidgetIds] 的数量差判定。
 * 延时跑在调用方传入的 [scope]（设置页的 rememberCoroutineScope）上，不新起线程。
 *
 * [onFinished] 在主线程回调，用来放开「添加」按钮；无论成败都会调到，否则按钮会永久灰着。
 */
private fun requestPinWidget(
    context: Context,
    toast: ToastHostState,
    scope: CoroutineScope,
    receiver: Class<*>,
    onFinished: () -> Unit,
) {
    val manager = AppWidgetManager.getInstance(context)
    // 桌面（Launcher）不支持一键添加时，requestPinAppWidget 只会静默失败：先拦下来给手动路径
    if (!manager.isRequestPinAppWidgetSupported) {
        toast.show(PIN_UNSUPPORTED_TOAST)
        onFinished()
        return
    }
    val provider = ComponentName(context, receiver)
    val before = installedCount(manager, provider)
    // 返回 true 只代表请求已发出；false / 异常代表系统直接拒了，不必再等 1.5 秒
    val requested = runCatching {
        manager.requestPinAppWidget(provider, null, null)
    }.getOrDefault(false)
    if (!requested) {
        toast.show(PIN_MANUAL_TOAST)
        onFinished()
        return
    }
    scope.launch {
        delay(PIN_VERIFY_DELAY_MS)
        toast.show(pinVerdictToast(installedCount(manager, provider) > before))
        onFinished()
    }
}

/** 桌面上该组件的实例数。provider 未在 manifest 声明时 getAppWidgetIds 会抛异常，按 0 处理。 */
private fun installedCount(manager: AppWidgetManager, provider: ComponentName): Int =
    runCatching { manager.getAppWidgetIds(provider).size }.getOrDefault(0)
