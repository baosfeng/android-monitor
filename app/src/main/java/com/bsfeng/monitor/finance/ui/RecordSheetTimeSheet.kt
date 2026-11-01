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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
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
 *
 * **草稿式抽屉**：chip 与滚轮只改本地草稿 [draft]，点「确定」才通过 [onConfirm] 回写表单；
 * 点遮罩 / 返回键只调 [onDismiss]，抽屉卸载时草稿一起丢弃，时间行保持打开前的值。
 */
@Composable
internal fun BoxScope.TimeSheet(
    visible: Boolean,
    parts: RecordForm.TimeParts,
    onConfirm: (RecordForm.TimeParts) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val today = LocalDate.now()
    val shortcuts = RecordForm.dateShortcuts(today)
    // 初始值取打开抽屉那一刻的当前值；之后所有操作只动草稿（锚点 s14a-time-sheet 边界）
    var draft by remember { mutableStateOf(parts) }
    SheetShell(title = "选择时间", onDismiss = onDismiss) {
        // 锚点 s14a-time-sheet（「选择时间」抽屉）：贴在面板内容根上，关闭即随抽屉卸载
        Box(Modifier.fillMaxWidth().testTag("s14a-time-sheet")) {
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
                    // 锚点 s14a-date-strip：日期快捷 chip 条（今天 / 昨天 / 前天 / 10 月 14 日 …）
                    .testTag("s14a-date-strip")
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                shortcuts.forEach { shortcut ->
                    DateChip(
                        label = shortcut.label,
                        selected = shortcut.date == draft.date,
                        // 只改草稿：预览行立即跟手，抽屉保持打开（锚点 s14a-date-strip）
                        onClick = { draft = draft.copy(date = shortcut.date) },
                    )
                }
            }
        }
        Row(
            // 锚点 s14a-timepick：时 / 分双滚轮容器（中间高亮带 + 冒号分隔）
            modifier = Modifier.fillMaxWidth().padding(top = t.space4).testTag("s14a-timepick"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 锚点 s14a-wheel-hour：小时滚轮（00–23）
            Box(Modifier.testTag("s14a-wheel-hour")) {
                WheelColumn(
                    values = (0..23).map { twoDigits(it) },
                    selectedIndex = draft.hour,
                    onSelect = { draft = draft.copy(hour = it) },
                )
            }
            Text(
                ":",
                // 原型 .s14a-colon：--text-xl(28px)/600/--muted
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = t.textXl),
                color = t.muted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = t.space3),
            )
            // 锚点 s14a-wheel-minute：分钟滚轮（00–59）
            Box(Modifier.testTag("s14a-wheel-minute")) {
                WheelColumn(
                    values = (0..59).map { twoDigits(it) },
                    selectedIndex = draft.minute,
                    onSelect = { draft = draft.copy(minute = it) },
                )
            }
        }
        // 锚点 s14a-picked：预览行「今天 10:30:00」，自身无按下反馈、点击无响应
        Text(
            RecordForm.timeRowText(draft, today),
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = t.space3)
                .testTag("s14a-picked"),
        )
        Spacer(Modifier.height(t.space3))
        // 锚点 s14a-time-ok：「确定」—— 唯一把草稿写回表单的出口；未改任何值时等价于确认原值并关闭
        Box(Modifier.testTag("s14a-time-ok")) {
            SheetPrimaryButton(text = "确定", onClick = { onConfirm(draft) })
        }
        }
    }
}

/** 日期快捷 chip（原型 .chip + .s14a-strip） */
@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
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
            .testTag("chip.date")
            .clickable {
                haptic(HapticTone.LIGHT)
                onClick()
            }
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
