package com.bsfeng.monitor.widget

// 6 个组件的预览图内容 —— 只在测试源集里用，由 WidgetPreviewImageTest 渲染成 PNG 落到 res。
//
// 数据全是演示值：金额、账号名都取「一眼看得出是假的」的整数，不碰任何真实账目与真实账号
// （预览图会进 APK、进小米的安卓小部件列表，等于公开可读）。
//
// 结构同源约束：每块场景对应一个 XxxWidget.kt 的某一档，改那边的结构必须同步改这里。

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/** 演示用固定「现在」：额度重置时刻文案才稳定。 */
private val PREVIEW_NOW = LocalDateTime.of(2026, 11, 28, 10, 0)
    .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()

/** 记账 4×2：40sp 主数值 + 进度条 + 底栏两块分区（对应 FinanceWide）。 */
@Composable
internal fun FinancePreviewScene() {
    PreviewCard(face = PREVIEW_FINANCE) {
        PreviewHead(PREVIEW_FINANCE, "¥", "本月支出 · 11 月", trailing = usedText(0.71f))
        Spacer(Modifier.height(6.dp))
        Text(text = money(12345.0, hidden = false), style = previewNumberStyle(size = 40.sp), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        PreviewBar(progress = 0.71f, face = PREVIEW_FINANCE)
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            PreviewSection(PREVIEW_FINANCE, Modifier.weight(1f)) {
                PreviewStat("收入", money(8800.0, hidden = false))
            }
            Spacer(Modifier.width(8.dp))
            PreviewSection(PREVIEW_FINANCE, Modifier.weight(1f)) {
                PreviewStat("预算剩余", remainingText(0.71f, 1713.5, hasBudget = true, hidden = false))
            }
        }
    }
}

/** 监控健康 2×1：顶行状态 + 两列读数（对应 HealthCard 的 COMPACT 档）。 */
@Composable
internal fun HealthPreviewScene() {
    PreviewCard(face = PREVIEW_HEALTH) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PreviewBadge(PREVIEW_HEALTH, "♥", 22.dp)
            Spacer(Modifier.width(8.dp))
            PreviewDot(PREVIEW_INCOME)
            Spacer(Modifier.width(5.dp))
            Text(text = "监控中", style = previewValueStyle(), maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PreviewStat(
                label = "今日事件",
                value = count(42),
                modifier = Modifier.weight(1f),
                valueSize = 22.sp,
            )
            PreviewVDivider()
            Spacer(Modifier.width(10.dp))
            PreviewStat(
                label = "跳过",
                value = count(7),
                modifier = Modifier.weight(1f),
                valueSize = 22.sp,
            )
        }
    }
}

/** 跳过战报 2×1：今日 / 累计 / 确认率三列（对应 AdCompact）。 */
@Composable
internal fun AdSkipPreviewScene() {
    PreviewCard(face = PREVIEW_ADSKIP) {
        PreviewHead(PREVIEW_ADSKIP, "»", "跳过战报", badge = 22.dp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewStat("今日", count(3), Modifier.weight(1f), valueSize = 20.sp)
            PreviewVDivider()
            Spacer(Modifier.width(10.dp))
            PreviewStat("累计", count(128), Modifier.weight(1f), valueSize = 20.sp)
            PreviewVDivider()
            Spacer(Modifier.width(10.dp))
            PreviewStat("确认率", confirmRateText(121, 128), Modifier.weight(1f), valueSize = 20.sp)
        }
    }
}

/** 传送状态 2×1：顶行状态 + 底行在线台数（对应 TransferCard 的 COMPACT 档）。 */
@Composable
internal fun TransferPreviewScene() {
    PreviewCard(face = PREVIEW_TRANSFER) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PreviewBadge(PREVIEW_TRANSFER, "↕", 22.dp)
            Spacer(Modifier.width(8.dp))
            PreviewDot(PREVIEW_INCOME)
            Spacer(Modifier.width(5.dp))
            Text(text = "接收中", style = previewValueStyle(), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(text = "在线", style = previewLabelStyle(), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(text = count(3), style = previewNumberStyle(size = 22.sp), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(text = "台", style = previewLabelStyle(), maxLines = 1)
        }
    }
}

/** 快捷记一笔 1×1：实色主色块 + 「+」+「记一笔」（对应 QuickAddTile）。 */
@Composable
internal fun QuickAddPreviewScene() {
    PreviewSolidTile {
        Text(text = "+", style = previewNumberStyle(PREVIEW_ON_HUE, 26.sp), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(text = "记一笔", style = previewLabelStyle(PREVIEW_ON_HUE), maxLines = 1)
    }
}

/** 额度 4×2：顶栏 + 2×2 网格（复用 QuotaWidgetMirror，与真机同一套纯函数）。 */
@Composable
internal fun QuotaPreviewScene() {
    val grid = quotaWidgetGrid(widthDp = 350f, heightDp = 170f)
    val scale = QuotaWidgetMetrics.of(170f)
    val cellWidth = quotaCellWidthDp(350f, grid.columns, scale)
    val cells = quotaWidgetCells(
        accounts = quotaPreviewAccounts(),
        limit = grid.cells,
        nowEpochSecond = PREVIEW_NOW,
        captionWidthDp = cellWidth,
        zone = ZoneId.of("Asia/Shanghai"),
    )
    QuotaContentMirror(
        cells = cells,
        columns = grid.columns,
        showHeader = grid.showHeader,
        headerText = "额度 · 更新于 4 分钟前",
        scale = scale,
    )
}

/** 额度演示账号：与 QuotaWidgetShotTest 同一组演示值（账号名是产品名，不是用户账号）。 */
private fun quotaPreviewAccounts(): List<QuotaWidgetAccount> = listOf(
    QuotaWidgetAccount(
        instanceId = "preview-a",
        displayName = "opencode",
        items = listOf(percent("h", "5 小时", "12", at(11, 28, 17))),
    ),
    QuotaWidgetAccount(
        instanceId = "preview-b",
        displayName = "Ollama",
        items = listOf(percent("w", "本周", "96", at(11, 30, 0))),
    ),
    QuotaWidgetAccount(
        instanceId = "preview-c",
        displayName = "open-design",
        items = listOf(percent("m", "本月", "34", at(12, 1, 0))),
    ),
    QuotaWidgetAccount(
        instanceId = "preview-d",
        displayName = "余额示例",
        items = listOf(
            QuotaItem(
                id = "bal",
                title = "余额",
                kind = QuotaKind.MONEY,
                remaining = BigDecimal("8.60"),
                unit = QuotaUnit.CNY,
            ),
        ),
    ),
)

private fun percent(id: String, title: String, remaining: String, resetsAt: Long) = QuotaItem(
    id = id,
    title = title,
    kind = QuotaKind.PERCENT,
    remaining = BigDecimal(remaining),
    unit = QuotaUnit.PERCENT,
    resetsAtEpochSecond = resetsAt,
)

private fun at(month: Int, day: Int, hour: Int): Long =
    LocalDateTime.of(2026, month, day, hour, 0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
