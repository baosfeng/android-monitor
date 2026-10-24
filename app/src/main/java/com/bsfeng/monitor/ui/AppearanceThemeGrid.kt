package com.bsfeng.monitor.ui

// 从 ui/AppearanceScreen.kt 拆出：主题选项卡 ThemeGridItem（原型 s13 的 .theme-opt / .theme-swatch）——
// 3 色小样 + 中英文名 + 当前标记。负责「一个主题长什么样」，网格排布仍在 AppearanceScreen.kt。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 主题选项卡（原型 s13 的 .theme-opt / .theme-swatch，s13-appearance.html:13-23）：
 * 左 = 46×36 三段色卡（底 0-34% / 表面 34-64% / 文字 64-100%，140° 线性渐变）；
 * 右 = 中文（17sp）+ 英文（12sp muted）两行；
 * 选中态 = 描边加粗到 fg 40% + 文字转 fg + 字重 600（原型 .theme-opt[aria-pressed="true"]）。
 */
@Composable
internal fun ThemeGridItem(
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val preview = DesignTokens(id)
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) t.fgFade(40) else t.borderSoft,
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 36.dp)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to preview.bg,
                            0.34f to preview.bg,
                            0.34f to preview.surface,
                            0.64f to preview.surface,
                            0.64f to preview.fg2,
                            1f to preview.fg2,
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                ),
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                themeNameCn(id),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) t.fg else t.fg2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                themeNameEn(id),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
        }
    }
}

