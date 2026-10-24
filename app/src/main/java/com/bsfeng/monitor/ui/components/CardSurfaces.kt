package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：卡片类容器 —— appBackdrop / tokenShadow 修饰符、AppCard、GlassCard、KpiCard、SectionHeader。负责「内容装进什么面」。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.sparkHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape

/** 页面底衬：浅色玻璃必须压在可见底衬上才成立（契约 §4）。 */
@Composable
fun Modifier.appBackdrop(): Modifier {
    val t = LocalDesignTokens.current
    // drawWithCache：build 块只在尺寸 / token 变化时执行；全屏底衬随可见区每次滚动重绘，
    // 逐帧重跑的只剩 drawRect，不再每帧 new Brush + listOf。
    return this.drawWithCache {
        val brush = Brush.verticalGradient(
            listOf(t.backdropA, t.backdropB),
            startY = 0f,
            endY = size.height,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * 层级阴影（原型 --elev-1/2/3）：Compose 侧用 shadow 近似 CSS box-shadow，
 * 色相取 --fg 的透明度（1 → 0 1px 2px fg 7%；2 → 0 10px 28px -10px fg 22%；3 → 0 22px 56px -18px fg 34%）。
 */
@Composable
fun Modifier.tokenShadow(level: Int, shape: Shape): Modifier {
    if (level <= 0) return this
    val t = LocalDesignTokens.current
    val elevation = when (level) {
        1 -> 1.dp
        2 -> 10.dp
        else -> 22.dp
    }
    val alpha = when (level) {
        1 -> 0.07f
        2 -> 0.22f
        else -> 0.34f
    }
    val c = t.fg.copy(alpha = t.fg.alpha * alpha)
    return this.shadow(elevation = elevation, shape = shape, clip = false, ambientColor = c, spotColor = c)
}

/** 普通卡：surface 底 + 1px 描边（原型 .card）；[elevation] = 1 时叠加 --elev-1。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    onClick: (() -> Unit)? = null,
    elevation: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val p = padding ?: t.space4
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(elevation, shape)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(p),
        content = content,
    )
}

/** 玻璃卡：surface 72% 透明 + 8% fg 描边 + 顶部高光（真模糊需 API31 backdrop，这里以底衬+半透明等价表达）。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val p = padding ?: t.space4
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(1, shape)
            .clip(shape)
            .background(t.glassBg)
            .border(1.dp, t.glassBorder, shape)
            // 原型 .glass：box-shadow inset 0 1px 0 var(--glass-highlight) —— 顶部 1px 高光内描边
            .drawBehind {
                drawRect(
                    color = t.glassHighlight,
                    topLeft = Offset.Zero,
                    size = Size(size.width, 1.dp.toPx()),
                )
            }
            .padding(p),
        content = content,
    )
}

/** 分组头（分组列表上方的小标题 + 右侧动作）。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/**
 * KPI 卡：数值 + 单位 + 环比 + 可选 sparkline。
 * @param spark 迷你走势数据；null 表示不画。
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaUp: Boolean? = null,
    spark: List<Float>? = null,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = t.fg,
            )
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            if (delta != null) {
                Spacer(Modifier.weight(1f))
                val c = when (deltaUp) {
                    true -> t.successText
                    false -> t.dangerText
                    null -> t.muted
                }
                Text(delta, style = MaterialTheme.typography.labelMedium, color = c)
            }
        }
        if (spark != null) {
            Spacer(Modifier.height(t.space2))
            Sparkline(values = spark, modifier = Modifier.fillMaxWidth().height(t.sparkHeight))
        }
    }
}
