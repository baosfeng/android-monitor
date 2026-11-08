package com.bsfeng.monitor.ui

// 设置二级页清单（s09 交互口径的页面层级）：一级页 5 组入口 + 1 组调试入口，
// 「功能开关」页再下钻 N 个功能细则页（N = Feature.entries.size，见 settingsFeatureDetailPages）。
//
// ⚠️ name 会写进 MainContent 的 rememberSaveable（字符串），改名 = 升级后恢复状态失配：
//   · GENERAL / FEATURE / DATA / ABOUT 是旧版本存过的名字，**只能换 title 换内容，不能换名字**
//     （GENERAL 现承载「监控与采集」，旧版本恢复进来落到新页而不是空白）。
//   · 新增的 *_DETAIL 项只在功能开关页下钻，不进一级页入口清单（见 [indexEntries]）。

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.bsfeng.monitor.feature.Feature

/**
 * 设置二级页。
 *
 * @param title 页头大标题
 * @param meta 页头副标题（一句话说清这一页管什么）
 * @param indexEntry true = 一级页的组入口；false = 只有从「功能开关」页下钻才打开
 */
enum class SettingsSubPage(
    val title: String,
    val meta: String,
    val indexEntry: Boolean = true,
) {
    // ── 一级页 6 组（顺序即入口卡的行序；DEBUG 由 debugUi 闸决定是否出现）──
    GENERAL("监控与采集", "前台监控 · 使用时长 · 事件上报 · 监控窗口 · 电池优化"),
    FEATURE(
        "功能开关",
        Feature.entries.size.toString() + " 项功能各一行开关，右侧 chevron 进各自细则",
    ),
    DATA("数据与备份", "立即备份 · 自动频率 · 导出到下载 · 从备份还原"),
    APPEARANCE("外观与动效", "外观与主题 · 动效强度 · 触觉反馈与强度"),
    ABOUT("关于", "版本 · 检查更新 · 开源许可"),
    // 只在 debug 构建 + 关于页长按解锁后进一级页入口清单（见 settingsIndexEntries）
    DEBUG("调试与诊断", "调试模式 · 组件与状态演示 · 模拟自动记账浮窗"),

    // ── 功能细则页（从「功能开关」下钻）──
    LIVE_DETAIL("实时监控", "实时事件流与前台 App 记录", indexEntry = false),
    SKIP_DETAIL("广告跳过", "跳过引擎 · 监控时长窗口 · 规则与白名单", indexEntry = false),
    FINANCE_DETAIL("记账", "手动记账 · 统计 · 支付后弹窗确认", indexEntry = false),
    TRANSPORT_DETAIL("传送", "本机名称 · 后台接收 · 提示音 · 接收目录", indexEntry = false),
    QUOTA_DETAIL("额度监控", "Provider 与凭据 · 预警通知 · 后台刷新", indexEntry = false),
    LOCATION_DETAIL("位置采集", "四态权限引导 · 采集状态 · 采集覆盖", indexEntry = false),
    // 传送的专属设置单独一页：本机名称 + 后台接收 + 自动关闭 + 磁贴 + 提示音 + 免配对 + 阈值 +
    // 接收目录，本身就有 8 项，挤进「传送」开关页会超过每页 ≤ 7 项的上限。
    TRANSPORT_RECEIVING_DETAIL("传送 · 接收", "本机名称 · 后台接收 · 自动关闭 · 接收目录", indexEntry = false),
    TRANSPORT_ALERTS_DETAIL("传送 · 提醒与配对", "提示音 · 免配对窗口 · 体积阈值", indexEntry = false),
    TOTP_DETAIL("验证器", "TOTP 二次验证码 · 账户管理 · 备份与导入", indexEntry = false),
    WIDGET_DETAIL("桌面小组件", "隐藏金额 · 六个组件添加到桌面", indexEntry = false);

    companion object {
        /**
         * 一级页入口（DEBUG 是否出现由调用方按 debugUi 过滤，本清单不判闸门）。
         *
         * 顺序固定：监控与采集 / 功能开关 / 数据与备份 / 外观与动效 / 关于 / 调试与诊断。
         */
        val indexEntries: List<SettingsSubPage>
            get() = entries.filter { it.indexEntry }
    }
}

/**
 * 一级页真正渲染出来的入口：[debugUi] 为 false 时不出现「调试与诊断」整页。
 *
 * 抽成纯函数是为了不上设备就能钉住「debug 关闭时一级页恰好 5 组、开启时 6 组，且细则页不漏进来」。
 */
internal fun settingsIndexEntries(debugUi: Boolean): List<SettingsSubPage> =
    SettingsSubPage.indexEntries.filter { it != SettingsSubPage.DEBUG || debugUi }

/**
 * 「功能开关」页的行序（顺序须与 ui/SettingsFeatureIndexSection 的 featureIndexRows 一致）。
 *
 * 分母、摘要、计数全部由 [Feature] 派生，不在页头/行尾手写项数：
 * 手写期 TOTP 被漏掉，页头摘要与行尾计数都少一项（实测渲染 7 行）。
 * [Feature] 新增枚举项时 [settingsFeatureShortName] 的 when 会编译期报错，漏不掉。
 */
internal val settingsFeatureDisplayOrder: List<Feature> = listOf(
    Feature.LIVE,
    Feature.AD_SKIP,
    Feature.FINANCE,
    Feature.TRANSPORT,
    Feature.QUOTA,
    Feature.LOCATION,
    Feature.TOTP,
)

