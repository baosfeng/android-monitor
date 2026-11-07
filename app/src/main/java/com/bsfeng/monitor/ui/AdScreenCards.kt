package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页的单条记录详情面板。只接收渲染所需的值与回调，不持有状态。
// 摘要条 / 记录列表 / 趋势卡的实现留在 AdScreen.kt 内（页面结构与数据派生强耦合，拆出去反而要多传参数）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 单条跳过记录的详情面板内容（放在 BottomSheet 里，遮罩由 BottomSheet 承担）。 */
@Composable
internal fun AdRecordDetailContent(
    record: SkipRecord,
    onClose: () -> Unit,
    onCopySelector: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = record.displayName + " · 跳过成功",
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Badge(record.method.label + "匹配")
    }
    Spacer(Modifier.height(t.space3))
    DetailRow("发生时间", formatClockSec(record.timestampMs), first = true)
    DetailRow("命中规则", record.selector.ifBlank { PLACEHOLDER })
    DetailRow("响应耗时", record.costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
    DetailRow(
        "结果",
        when (record.confirmed) {
            true -> "已进入正片 · 未误触"
            false -> "点击后广告仍在（未确认）"
            null -> "未复核"
        },
    )
    DetailRow("包名", record.packageName.ifBlank { PLACEHOLDER })
    Spacer(Modifier.height(t.space4))
    // 点击区 ≥44dp（M3 Button 默认最小高 40dp，不够）· 焦点环 2dp · 触感 LIGHT（口径「普通按钮 LIGHT」）
    val shape = RoundedCornerShape(t.radiusPill)
    val haptic = rememberHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
        OutlinedButton(
            onClick = {
                haptic(HapticTone.LIGHT)
                onClose()
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = t.tapMin)
                .keyboardFocusRing(shape),
            shape = shape,
        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        Button(
            onClick = {
                haptic(HapticTone.LIGHT)
                onCopySelector()
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = t.tapMin)
                .keyboardFocusRing(shape),
            shape = shape,
        ) { Text("复制选择器", style = MaterialTheme.typography.labelLarge) }
    }
}
