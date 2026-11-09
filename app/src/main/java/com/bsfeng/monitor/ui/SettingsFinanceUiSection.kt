package com.bsfeng.monitor.ui

// s09 记账细则页的「记账功能（可按需开启）」分组（原型 s09-finance-group 四条：预算与预警 / 首页记账卡 /
// 多周期查询入口 / 预算设置）。
//
// 为什么放在「记账」细则页而不是一级页：一级页只回答「去哪儿」（见 ui/SettingsScreen.kt 的 KDoc），
// 平铺开关会让一级页退化成配置长列表；这四项又都是记账的呈现件，语义上属于「记账怎么配」。
//
// 三个开关的消费点各在别处（预算是记账页环图、首页卡是概览卡、多周期是记账页 unitbar）：
// 本文件只负责「拨一下」，不复制消费逻辑 —— 同一事实只能有一个判定点。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.performHaptic
import com.bsfeng.monitor.ui.foundation.FinanceUiPrefs
import com.bsfeng.monitor.ui.foundation.FinanceUiToggle

/**
 * 「记账功能（可按需开启）」分组：组头 + 一张卡内三行开关 + 一行预算页入口。
 *
 * @param onOpenBudget 进预算设置页；null = 宿主未接线，该行不渲染（不伪造跳转，同 s01-empty-cta 口径）
 */
@Composable
internal fun SettingsFinanceUiSection(
    rt: SettingsScreenRuntime,
    onOpenBudget: (() -> Unit)? = null,
) {
    val t = rt.t
    // 绑定持久化后端并装载一次（幂等）：设置页不依赖概览屏先被打开过。
    LaunchedEffect(Unit) { FinanceUiPrefs.attach(rt.context) }
    val values by FinanceUiPrefs.values.collectAsState()

    // 原型 s09-finance-group 是「组头 + 卡片」这一整块的包裹元素，组头与卡片各自还有子锚点
    // （grouphead / list）。这里把外层 Column 当落点：锚点挂在真实节点上，与两级子锚点不冲突。
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("s09-finance-group"),
    ) {
        GroupLabel(
            "记账功能（可按需开启）",
            Modifier.testTag("s09-finance-grouphead"),
        )
        AppCard(
            Modifier
                .padding(horizontal = t.space4)
                .testTag("s09-finance-list"),
            padding = 0.dp,
        ) {
            // 三行写成三个显式调用（不是 entries 循环 + 参数算 tag）：锚点必须能从 testTag 调用点
            // 逐字对账到账本（scripts/check-anchors.mjs 只认字面量与 ≤3 跳的常量/参数透传，
            // 「按枚举算出来的 tag」静态求值不到，会被判「清单外缺失」）。
            FinanceToggleRow(
                rt = rt,
                toggle = FinanceUiToggle.BUDGET,
                checked = values[FinanceUiToggle.BUDGET] ?: FinanceUiToggle.BUDGET.defaultOn,
                rowTag = "s09-finance-budget",
                switchTag = "s09-finance-budget-switch",
            )
            TokenDivider(first = false)
            FinanceToggleRow(
                rt = rt,
                toggle = FinanceUiToggle.HOME_CARD,
                checked = values[FinanceUiToggle.HOME_CARD] ?: FinanceUiToggle.HOME_CARD.defaultOn,
                rowTag = "s09-finance-homecard",
                switchTag = "s09-finance-homecard-switch",
            )
            TokenDivider(first = false)
            FinanceToggleRow(
                rt = rt,
                toggle = FinanceUiToggle.PERIOD_BAR,
                checked = values[FinanceUiToggle.PERIOD_BAR] ?: FinanceUiToggle.PERIOD_BAR.defaultOn,
                rowTag = "s09-finance-multiperiod",
                switchTag = "s09-finance-multiperiod-switch",
            )
            // 预算设置：宿主接线后才可点（onClick = null 时不加点击语义，行仍在 —— 锚点不随接线状态消失）。
            TokenRow(
                title = "预算设置",
                subtitle = if (onOpenBudget != null) {
                    "按需进入，不再占用记账首页"
                } else {
                    "入口待接线：当前从「记账」Tab 进预算页"
                },
                icon = Icons.Outlined.Savings,
                onClick = onOpenBudget,
                modifier = Modifier.testTag("s09-finance-budgetentry"),
                trailing = { TailChevron() },
            )
            Spacer(Modifier.height(t.space1))
        }
    }
}

/**
 * 一行开关：标题 + 「开启后得到什么」的副标题 + 行尾开关（锚点落在开关本身，拨它才是触发）。
 *
 * @param rowTag 行容器锚点（原型 data-od-id s09-finance-budget / -homecard / -multiperiod）
 * @param switchTag 开关锚点（锚点账本里「拨开关」这一行的目标）
 */
@Composable
private fun FinanceToggleRow(
    rt: SettingsScreenRuntime,
    toggle: FinanceUiToggle,
    checked: Boolean,
    rowTag: String,
    switchTag: String,
) {
    // 关闭态副标题加「已关闭：」前缀（s09 既有口径，见 SettingsLiveDetail / SettingsSkipFeatureCard）：
    // 光看开关的开合在语义树里读不出状态，副标题是唯一能被读屏与自动化对账到的文字证据。
    TokenRow(
        title = toggle.title,
        subtitle = if (checked) toggle.summary else "已关闭：" + toggle.summary,
        icon = when (toggle) {
            FinanceUiToggle.BUDGET -> Icons.Outlined.Speed
            FinanceUiToggle.HOME_CARD -> Icons.Outlined.BarChart
            FinanceUiToggle.PERIOD_BAR -> Icons.Outlined.DateRange
        },
        modifier = Modifier.testTag(rowTag),
        trailing = {
            AppSwitch(
                checked = checked,
                onCheckedChange = { enabled ->
                    // 值没变就不提示：重复点同一状态不该刷一条 toast
                    if (!FinanceUiPrefs.setEnabled(toggle, enabled)) return@AppSwitch
                    rt.view.performHaptic(HapticTone.SUCCESS)
                    rt.toast.show(toggle.title + if (enabled) "已开启" else "已关闭")
                },
                modifier = Modifier.testTag(switchTag),
                contentDescription = toggle.title,
            )
        },
    )
}
