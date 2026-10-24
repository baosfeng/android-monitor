package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：口径说明 —— TripRulesCard（卡片外壳）、RuleRow（一条规则）。负责「这些数字是怎么算出来的」。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun TripRulesCard(basis: Basis, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        CardHead("口径说明", basisBadge(basis))
        Spacer(Modifier.height(t.space2))
        RuleRow("停留", "连续 10 分钟以上停在同一地点")
        RuleRow("移动", "两点间净位移超过 300 米，且定位覆盖不少于 60%")
        RuleRow("未记录", "采集有中断，期间内容不做推断")
        if (basis == Basis.DEVICE) {
            RuleRow("地点名", "来自当前网络名与 App 使用簇；开启定位后可显示距离与地址")
        }
    }
}

@Composable
private fun RuleRow(title: String, desc: String) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = t.space2)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

// ───────────────────────────── 停留详情抽屉 ─────────────────────────────
