package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的私有组件；职责：导入页的结果小格、主/次按钮、进度文案、文件名解析与进度上限常量。

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.util.Locale

/** 进度条封顶值：导入没有真实进度事件，动画停在 92% 直到结果返回（锚点 s08-progress）。 */
internal const val PROGRESS_CEILING = 0.92f

/** 结果统计的一格（标签 + 数值）。三格不拆成三个 Tab 停位，见 [ImportResultSummaryCard]。 */
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

/**
 * 导入主按钮（锚点 s08-go）。
 *
 * 口径：按下底加深一档（--dur-state，触感 MEDIUM）→ 转「导入中…」并禁用；禁用态 45% 透明、
 * 不可聚焦、读屏「开始导入，不可用，请先选择文件」；焦点环 2dp 画在按钮内侧，不改高度与圆角。
 */
@Composable
internal fun ImportActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusLg)
    val base = if (enabled) t.accent else t.accent.copy(alpha = 0.45f)
    // 加深一档：与 fg 混 8%（双主题下都表现为「底更实」）；禁用态不变色
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) lerp(base, t.fg, 0.08f) else base,
        animationSpec = tween(t.durState),
        label = "importGoBg",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .keyboardFocusRing(shape)
            .semantics { if (!enabled) stateDescription = "不可用，请先选择文件" }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptic(HapticTone.MEDIUM)
                onClick()
            }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("button.primary"),
        )
    }
}

/** 导入页次级按钮（重试失败项 / 再导一个文件）：按下底加深一档（--dur-state）+ LIGHT + 焦点环。 */
@Composable
internal fun GhostActionButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusLg)
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) lerp(t.surfaceWarm, t.fg, 0.06f) else t.surfaceWarm,
        animationSpec = tween(t.durState),
        label = "importGhostBg",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = t.fg2,
            modifier = Modifier.testTag("button.ghost"),
        )
    }
}

/**
 * 进度文案按阶段推进（口径三段：解析 → 写入本地数据库 → 重建预算与统计）。
 *
 * 段界取口径的 45% / 90%；文件格式沿用产品现状（.db），文案不写「CSV」。
 */
internal fun progressLabel(value: Float): String {
    val percent = (value * 100).toInt().coerceIn(0, 100)
    val stage = when {
        value < 0.45f -> "正在解析文件… "
        value < 0.9f -> "正在写入本地数据库… "
        else -> "正在重建预算与统计… "
    }
    return stage + percent + "%"
}

/** 文件大小展示（口径「1.2 MB」：一位小数，KB 取整）。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
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
