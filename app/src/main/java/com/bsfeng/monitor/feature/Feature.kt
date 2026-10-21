package com.bsfeng.monitor.feature

/**
 * 可开关的功能。
 *
 * 划分口径：**一项功能 = 一段会真干活的能力**（Tab、服务、扫描、后台任务），
 * 不是「页面上的一行开关」。默认值集中在本文件的 [FeatureCatalog.DEFAULTS]，UI 里不写字面量默认值。
 *
 * - [LIVE] / [FINANCE] / [TRANSFER] 各对应一个底部 Tab；
 * - [AD_SKIP] 对应开屏跳过引擎（规则匹配 + 点击），**不**对应「广告」Tab ——
 *   广告 Tab 是广告事件记录页，跳过引擎关掉后记录页照常可看。
 */
enum class Feature(
    /** 设置页卡片标题。 */
    val title: String,
    /** 一句话说明「这项功能做什么」，用于设置页卡片副标题。 */
    val summary: String,
    /** 持久化键；改名会丢用户已存的选择，保持稳定。 */
    val storageKey: String,
) {
    LIVE("实时监控", "实时事件流与前台 App 记录", "live"),
    AD_SKIP("广告跳过", "开屏广告自动识别并点击跳过", "ad_skip"),
    FINANCE("记账", "手动记账、统计与自动记账", "finance"),
    TRANSPORT("传送", "局域网互发文件与后台接收", "transport"),
}

/**
 * 底部 Tab。枚举顺序 = 历史顺序（概览 0 / 实时 1 / 广告 2 / 记账 3 / 传送 4 / 设置 5），
 * 通知深链的 `EXTRA_START_TAB` 用这个下标，顺序不要改。
 *
 * [feature] = null 表示常驻 Tab（概览 / 广告 / 设置不受开关影响）。
 */
enum class AppTab(val title: String, val feature: Feature?) {
    OVERVIEW("概览", null),
    LIVE("实时", Feature.LIVE),
    AD("广告", null),
    FINANCE("记账", Feature.FINANCE),
    TRANSFER("传送", Feature.TRANSPORT),
    SETTINGS("设置", null),
}

/**
 * 默认值、Tab 可见性、深链回退 —— 全部是纯函数，可在纯 JVM 单测里直接断言。
 */
object FeatureCatalog {

    /** 默认值（产品拍板）：传送默认关闭，其余功能默认开启。 */
    val DEFAULTS: Map<Feature, Boolean> = Feature.entries.associateWith { it != Feature.TRANSPORT }

    /** 深链目标不可用时回退到的 Tab。 */
    val FALLBACK_TAB: AppTab = AppTab.OVERVIEW

    fun defaultOf(feature: Feature): Boolean = DEFAULTS.getValue(feature)

    /** Tab 是否可见：常驻 Tab 恒可见，其余看所属功能是否开启。 */
    fun isTabVisible(tab: AppTab, enabled: (Feature) -> Boolean): Boolean =
        tab.feature?.let(enabled) ?: true

    /** 当前可见的 Tab（保持枚举顺序）。 */
    fun visibleTabs(enabled: (Feature) -> Boolean): List<AppTab> =
        AppTab.entries.filter { isTabVisible(it, enabled) }

    /** 深链 / 通知跳转落点：目标功能已关闭时回退 [FALLBACK_TAB]，不产生空 Tab。 */
    fun resolveTab(tab: AppTab, enabled: (Feature) -> Boolean): AppTab =
        if (isTabVisible(tab, enabled)) tab else FALLBACK_TAB

    /** 通知里携带的历史下标 → Tab（越界 / 非法一律回退，别让旧通知把 App 打崩）。 */
    fun tabOfIndex(index: Int): AppTab = AppTab.entries.getOrNull(index) ?: FALLBACK_TAB

    /** 功能关闭时，设置页卡片里那句提示。 */
    fun offHint(feature: Feature): String = "开启「" + feature.title + "」后可用"
}
