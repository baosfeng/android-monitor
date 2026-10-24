package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：AppField 文本输入（原型 .field：surface 底 + 1px 描边 + 44dp 高）。负责「填一个字」的输入面。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 文本输入（原型 .field：surface/surface-warm 底 + 1px 描边 + 44dp 高）。 */
@Composable
internal fun AppField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    warm: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (warm) t.surfaceWarm else t.surface)
            .border(1.dp, t.border, RoundedCornerShape(t.radiusMd))
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.fg),
            cursorBrush = SolidColor(t.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = t.muted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

// ── 屏头与行式排版（原型 .screen-head / .h1 / .h2 / .meta / .group-label / .row） ──
