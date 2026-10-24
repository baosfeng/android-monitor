package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 TimeSheet 与 DateChip；职责：选择日期与时刻的抽屉（日期快捷 chip + 双滚轮）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.time.LocalDate

/** 日期 chip 最小宽度（原型 .s14a-strip .chip 的 min-width:48px） */
private val CHIP_MIN_W: Dp = 48.dp

/**
 * 选择时间：日期快捷 chips + 时 / 分双滚轮。
 *
 * 解决「想记昨天 10 点吃饭」——原 s05 只有一个「今天」日期列表，选不了时刻。
 * 秒不单独选：新增时沿用当前秒，编辑时沿用原记录的秒（[RecordForm.TimeParts]）。
 */
@Composable
internal fun BoxScope.TimeSheet(
    visible: Boolean,
    parts: RecordForm.TimeParts,
    onDateChange: (String) -> Unit,
    onHourMinuteChange: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val today = LocalDate.now()
    val shortcuts = RecordForm.dateShortcuts(today)
    SheetShell(title = "选择时间", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "日期",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.width(t.tapMin),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                shortcuts.forEach { shortcut ->
                    DateChip(
                        label = shortcut.label,
                        selected = shortcut.date == parts.date,
                        onClick = { onDateChange(shortcut.date) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = t.space4),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                values = (0..23).map { twoDigits(it) },
                selectedIndex = parts.hour,
                onSelect = { onHourMinuteChange(it, parts.minute) },
            )
            Text(
                ":",
                // 原型 .s14a-colon：--text-xl(28px)/600/--muted
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = t.textXl),
                color = t.muted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = t.space3),
            )
            WheelColumn(
                values = (0..59).map { twoDigits(it) },
                selectedIndex = parts.minute,
                onSelect = { onHourMinuteChange(parts.hour, it) },
            )
        }
        Text(
            RecordForm.timeRowText(parts, today),
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = t.space3),
        )
        Spacer(Modifier.height(t.space3))
        SheetPrimaryButton(text = "确定", onClick = onDismiss)
    }
}

/** 日期快捷 chip（原型 .chip + .s14a-strip） */
@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    // 原型 .chip：底透明 + 1px --border + --fg-2 字；
    // 选中 .chip.is-on = 底 color-mix(fg 7%) + 边 color-mix(fg 24%) + --fg 字 600（不是黑底白字）
    Box(
        modifier = Modifier
            .widthIn(min = CHIP_MIN_W)
            .heightIn(min = t.tapMin)
            .clip(shape)
            .background(if (selected) t.fgFade(7) else Color.Transparent)
            .border(1.dp, if (selected) t.fgFade(24) else t.border, shape)
            .clickable { onClick() }
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) t.fg else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
