package com.bsfeng.monitor.widget

// 小组件的数据口径：全部不可变、不依赖 Android，UI 层只渲染这些字段。

/*
 * 为什么不直接把 FinanceStats / QuotaSnapshot 传给 Glance：
 * 1. 组件刷新时进程可能刚被拉起，所有数据必须能从 SQLite / SharedPreferences 重建，
 *    所以这里不能出现流、句柄或 ViewModel；
 * 2. 读取逻辑（WidgetDataProvider）与渲染（T2/T3）分开改，互不踩脚；
 * 3. 这些类能纯 JVM 构造，配合 WidgetFormat 的纯函数才好单测。
 */

/**
 * 记账月度概览。
 *
 * @param monthLabel 短月份标签，如「10 月」：组件只展示当月，不跟随记账页的月份切换
 * @param budgetProgress 总预算进度 0..1；未设预算时为 null
 * @param budgetRemaining 总预算剩余金额（已按 0 兜底）；未设预算时为 null
 * @param hasBudget 是否设过总预算（或任一分项预算）
 */
data class FinanceWidgetData(
    val monthLabel: String,
    val expense: Double,
    val income: Double,
    val budgetProgress: Float?,
    val budgetRemaining: Double?,
    val hasBudget: Boolean,
)

/** 监控健康：服务是否真在干活 + 今日事件 / 跳过计数。 */
data class HealthWidgetData(
    val serviceRunning: Boolean,
    val todayEvents: Int,
    val todaySkips: Int,
)

/**
 * 额度榜单。
 *
 * @param configured 是否至少配了一家 provider 的额度凭据；false = 组件该显示「未配置额度」
 * @param rows 按剩余百分比升序，第一行 = 最先见底的那家
 * @param fetchedAtText 最近一次成功拉取的相对时间文案（如「更新于 5 分钟前」）；从未拉取过为 null
 */
data class QuotaWidgetData(
    val configured: Boolean,
    val rows: List<QuotaRow>,
    val fetchedAtText: String?,
)

/**
 * 额度榜单一行（一家 provider）。
 *
 * @param remainingPercent 主指标剩余百分比 0..100，越低越紧急
 */
data class QuotaRow(
    val providerId: String,
    val label: String,
    val remainingPercent: Int,
)

/**
 * 广告跳过战报：今日 / 近 7 天 / 累计的 AD_SKIPPED 条数，外加已确认条数。
 *
 * @param confirmedCount 累计跳过里「点击后复核确认广告已消失」的条数（detail 的 `confirmed=true`）；
 *   确认率 = `confirmedCount / totalCount`，`totalCount = 0` 时不算（组件显示「—」）
 */
data class AdWidgetData(
    val todayCount: Int,
    val weekCount: Int,
    val totalCount: Int,
    val confirmedCount: Int,
)

/**
 * 传送状态（纯内存态，进程回收即丢）。
 *
 * @param receiving 端口常驻**且**可被发现：只 running 时对端也搜不到本机
 */
data class TransferWidgetData(
    val running: Boolean,
    val onlineDevices: Int,
    val receiving: Boolean,
)
