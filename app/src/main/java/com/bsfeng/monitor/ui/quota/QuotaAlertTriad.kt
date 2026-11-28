package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.Instant
import java.time.ZoneId

/**
 * 主屏「恢复提醒 · 三态」的数据契约与组件（原型 s18-alerts）。
 *
 * 三态口径：
 * - [QuotaAlertTriadKind.RECOVERED] 来自**事件记录**（本周期提醒过又回升，见
 *   [com.bsfeng.monitor.domain.quota.QuotaAlertEvaluator.Recovery]），只留最近几条；
 * - [QuotaAlertTriadKind.LOW] / [QuotaAlertTriadKind.DEAD] 从当前快照实时推导 ——
 *   恢复后再降下去时不会同时出现「已恢复」与「快见底」两行。
 *
 * 为什么先算成一份不可变列表：主屏三态区、恢复通知、桌面组件要的是同一个
 * 「谁该被看见」的答案，各算一套必然出现「主屏说快见底、通知却没发」的双口径。
 */
enum class QuotaAlertTriadKind { RECOVERED, LOW, DEAD }

/** 三态区的一行（原型 .arow：圆点 + 标题 + 副行）。 */
data class QuotaAlertTriadRow(
    val kind: QuotaAlertTriadKind,
    val instanceId: String,
    /** 账号展示名（`Ollama 主号` / `open-design 工作区 A`）。 */
    val displayName: String,
    /** 窗口名：5 小时 / 本周 / 本月 / 余额。 */
    val itemTitle: String,
    /** 0..1；余额型为 0（比例对纯余额没有意义）。 */
    val fraction: Float,
    /** 例如 "34%" / "¥8.60"。 */
    val fractionText: String,
    /** 例如 "12-01 00:00 重置 · 约 4 天耗尽"；不含「N 分钟前」这类相对时间。 */
    val detailText: String,
    val occurredAtEpochSecond: Long,
    /** 仅 RECOVERED：恢复前提醒过的剩余比例（4% → 0.04）。 */
    val recoveredFrom: Float? = null,
)

/** 三态区的完整输入（空 = 整块不渲染）。 */
data class QuotaAlertTriad(val rows: List<QuotaAlertTriadRow> = emptyList()) {
    /** 空态判据：三态都没有内容时主屏不占位（原型没有「没有提醒」的卡片）。 */
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * 主屏三态区（原型 s18-alert-head + s18-alerts）。
 *
 * 自带组标题与左右 16dp 外边距（原型 `.sect` / `.alerts` 各带 `margin: 0 var(--space-4)`），
 * 调用方直接放进内容区即可，不要再叠加水平内边距。
 *
 * 空态整块返回、不留空白：原型没有空态卡片，留一个空标题会让人以为加载失败。
 */
@Composable
fun QuotaAlertTriadSection(
    triad: QuotaAlertTriad,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (triad.isEmpty) return
    val t = LocalDesignTokens.current
    // 相对时间在组合时取一次：三态区只在快照变化时重组，不必每帧读时钟
    val now = remember(triad) { System.currentTimeMillis() / 1000 }

    Column(modifier.padding(horizontal = t.space4)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = t.space3, bottom = t.space1)
                .testTag("s18-alert-head"),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "恢复提醒 · 三态",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            Text("${triad.rows.size} 条", style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
        AppCard(padding = 0.dp, modifier = Modifier.testTag("s18-alerts")) {
            triad.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    // 原型 .arow+.arow{border-top} 通宽，不缩进
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
                }
                AlertTriadRow(row = row, nowEpochSecond = now, onOpenSettings = onOpenSettings)
            }
        }
    }
}

