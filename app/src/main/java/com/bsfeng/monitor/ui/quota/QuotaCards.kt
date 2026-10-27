package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.dotDiameter
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoNumber
import java.math.BigDecimal

/**
 * 额度页的实体卡片：provider chip、额度卡（百分比型 / 金额型）。
 *
 * 用量卡在 `QuotaUsageCard.kt`，五态（未配置 / 失败 / 未接入）在 `QuotaStateViews.kt`
 * —— 单文件超过 300 行会触发项目规范里的拆文件要求。
 */

/** provider 横向 chip 行（原型 .s18-chips：可横滑、已配置带绿点）。 */
@Composable
fun ProviderChipRow(
    providers: List<QuotaProviderInfo>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = t.space4),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items(providers, key = { it.id }) { info ->
            ProviderChip(
                info = info,
                selected = info.id == selectedId,
                onClick = { onSelect(info.id) },
            )
        }
    }
}

@Composable
private fun ProviderChip(
    info: QuotaProviderInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    // provider 切换等价于 Tab 切换：用中触（KEYBOARD_TAP 档），与列表点击的轻触区分
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "providerChipBg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "providerChipStroke",
    )
    Row(
        modifier = Modifier
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(t.radiusPill))
            .clickable {
                haptic(HapticTone.MEDIUM)
                onClick()
            }
            .padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        // 绿点只表示「已配置凭据且能拉数据」，未接入的 provider 恒为灰点
        Box(
            Modifier
                .size(t.dotDiameter)
                .clip(CircleShape)
                .background(if (info.connected) t.success else t.fgFade(30)),
        )
        Text(
            info.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.fg else t.fg2,
        )
    }
}

/**
 * 额度卡入口：按返回结构选排版，不按 provider id 选。
 *
 * Ollama 新旧计划、DeepSeek 余额都可能返回两种结构中的一种，用结构分流比用 id
 * 分流更耐改（新增 provider 时不用动这里）。
 */
@Composable
fun QuotaCard(
    providerId: String,
    state: QuotaRepository.ProviderState,
    plan: OllamaPlanMode,
    nowEpochSecond: Long,
    modifier: Modifier = Modifier,
) {
    val items = state.items
    if (items.isEmpty()) return
    if (items.any { it.kind == QuotaKind.PERCENT }) {
        PercentQuotaCard(providerId, items, plan, nowEpochSecond, modifier)
    } else {
        MoneyQuotaCard(providerId, items, plan, nowEpochSecond, modifier)
    }
}