/** 功能 → 该功能的细则页（每项功能恰好一页；新增功能时这里编译期缺分支）。 */
internal fun settingsFeatureDetailPageOf(feature: Feature): SettingsSubPage = when (feature) {
    Feature.LIVE -> SettingsSubPage.LIVE_DETAIL
    Feature.AD_SKIP -> SettingsSubPage.SKIP_DETAIL
    Feature.FINANCE -> SettingsSubPage.FINANCE_DETAIL
    Feature.TRANSPORT -> SettingsSubPage.TRANSPORT_DETAIL
    Feature.QUOTA -> SettingsSubPage.QUOTA_DETAIL
    Feature.LOCATION -> SettingsSubPage.LOCATION_DETAIL
    Feature.TOTP -> SettingsSubPage.TOTP_DETAIL
}

/** 「功能开关」页的功能 → 各自细则页（按行序；每项功能恰好一页）。 */
internal fun settingsFeatureDetailPages(): List<SettingsSubPage> =
    settingsFeatureDisplayOrder.map(::settingsFeatureDetailPageOf)

/** 一级页摘要里用的短名（完整 title 含「监控 / 采集」后缀，7 项一行放不下）。 */
internal fun settingsFeatureShortName(feature: Feature): String = when (feature) {
    Feature.LIVE -> "实时"
    Feature.AD_SKIP -> "广告跳过"
    Feature.FINANCE -> "记账"
    Feature.TRANSPORT -> "传送"
    Feature.QUOTA -> "额度"
    Feature.LOCATION -> "位置"
    Feature.TOTP -> "验证器"
}

/** 一级页「功能开关」行的摘要：按行序列出每一项（单行，超出由 TokenRow 收省略号）。 */
internal fun settingsFeatureSummaryText(): String =
    settingsFeatureDisplayOrder.joinToString(" / ", transform = ::settingsFeatureShortName)

/**
 * 行尾计数「开 N / M」。
 *
 * M = [Feature] 条目数（不写字面量），N = 当前开着的功能数；[isOn] 由设置页的开关快照提供。
 */
internal fun settingsFeatureCountText(isOn: (Feature) -> Boolean): String =
    "开 " + Feature.entries.count(isOn) + " / " + Feature.entries.size

/**
 * 下钻页的父页：返回时回到父页而不是一路弹回一级页。
 *
 * 只有「功能开关 → 功能细则」与「传送 → 接收 / 提醒」这两层是下钻；一级页的 6 组
 * 没有父页（返回 = 关闭覆盖层回到一级页）。返回目标必须由**页面关系**决定，
 * 不能靠「上次打开的是谁」——那样会记住另一条分支的路径。
 */
internal fun settingsParentOf(page: SettingsSubPage): SettingsSubPage? = when (page) {
    SettingsSubPage.LIVE_DETAIL,
    SettingsSubPage.SKIP_DETAIL,
    SettingsSubPage.FINANCE_DETAIL,
    SettingsSubPage.TRANSPORT_DETAIL,
    SettingsSubPage.QUOTA_DETAIL,
    SettingsSubPage.LOCATION_DETAIL,
    SettingsSubPage.TOTP_DETAIL,
    SettingsSubPage.WIDGET_DETAIL,
    -> SettingsSubPage.FEATURE

    SettingsSubPage.TRANSPORT_RECEIVING_DETAIL,
    SettingsSubPage.TRANSPORT_ALERTS_DETAIL,
    -> SettingsSubPage.TRANSPORT_DETAIL

    SettingsSubPage.GENERAL,
    SettingsSubPage.FEATURE,
    SettingsSubPage.DATA,
    SettingsSubPage.APPEARANCE,
    SettingsSubPage.ABOUT,
    SettingsSubPage.DEBUG,
    -> null
}

/** 一级页入口与功能开关行的图标（同一 page 在两处用同一个图标，避免同一功能两个脸）。 */
internal fun settingsPageIcon(page: SettingsSubPage): ImageVector = when (page) {
    SettingsSubPage.GENERAL -> Icons.Outlined.Speed
    SettingsSubPage.FEATURE -> Icons.Outlined.Tune
    SettingsSubPage.DATA -> Icons.Outlined.Save
    SettingsSubPage.APPEARANCE -> Icons.Outlined.Palette
    SettingsSubPage.ABOUT -> Icons.Outlined.Info
    SettingsSubPage.DEBUG -> Icons.Outlined.BugReport
    SettingsSubPage.LIVE_DETAIL -> Icons.Outlined.Speed
    SettingsSubPage.SKIP_DETAIL -> Icons.Outlined.PlayArrow
    SettingsSubPage.FINANCE_DETAIL -> Icons.Outlined.Payments
    SettingsSubPage.TRANSPORT_DETAIL -> Icons.Outlined.SwapHoriz
    SettingsSubPage.TRANSPORT_RECEIVING_DETAIL -> Icons.Outlined.Settings
    SettingsSubPage.TRANSPORT_ALERTS_DETAIL -> Icons.Outlined.Settings
    SettingsSubPage.QUOTA_DETAIL -> Icons.Outlined.AccountBalanceWallet
    SettingsSubPage.LOCATION_DETAIL -> Icons.Outlined.LocationOn
    SettingsSubPage.TOTP_DETAIL -> Icons.Outlined.Security
    SettingsSubPage.WIDGET_DETAIL -> Icons.Outlined.Widgets
}
