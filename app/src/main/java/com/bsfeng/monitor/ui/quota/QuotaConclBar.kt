package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoNumber

/**
 * sticky 结论条（原型 .conclwrap + .concl）：永远显示剩余最低的那一个账号。
 *
 * 位置：任何滚动位置都钉在页头下沿（LazyColumn 的 stickyHeader），不压页头。
 * 环 = 44dp 的 conic 进度（剩余比例），顶部 6dp 圆点标出「起点就是剩余量」。
 */
@Composable
internal fun QuotaConclBar(leader: QuotaLeader?, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    Box(
        modifier
            .fillMaxWidth()
            // 原型 .conclwrap{padding:8px 16px;background:var(--bg)}：底色挡住从下面滚上来的行
            .background(t.bg)
            .padding(horizontal = t.space4, vertical = t.space2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 原型 .concl 高 65.6 = 内 49.6 + 上下各 8；三行文字按 CJK 行盒只有 45.2，
                // 用最小高度把卡片钉回原型高度（否则其后所有锚点整体上移 4dp）
                .heightIn(min = CONCL_CONTENT_HEIGHT)
                .testTag("s18-concl")
                .tokenShadow(1, shape)
                .clip(shape)
                .background(t.surface)
                .border(1.dp, t.borderSoft, shape)
                .padding(horizontal = t.space3, vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Start,
        ) {
            ConclRing(leader?.fraction)
            Spacer(Modifier.width(t.space3))
            Column(Modifier.weight(1f)) {
                FixedLine(13.dp, quotaTextSmall, "最低剩余", t.muted)
                FixedLine(
                    19.2.dp,
                    quotaLabel13.copy(fontWeight = FontWeight.Bold),
                    leader?.headline ?: "暂无额度数据",
                    t.fg,
                )
                FixedLine(13.dp, quotaTextSmall, conclDetailText(leader), t.muted)
            }
            Spacer(Modifier.width(t.space2))
            Text(
                text = leader?.valueText ?: "—",
                style = monitorMonoNumber,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 结论条最小总高度（原型 .concl 65.6；heightIn 约束的是含 padding 的总盒）。 */
private val CONCL_CONTENT_HEIGHT = 65.6.dp

/** 结论条副行：窗口名 + 重置时刻；没有重置时刻时只留窗口名。 */
private fun conclDetailText(leader: QuotaLeader?): String {
    if (leader == null) return "刷新后按剩余由低到高排列"
    val reset = leader.resetText
    return if (reset == null) leader.windowText else leader.windowText + " · " + reset + " 重置"
}

/** 44dp 环：剩余比例 0..1；0% 危险、≤20% 警告、其余序列色。 */
@Composable
private fun ConclRing(fraction: Float?) {
    val t = LocalDesignTokens.current
    val value = (fraction ?: 0f).coerceIn(0f, 1f)
    val color = when {
        value <= 0f -> t.danger
        value <= QUOTA_LOW_FRACTION -> t.warn
        else -> t.chartSeq
    }
    val track = t.fgFade(12)
    Canvas(Modifier.size(44.dp)) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (value > 0f) {
            drawArc(color, -90f, value * 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
        // 原型 .ring44::before：6dp 圆点压在 12 点方向
        drawCircle(color, 3.dp.toPx(), Offset(size.width / 2f, inset))
    }
}
