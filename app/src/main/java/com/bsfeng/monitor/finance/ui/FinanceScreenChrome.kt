package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的屏幕基元；职责：屏幕头、圆形图标按钮、月份切换条、图例色点。

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 屏幕大标题（左对齐 h1 + 副标题 meta + 右侧动作）。
 *
 * 长按标题可唤出 DEBUG 五态开关（见 [FinanceStateBar]）——正式包不生效。
 */
@Composable
internal fun FinanceScreenHead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    consumeTopInset: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onLongPress != null) {
                        Modifier.pointerInput(onLongPress) {
                            detectTapGestures(onLongPress = { onLongPress() })
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 原型 .screen-head .h1 = 28px/700/-0.42px → headlineMedium（设计规范 §3.1 映射表）
            Text(title, style = MaterialTheme.typography.headlineMedium, color = t.fg)
            if (subtitle != null) {
                Spacer(Modifier.height(t.space1))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space2))
            trailing()
        }
    }
}

/**
 * 圆形图标按钮（屏幕头右侧动作 / 月份切换）。
 *
 * 造型对齐原型 `.icon-btn`（00-shell:1117-1126）：--icon-size 44×44、radius-pill、
 * 底 fg 5%、描边 --border-soft。月份条内的同类按钮原型显式去掉底与边框
 * （`.s04-month .icon-btn{border-color:transparent;background:transparent}`，00-shell:1929），
 * 由 [chrome] 关闭，避免与月份胶囊底叠成两层。
 */
@Composable
internal fun FinanceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** 是否画 `.icon-btn` 的浅灰圆底 + 描边；月份条内传 false（原型是透明态）。 */
    chrome: Boolean = true,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .size(t.tapMin)
            .clip(shape)
            .then(
                if (chrome) {
                    Modifier
                        .background(t.fgFade(5))
                        .border(1.dp, t.borderSoft, shape)
                } else {
                    Modifier
                },
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 月份切换条：整宽胶囊（浅灰底 + 1px 描边）内「左右 chevron + 中间月份标签」，
 * 切换后标签做一次 --ease-spring 弹一下。
 *
 * 造型对齐原型 `.s04-month` / `.s06-month` / `.s07-month`（00-shell:1928/1997/2036）：
 * padding 3px、radius-pill、底 fg 4%、描边 --border-soft；高度 52px 由
 * 内部 44px 图标按钮 + 上下 4dp 内边距自然撑出（原型的 3px 内边距 ≈ space-1）。
 */
@Composable
internal fun FinanceMonthBar(
    monthText: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val bump = remember { Animatable(1f) }
    LaunchedEffect(monthText) {
        bump.snapTo(1.08f)
        bump.animateTo(1f, t.springOf(MotionToken.Press))
    }
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(shape)
            .background(t.fgFade(4))
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinanceIconButton(onClick = onPrevious, contentDescription = "上个月", chrome = false) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
        Text(
            monthText,
            style = MaterialTheme.typography.titleLarge,
            color = t.fg,
            modifier = Modifier.graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            },
        )
        FinanceIconButton(onClick = onNext, contentDescription = "下个月", chrome = false) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

/**
 * 分类图例色点：颜色一律由 [categoryColorOf] 决定（s04/s05/s07 同一个口径）。
 *
 * 圆角固定 3dp —— 原型 `.list-row .legend-dot{width:10px;height:10px;border-radius:3px}`
 * 是圆角小方块（不是圆点），与统计页图例 [com.bsfeng.monitor.ui.components.Legend] 的 3dp 口径一致。
 */
@Composable
internal fun LegendDot(color: Color, modifier: Modifier = Modifier, size: Dp? = null) {
    val t = LocalDesignTokens.current
    Box(
        modifier
            .size(size ?: t.dotSize)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}
