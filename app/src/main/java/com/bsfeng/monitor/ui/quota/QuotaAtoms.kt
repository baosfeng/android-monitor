package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.AppCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.dotDiameter
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/**
 * 额度页的小原子：进度条、信息行、指标行、图例点。
 *
 * 单独成文件是为了让两张卡片（额度 / 用量）共用同一套行高与数值字体；
 * 分开各写一份会出现「同一页两种行距」。
 */

/** 带进度条的额度项（原型 .s18-meter）。 */
@Composable
fun QuotaMeter(
    title: String,
    valueText: String,
    fraction: Float?,
    meta: String?,
    modifier: Modifier = Modifier,
    /** 进度条下方的补充明细（如「743 / 50,000 credits」）；为空不占位。 */
    detail: String? = null,
) {
    val t = LocalDesignTokens.current
    // 标题 / 数值 / 重置时间挤一行：重置时间原来单独一行，两条进度条就多吃掉两行高度
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = t.fg2, modifier = Modifier.weight(1f))
        Text(valueText, style = monitorMonoMeta, color = t.fg)
        if (meta != null) {
            Spacer(Modifier.width(t.space2))
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (fraction != null) {
        Spacer(Modifier.height(t.space1))
        Box(
            Modifier
                .fillMaxWidth()
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.fgFade(10)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(meterColor(fraction)),
            )
        }
    }
    if (!detail.isNullOrBlank()) {
        Spacer(Modifier.height(t.space1))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = t.muted)
    }
}

/** 进度条语义色：20% 以下 danger、50% 以下 warn，其余序列色（原型 .is-low / .is-warn）。 */
@Composable
fun meterColor(fraction: Float): Color {
    val t = LocalDesignTokens.current
    return when {
        fraction <= 0.2f -> t.danger
        fraction <= 0.5f -> t.warn
        else -> t.chartSeq
    }
}

/** 一行「标题 — 数值」（原型 .s18-line）。 */
@Composable
fun InfoLine(title: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
        Text(value, style = monitorMonoMeta, color = t.fg)
    }
}

/** 用量指标行：左侧口径名可换行（「输入 / 缓存 / 输出」），右侧数值等宽右对齐。 */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.weight(1f))
        Text(value, style = monitorMonoMeta, color = t.fg)
    }
}

/** 图例点（原型 .s18-legend-dot：8px 圆角方块）。 */
@Composable
fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(t.dotDiameter)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(t.space1))
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/**
 * 一个 20dp 的「?」圆标：纯展示、不接收点击。
 *
 * 额度页的「为什么这样显示」类文案（百分比计划不提供金额、用量写入有延迟）
 * 以前常驻卡片底部，占掉三四行高度；改成按需展开，一屏才能放得下额度卡 + 用量卡。
 *
 * 名字与点击落在同位置的 [QuotaInfoTouch] 上：圆标自己带 contentDescription 会让读屏念两遍。
 */
@Composable
fun QuotaInfoBadge(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 「?」的触达层：48dp 透明可点区，与圆标同位置叠放，点开弹说明。
 *
 * 为什么不把圆标本身撑到 48dp：标注行只有 20dp 高，撑开会把整张卡顶高 28dp（视觉变了）。
 * 叠层放进与卡片内容同高的 Box 里，两者都不动——视觉 20dp、触达 48dp、卡片高度原样。
 */
@Composable
fun QuotaInfoTouch(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(t.tapMin)
            .clip(CircleShape)
            .clickable { open = true }
            .semantics { contentDescription = title },
    )
    AppDialog(visible = open, onDismiss = { open = false }) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space4))
        Button(
            onClick = { open = false },
            modifier = Modifier.fillMaxWidth().height(t.tapMin),
            shape = RoundedCornerShape(t.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.accentOn),
        ) {
            Text("知道了", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
