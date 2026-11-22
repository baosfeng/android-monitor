package com.bsfeng.monitor.finance.ui

// 来源：原型 s07「本月日均」卡（.open-design/src/screens/s07-统计分析.html:62-70）；
// 职责：卡片的组装与朗读（锚点 s07-dailyavg / s07-dailyavg-amount / s07-dailyavg-note），
// 口径本身在 FinanceMonthPace（monthElapsedDays / monthDailyAverage），本文件不重算除法。

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.monthDailyAverage
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoNumber

/** 日均单位：原型 .kpi-unit 的文案（--text-sm 14px/500 + muted）。 */
internal const val S07_DAILY_AVG_UNIT: String = "元/天"

/**
 * 卡片标题的行盒高度：原型 .h2 的 line-height var(--leading-tight)=1.05 → 21px × 1.05 = 22.05px。
 *
 * 为什么要单独钉住：21sp 中文的字形度量约 1.476em（31dp），Compose 的 Text 自然行盒就是 31dp，
 * 直接把标题放进卡片会让这一行比原型高 9dp、整卡高 7%（与原型并排一眼可见）。
 * 这里用「22dp 定高盒 + 不裁剪的居中放置」复刻 CSS 的负 half-leading：行盒 22dp，字形不裁。
 * 不动 monitorTypography 的 headlineSmall：改全局字阶会牵动 8 套主题与全站换行，属对齐台账的
 *「已知口径差」，不在本功能范围内。
 */
internal val S07_DAILY_AVG_TITLE_LINE_HEIGHT = 22.sp

/**
 * 本月日均卡（原型 62-70）：标题「本月日均」+ 右上口径 + 大号数字与单位 + 一行可复算的口径。
 *
 * 位置：紧跟 KPI 三格（s07-kpicard）、在时间范围卡之前 —— 这两块的数据源都是**浏览月**
 * （同一个 stats.totalExpense），日均就是这个月的花钱节奏；时间范围卡是「五档 + 年周均 /
 * 年累计」的固定口径块，把月度节奏塞进去会让两种口径挨在一起。
 *
 * 分母 ≤ 0（月份条翻到未来月）时大号数字给占位「—」：把「这一天还没到」说成「日均 ¥0」是错的信息。
 * 数字/单位/说明与原型逐字同源（¥X / 元/天 / 本月累计 ¥X · 已过 N 天），改文案要两边一起改。
 */
@Composable
internal fun FinanceStatsDailyAvgCard(
    monthExpense: Double,
    elapsedDays: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val titleBoxHeight = with(LocalDensity.current) { S07_DAILY_AVG_TITLE_LINE_HEIGHT.toDp() }
    val amountText = if (elapsedDays > 0) yen(monthDailyAverage(monthExpense, elapsedDays), 0) else PLACEHOLDER_AMOUNT
    val noteText = monthDailyAverageNote(monthExpense, elapsedDays)
    // 整卡一个焦点停位、整体朗读（与 s07-kpicard / s07-ringcard 同一交互口径）
    val spoken = "本月日均 " + amountText + " " + S07_DAILY_AVG_UNIT + "，" + noteText
    Column(
        modifier
            .padding(horizontal = t.space4)
            .padding(top = t.space3)
            .testTag("s07-dailyavg")
            .focusable()
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.height(titleBoxHeight), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "本月日均",
                        style = MaterialTheme.typography.headlineSmall,
                        color = t.fg,
                        maxLines = 1,
                        modifier = Modifier.wrapContentHeight(unbounded = true, align = Alignment.CenterVertically),
                    )
                }
                // 原型右上角是 .meta（14px muted），不是卡片标题旁常用的 labelSmall（12sp）
                Text("支出口径", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
            Spacer(Modifier.height(t.space2))
            // 大号数字 + 单位：原型 .kpi-value(28px/700 mono) + .kpi-unit(14px/500 muted, margin-left 4px)，
            // 两者基线对齐（原型 .s07-avg 的 align-items:baseline），不是底对齐。
            Row(
                modifier = Modifier.fillMaxWidth().testTag("s07-dailyavg-amount"),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    amountText,
                    style = monitorMonoNumber,
                    color = t.expenseText,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(t.space1))
                Text(
                    S07_DAILY_AVG_UNIT,
                    style = MaterialTheme.typography.labelMedium,
                    color = t.muted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(t.space2))
            Text(
                noteText,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                // 原型这行是块级 .meta（宽 = 卡内容宽），fillMaxWidth 让锚点盒子与原型同宽
                modifier = Modifier.fillMaxWidth().testTag("s07-dailyavg-note"),
            )
        }
    }
}

/**
 * 口径说明行：「本月累计 ¥X · 已过 N 天」（原型 64 的 .meta 文案）。
 *
 * 写出来的目的与「周均 = 年累计 ÷ 该年已过去周数」一致：日均是页面上第二处除法，
 * 不把分子分母都写出来，读者没法自己复算，也没法判断「已过天数」是整月还是到今天。
 * 累计与天数都用 0 位小数，与上面的大号数字同一精度。
 */
internal fun monthDailyAverageNote(monthExpense: Double, elapsedDays: Int): String =
    "本月累计 " + yen(monthExpense, 0) + " · 已过 " + elapsedDays + " 天"
