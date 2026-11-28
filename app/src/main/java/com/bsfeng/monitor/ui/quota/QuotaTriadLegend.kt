package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 三态说明（原型 s19-tristate）：解释三种颜色的含义与「点通知去哪」。
 *
 * 纯展示、不可点，交互语义只留 testTag；三态数据的真值在主屏 [QuotaAlertTriadSection]。
 *
 * 单独成文件：它不属于任何一行（是卡片底的说明块），字号口径与 .srow 行盒也不同
 * （11px/1.3 + 14.3 行高），混在行文件里会让「这个文件管什么」说不清。
 */
@Composable
internal fun AlertTriadLegend() {
    val t = LocalDesignTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            // 原型 .tristate{padding:10px 12px}：上下 10，wrapper 另补 2 才到实测 71.9
            .padding(vertical = 10.dp)
            .testTag("s19-tristate"),
    ) {
        TriadLegendLine(t.warn, "快见底", "卡片转黄、组件同色；点通知直达该账号")
        Spacer(Modifier.height(4.dp)) // 原型 .tristate{gap:var(--space-1)}
        TriadLegendLine(t.danger, "已耗尽", "卡片转红并给「切主号 / 等重置」两条出路")
        Spacer(Modifier.height(4.dp))
        TriadLegendLine(t.success, "已恢复", "绿色回弹，附「从 4% → 96%」的变化")
    }
}

/**
 * 一行图例：8dp 圆点 + 11px 说明（原型 .ts）。
 *
 * 行高钉 14.3（原型 .ts 实测 14.3）：CJK 字体行盒会把它顶到 16.2，不钉住的话三行累计多 5.7
 * （parity 里正是 s19-tristate 的 h 差）。文字 unbounded 溢出，保证字形不被裁
 * （做法见[对齐台账](../../../../../../docs/UI设计/原型实现对齐台账.md)「行盒」节）。
 */
@Composable
private fun TriadLegendLine(color: Color, title: String, detail: String) {
    val t = LocalDesignTokens.current
    Row(Modifier.height(14.3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(t.space2))
        Text(
            title + " · " + detail,
            style = legendStyle(),
            color = t.muted,
            maxLines = 1,
            modifier = Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true),
        )
    }
}

/** 原型 .ts-t：11px / 400 / line-height 1.3（字阶无 11，按 copy 特例就地取，同 9sp 角标）。 */
@Composable
private fun legendStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.3.sp)
