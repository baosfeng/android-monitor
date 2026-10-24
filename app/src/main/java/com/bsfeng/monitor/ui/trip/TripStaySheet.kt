package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：停留详情抽屉 —— TripStaySheet（底部抽屉内容）、placeSourceNote（位置来源说明）。负责「点开某段停留看期间的应用与页面轨迹」。

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun TripStaySheet(
    stay: Stay,
    dayStart: Long,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = placeDisplayName(stay),
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Badge("停留 " + formatSpanText(stay.durationMs))
        }
        Spacer(Modifier.height(t.space1))
        MetaText(
            formatSpanClock(stay.start, dayStart) + " – " + formatSpanClock(stay.end, dayStart) +
                " · " + placeSourceNote(stay.place),
        )

        Spacer(Modifier.height(t.space4))
        Text("期间应用", style = MaterialTheme.typography.labelLarge, color = t.muted)
        Spacer(Modifier.height(t.space2))
        if (stay.topApps.isEmpty()) {
            MetaText("这段时间没有应用使用记录")
        } else {
            // 模型侧给的是全部应用（按时长降序）；这里列前 8 条 + 「其他 N 个应用」，
            // 避免抽屉被长列表撑出屏，同时不会把剩下的 App 直接吞掉。
            visibleApps(stay.topApps).forEach { slice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = t.tapMin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = slice.app,
                        style = MaterialTheme.typography.bodyMedium,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSpanText(slice.ms),
                        style = MaterialTheme.typography.labelMedium,
                        color = t.muted,
                    )
                }
            }
            val rest = otherAppsNote(stay.topApps)
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(t.space1))
                MetaText(rest)
            }
        }

        Spacer(Modifier.height(t.space4))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text("关闭", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 地点来源：有坐标说定位，没坐标如实说是推算。 */
private fun placeSourceNote(place: Place): String = when (place.source) {
    Basis.GPS -> "地点来自定位"
    Basis.DEVICE -> "地点来自网络名与使用簇"
}
