package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的私有组件；职责：导入页的结果小格、主/次按钮、进度文案、文件名解析与进度上限常量。

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 进度条封顶值：导入没有真实进度事件，动画停在 90% 直到结果返回。 */
internal const val PROGRESS_CEILING = 0.92f

/** 结果统计的一格（标签 + 数值）。 */
@Composable
internal fun ResultTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space1))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 导入主按钮（禁用时降透明度且不响应点击）。 */
@Composable
internal fun ImportActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(if (enabled) t.accent else t.accent.copy(alpha = 0.45f))
            .clickable { if (enabled) onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 导入页次级按钮。 */
@Composable
internal fun GhostActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .clickable { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = t.fg2)
    }
}

/** 进度文案按阶段推进（与原型的三段提示一致）。 */
internal fun progressLabel(value: Float): String {
    val percent = (value * 100).toInt().coerceIn(0, 100)
    val stage = when {
        value < 0.45f -> "正在解析文件… "
        value < 0.9f -> "正在写入本地数据库… "
        else -> "正在重建预算与统计… "
    }
    return stage + percent + "%"
}

/** 从 content URI 解析文件名（用于展示所选文件）。 */
internal fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}
