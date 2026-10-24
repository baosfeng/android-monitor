package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过记录行 —— 时间列 + 应用图标 + 应用名 + 跳过方式说明。
// 负责「一条跳过记录长什么样」；详情抽屉仍在 AdScreen.kt。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun SkipRecordRow(record: SkipRecord, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            .clickable { onClick() }
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClockSec(record.timestampMs),
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
            modifier = Modifier.width(t.space12),
        )
        Spacer(Modifier.width(t.space1))
        // s03 跳过记录行以应用为主体（原型 .s03 列表每行都是 .app-icon）→ 用应用自身图标，
        // 取不到真实图标时 AppIcon 内部回落「品牌色底 + 应用首字」。旧记录缺 package_name 时同样走兜底。
        AppIcon(packageName = record.packageName.takeIf { it.isNotBlank() }, appName = record.displayName)
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = methodSubtitle(record.method, record.costMs),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