/** 一行：10dp 圆点（原型 .adot）+ 标题 + 副行；整行可点进设置页。 */
@Composable
private fun AlertTriadRow(
    row: QuotaAlertTriadRow,
    nowEpochSecond: Long,
    onOpenSettings: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val dotColor = when (row.kind) {
        QuotaAlertTriadKind.RECOVERED -> t.success
        QuotaAlertTriadKind.LOW -> t.warn
        QuotaAlertTriadKind.DEAD -> t.danger
    }

    Row(
        Modifier
            .fillMaxWidth()
            // 原型 .arow{min-height:48px;padding:6px 12px}
            .heightIn(min = 48.dp)
            // 实参必须直接是「一层函数调用」：锚点门禁不追局部 val（见 triadAnchorOf 的说明）
            .testTag(triadAnchorOf(row.kind))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onOpenSettings()
            }
            .semantics { role = Role.Button }
            .padding(horizontal = t.space3, vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp) // 原型 .adot 10×10：比列表圆点大一档，三态靠颜色区分
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(t.space2))
        Column(Modifier.weight(1f)) {
            Text(
                alertTriadTitle(row),
                style = MaterialTheme.typography.labelLarge,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                alertTriadDetail(row, nowEpochSecond),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 三态行的锚点（原型 s18-alert-back / -low / -dead）。
 *
 * 写成函数而不是行内 `val tag = when { ... }`：锚点门禁从 testTag 调用点出发追证据，
 * 只认「一层函数调用 / 大写常量」；局部 val 会被判成缺失。
 */
private fun triadAnchorOf(kind: QuotaAlertTriadKind): String = when (kind) {
    QuotaAlertTriadKind.RECOVERED -> "s18-alert-back"
    QuotaAlertTriadKind.LOW -> "s18-alert-low"
    QuotaAlertTriadKind.DEAD -> "s18-alert-dead"
}

/** 标题：`Ollama 主号 · 本周已恢复` / `open-design 工作区 A · 快见底 34%` / `opencode-go 备用 · 5 小时已耗尽`。 */
internal fun alertTriadTitle(row: QuotaAlertTriadRow): String = when (row.kind) {
    QuotaAlertTriadKind.RECOVERED -> "${row.displayName} · ${row.itemTitle}已恢复"
    QuotaAlertTriadKind.LOW -> "${row.displayName} · 快见底 ${row.fractionText}"
    QuotaAlertTriadKind.DEAD -> "${row.displayName} · ${row.itemTitle}已耗尽"
}

/**
 * 副行：恢复行前面补「N 分钟前」，其余直接用推导好的明细。
 *
 * 恢复行的「已发『已恢复』」写在明细里：用户最想知道的是「这条是不是真发过通知」。
 */
internal fun alertTriadDetail(row: QuotaAlertTriadRow, nowEpochSecond: Long): String =
    if (row.kind == QuotaAlertTriadKind.RECOVERED) {
        alertAgoText(row.occurredAtEpochSecond, nowEpochSecond) + " " + row.detailText
    } else {
        row.detailText
    }

/** 相对时间：`22 分钟前` / `3 小时前` / `2 天前`（原型 s18-alert-back）。 */
internal fun alertAgoText(occurredAtEpochSecond: Long, nowEpochSecond: Long): String {
    val minutes = ((nowEpochSecond - occurredAtEpochSecond).coerceAtLeast(0L)) / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}

/** 周期重置的短文案：`今天 17:00` / `明天 00:00` / `12-01 00:00`；没有重置时刻时为 null。 */
internal fun alertResetShort(
    resetsAtEpochSecond: Long?,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val ts = resetsAtEpochSecond ?: return null
    val at = Instant.ofEpochSecond(ts).atZone(zone)
    val now = Instant.ofEpochSecond(nowEpochSecond).atZone(zone)
    val clock = java.lang.String.format(java.util.Locale.US, "%02d:%02d", at.hour, at.minute)
    return when {
        at.toLocalDate() == now.toLocalDate() -> "今天 $clock"
        at.toLocalDate() == now.toLocalDate().plusDays(1) -> "明天 $clock"
        else -> java.lang.String.format(java.util.Locale.US, "%02d-%02d ", at.monthValue, at.dayOfMonth) + clock
    }
}
