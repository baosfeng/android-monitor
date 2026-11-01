package com.bsfeng.monitor.ui

// 二级页「功能开关」：六项功能各一行 —— 左侧开关决定功能开不开，右侧 chevron 进该功能的细则。
//
// 为什么开关和细则拆成两层：开关回答「要不要」，细则回答「怎么配」。放在同一屏时
// 关闭态的功能会折成一堆「开启后可用」占位行，六项叠加后正文 37 行（约 4.7 屏），
// 用户要滚很久才能找到真正想改的那一项。现在一屏六行、关闭态也一眼看得完。
//
// 广告跳过开关只在**这里**：一级页快捷区不再重复第二个（锚点 s09 重复项只留一处）。

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch

/**
 * 「功能开关」页正文：两张卡 —— 六项功能各一行开关 + chevron（6 项），
 * 以及无功能开关的「桌面小组件」（1 项）。合计 7 项，正好卡在单页上限。
 *
 * 桌面小组件放这里而不是塞进某张功能卡：它没有 Tab、没有功能开关，只有一组「添加到桌面」，
 * 挂在「记账 / 监控」卡末尾会让人以为它是那个功能的子项。
 */
@Composable
internal fun SettingsFeatureIndexSection(
    rt: SettingsScreenRuntime,
    onOpenDetail: (SettingsSubPage) -> Unit,
) {
    val t = rt.t
    val rows = featureIndexRows(rt)
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        rows.forEachIndexed { index, row ->
            TokenRow(
                title = row.feature.title,
                subtitle = row.subtitle,
                icon = settingsPageIcon(row.page),
                onClick = { onOpenDetail(row.page) },
                trailing = {
                    AppSwitch(
                        checked = row.enabled,
                        onCheckedChange = { rt.setFeature(row.feature, it) },
                        contentDescription = row.feature.title,
                    )
                    TailChevron()
                },
            )
            TokenDivider(first = index == 0)
        }
    }

    // 桌面小组件不在这里：它属于记账的呈现方式，入口已挪到「记账」细则页，
    // 本页严格保持 7 项（7 项功能各一行）。
}

/** 「功能开关」页的一行：功能、开关状态、摘要、下钻到哪一页。 */
private class FeatureIndexRow(
    val feature: Feature,
    val page: SettingsSubPage,
    val enabled: Boolean,
    val subtitle: String,
)

/**
 * 六行开关的状态与摘要。
 *
 * 关闭态副标题写「已关闭：…」，并保留 chevron 可进 —— 细则页里的规则 / 白名单 / 凭据
 * 在关闭时仍是可维护的只读配置（锚点 s09-skip「组内两行常驻」）。
 */
private fun featureIndexRows(rt: SettingsScreenRuntime): List<FeatureIndexRow> = listOf(
    FeatureIndexRow(
        feature = Feature.LIVE,
        page = SettingsSubPage.LIVE_DETAIL,
        enabled = rt.liveOn,
        subtitle = if (rt.liveOn) "实时事件流与前台 App 记录" else "已关闭：不生成「实时」Tab",
    ),
    FeatureIndexRow(
        feature = Feature.AD_SKIP,
        page = SettingsSubPage.SKIP_DETAIL,
        enabled = rt.adSkipOn,
        subtitle = if (rt.adSkipOn) {
            "规则 " + rt.ruleCount.value + " 条 · 白名单 " + rt.whitelistCount.value + " 个"
        } else {
            "已关闭：切 App 后不扫描页面、不执行跳过规则"
        },
    ),
    FeatureIndexRow(
        feature = Feature.FINANCE,
        page = SettingsSubPage.FINANCE_DETAIL,
        enabled = rt.financeOn,
        subtitle = if (rt.financeOn) "手动记账、统计与自动记账" else "已关闭：不生成「记账」Tab",
    ),
    FeatureIndexRow(
        feature = Feature.TRANSPORT,
        page = SettingsSubPage.TRANSPORT_DETAIL,
        enabled = rt.transportOn,
        subtitle = if (rt.transportOn) {
            "局域网互发文件 · 设置在本页，传送页只留收发"
        } else {
            "已关闭：不启动传送服务、不占端口、不做设备发现"
        },
    ),
    FeatureIndexRow(
        feature = Feature.QUOTA,
        page = SettingsSubPage.QUOTA_DETAIL,
        enabled = rt.quotaOn,
        subtitle = if (rt.quotaOn) {
            "聚合 AI 服务的余额与用量 · " + quotaRefreshLabel(rt)
        } else {
            "已关闭：不生成「额度」Tab，也不后台查询"
        },
    ),
    FeatureIndexRow(
        feature = Feature.LOCATION,
        page = SettingsSubPage.LOCATION_DETAIL,
        enabled = rt.locationOn,
        subtitle = if (rt.locationOn) LocationHealthText.PACE_SUMMARY else "已关闭：不读位置、不落坐标",
    ),
    FeatureIndexRow(
        feature = Feature.TOTP,
        page = SettingsSubPage.TOTP_DETAIL,
        enabled = rt.totpOn,
        // 没有底部 Tab：关闭态要说清「入口去哪儿了」，否则用户会去找 Tab
        subtitle = if (rt.totpOn) {
            "二次验证码 · 概览页右上角图标进入"
        } else {
            "已关闭：概览入口收起，本地密钥保留"
        },
    ),
)

/** 额度后台刷新频率的短标签（行尾与细则页同源）。 */
private fun quotaRefreshLabel(rt: SettingsScreenRuntime): String =
    QuotaSettings.labelOf(rt.quotaRefreshHoursState.value) + "刷新"