/** 百分比限额卡（Ollama 旧版）：hero 是会话剩余，逐项进度条。 */
@Composable
private fun PercentQuotaCard(
    providerId: String,
    items: List<QuotaItem>,
    plan: OllamaPlanMode,
    nowEpochSecond: Long,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val primary = items.firstOrNull { it.primary } ?: items.first()
    val mismatch = QuotaFormat.planMismatchNote(plan, items, providerId)
    AppCard(modifier = modifier.then(percentCardTagOf(providerId))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(primary.title + "剩余", style = MaterialTheme.typography.bodySmall, color = t.muted)
            primary.badge?.let { badge ->
                Spacer(Modifier.width(t.space2))
                Badge(badge, tone = BadgeTone.Neutral)
            }
            Spacer(Modifier.weight(1f))
            QuotaInfoIcon(
                title = "计费口径",
                text = mismatch ?: "该计划按百分比限额计费，不提供金额明细；预警阈值按剩余百分比计算。",
            )
        }
        Spacer(Modifier.height(t.space1))
        Text(
            QuotaFormat.percent(primary.remaining),
            style = monitorMonoNumber,
            color = t.fg,
        )
        items.forEach { item ->
            Spacer(Modifier.height(t.space3))
            if (item.kind == QuotaKind.PERCENT) {
                QuotaMeter(
                    title = item.title,
                    valueText = "剩余 " + QuotaFormat.percent(item.remaining),
                    fraction = item.remainingFraction,
                    meta = QuotaFormat.resetText(item.period, item.resetsAtEpochSecond, nowEpochSecond),
                    detail = item.detailText,
                    modifier = meterTagOf(providerId, item, item == primary),
                )
            } else {
                // 百分比计划里也可能带回金额项（如已购余额）：按金额显示，
                // 不能套百分比进度条 —— 否则 0.002 美元会被读成「剩余 0.2%」。
                InfoLine(item.title, QuotaFormat.amount(item.remaining, item.unit))
            }
        }
        // 契约 s18-opendesign-cred：凭据是浏览器会话令牌而不是 API Key，说明常驻在月度额度卡里
        if (providerId == "opendesign") {
            Spacer(Modifier.height(t.space3))
            Column(Modifier.fillMaxWidth().testTag("s18-opendesign-cred")) {
                Badge("浏览器会话令牌", tone = BadgeTone.Neutral)
                Spacer(Modifier.height(t.space1))
                Text(
                    "open-design 只有控制台内部接口，需要填浏览器 Cookie 里的会话令牌（不是 API Key）。" +
                        "令牌会过期，失效后需回控制台重新复制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
}

/** 金额卡（Ollama 按金额 / DeepSeek 余额）：同一币种内相加，跨币种分行。 */
@Composable
private fun MoneyQuotaCard(
    providerId: String,
    items: List<QuotaItem>,
    plan: OllamaPlanMode,
    nowEpochSecond: Long,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val moneyItems = items.filter { it.kind == QuotaKind.MONEY }
    if (moneyItems.isEmpty()) return
    val primary = moneyItems.firstOrNull { it.primary } ?: moneyItems.first()
    val groups = moneyItems.groupBy { it.unit }
    val primaryUnit = primary.unit
    val primaryItems = groups[primaryUnit].orEmpty().ifEmpty { listOf(primary) }
    // DeepSeek 的赠金 / 充值：接口当前只给 total_balance，拆分行由数据层补齐后才出现（锚点按条件保留）
    val splitItems = if (providerId == "deepseek") moneyItems.filter { isDeepseekSplit(it) } else emptyList()
    // hero 取主项余额本身，不做同币种求和：Ollama 的「已购余额」是叠加在「内含额度」之外的
    // 另一笔，求和会让 hero 与下面进度条的「剩余/总量」对不上（原型 hero 也是主项值）。
    val hero = primary.remaining
    AppCard(modifier = modifier.then(moneyCardTagOf(providerId))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (providerId == "deepseek") "账户余额 · ${primaryUnit.name}" else "可用额度",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            Spacer(Modifier.weight(1f))
            QuotaInfoIcon(
                title = "额度口径",
                text = QuotaFormat.planMismatchNote(plan, items, providerId)
                    ?: "多币种分行展示，不做换算合并；余额型 provider 没有周期与总量。",
            )
        }
        Spacer(Modifier.height(t.space1))
        Text(QuotaFormat.amount(hero, primaryUnit), style = monitorMonoNumber, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text(
            heroSubtitle(providerId, primaryItems),
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        // 赠金 / 充值各一行：没有拆分字段时两条都不渲染（不显示编造的 0.00）
        if (providerId == "deepseek") {
            deepseekSplitItem(moneyItems, "赠金")?.let { item ->
                Spacer(Modifier.height(t.space2))
                InfoLine("赠金", QuotaFormat.amount(item.remaining, item.unit), Modifier.testTag("s18-deepseek-grant"))
            }
            deepseekSplitItem(moneyItems, "充值")?.let { item ->
                Spacer(Modifier.height(t.space2))
                InfoLine("充值", QuotaFormat.amount(item.remaining, item.unit), Modifier.testTag("s18-deepseek-topup"))
            }
        }
        primaryItems.forEach { item ->
            if (item in splitItems) return@forEach
            val fraction = item.remainingFraction
            val total = item.total
            // 只有一条无总量的余额项时，hero 已经把它显示完了，再列一行就是重复
            val coveredByHero = primaryItems.size == 1 && total == null
            if (coveredByHero) return@forEach
            Spacer(Modifier.height(t.space3))
            if (fraction != null && total != null) {
                QuotaMeter(
                    title = item.title,
                    valueText = QuotaFormat.amount(item.remaining, item.unit) +
                        " / " + QuotaFormat.amount(total, item.unit),
                    fraction = fraction,
                    meta = QuotaFormat.resetText(item.period, item.resetsAtEpochSecond, nowEpochSecond),
                    modifier = meterTagOf(providerId, item, item == primary),
                )
            } else {
                InfoLine(
                    item.title,
                    QuotaFormat.amount(item.remaining, item.unit),
                    moneyInfoTagOf(providerId, item),
                )
            }
        }
        // 其他币种逐行显示（同币种求和、不跨币种相加）：DeepSeek 的 CNY 与 USD 是两个独立账户
        groups.keys.filter { it != primaryUnit }.forEach { unit ->
            Spacer(Modifier.height(t.space2))
            val amount = groups[unit].orEmpty().fold(BigDecimal.ZERO) { acc, item -> acc + item.remaining }
            InfoLine("${unit.name} 余额", QuotaFormat.amount(amount, unit), deepseekUsdTagOf(providerId, unit))
        }
        // 另一币种单独一行：DeepSeek 的 USD 账户没开通时显式标出来，
        // 避免用户把「没有这一行」误读成「已折算进上面」
        if (providerId == "deepseek") {
            QuotaUnit.entries
                .filter { it == QuotaUnit.CNY || it == QuotaUnit.USD }
                .filter { groups[it] == null }
                .forEach { unit ->
                    Spacer(Modifier.height(t.space2))
                    Row(
                        modifier = deepseekUsdTagOf(providerId, unit),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${unit.name} 余额", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
                        Badge("未开通", tone = BadgeTone.Neutral)
                    }
                }
        }
    }
}

/** hero 下面那行：多条目时列出组成，单条目时给一句口径说明。 */
private fun heroSubtitle(providerId: String, items: List<QuotaItem>): String = when {
    items.size > 1 -> items.joinToString(" + ") { it.title }
    providerId == "deepseek" -> "余额来自 DeepSeek 账户"
    else -> "内含 + 已购"
}

/** 百分比卡锚点：Ollama 旧版 / opencode-go / open-design 各一张卡（契约 s18-legacy-* / s18-opencode-card / s18-opendesign-card）。 */
private fun percentCardTagOf(providerId: String): Modifier = when (providerId) {
    "ollama" -> Modifier.testTag("s18-legacy-quota")
    "opencode-go" -> Modifier.testTag("s18-opencode-card")
    "opendesign" -> Modifier.testTag("s18-opendesign-card")
    else -> Modifier
}

/** 金额卡锚点：Ollama 按金额 / DeepSeek 余额。 */
private fun moneyCardTagOf(providerId: String): Modifier = when (providerId) {
    "ollama" -> Modifier.testTag("s18-ollama-quota")
    "deepseek" -> Modifier.testTag("s18-deepseek-balance")
    else -> Modifier
}

/**
 * 卡片内部进度条锚点：契约按「哪一家的哪个窗口」逐条给了锚点。
 *
 * Ollama 旧版按条目的稳定 id（session / weekly）；opencode-go 按窗口短名（5 小时 / 每周 / 每月）；
 * open-design 只有一个窗口，锚点落在主项上（credits 明细由 detailText 承担）。
 */
private fun meterTagOf(providerId: String, item: QuotaItem, primary: Boolean): Modifier = when (providerId) {
    "ollama" -> when (item.id) {
        "included" -> Modifier.testTag("s18-ollama-included")
        "session" -> Modifier.testTag("s18-legacy-session")
        "weekly" -> Modifier.testTag("s18-legacy-weekly")
        else -> Modifier
    }
    "opencode-go" -> when (windowShortTitle(item.title)) {
        "5 小时" -> Modifier.testTag("s18-opencode-session")
        "每周" -> Modifier.testTag("s18-opencode-weekly")
        "每月" -> Modifier.testTag("s18-opencode-monthly")
        else -> Modifier
    }
    "opendesign" -> if (primary) Modifier.testTag("s18-opendesign-monthly") else Modifier
    else -> Modifier
}

/** 金额卡里的信息行锚点：Ollama 的「已购余额」是契约里唯一一条固定信息行。 */
private fun moneyInfoTagOf(providerId: String, item: QuotaItem): Modifier =
    if (providerId == "ollama" && item.id == "purchased") Modifier.testTag("s18-ollama-purchased") else Modifier

/** DeepSeek 的 USD 行：开通时显示数值、未开通时显示「未开通」角标，两种情况落同一锚点。 */
private fun deepseekUsdTagOf(providerId: String, unit: QuotaUnit): Modifier =
    if (providerId == "deepseek" && unit == QuotaUnit.USD) Modifier.testTag("s18-deepseek-usd") else Modifier

/** DeepSeek 拆分项（赠金 / 充值）：接口没有这两列，按条目标题识别，缺了整行不渲染。 */
private fun isDeepseekSplit(item: QuotaItem): Boolean =
    item.title.startsWith("赠金") || item.title.startsWith("充值")

private fun deepseekSplitItem(items: List<QuotaItem>, label: String): QuotaItem? =
    items.firstOrNull { it.title.startsWith(label) }
