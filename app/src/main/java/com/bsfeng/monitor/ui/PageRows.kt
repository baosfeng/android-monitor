package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：行式排版 —— TokenDivider（1px 分隔线）、TokenRow（图标 + 标题/副标题 + 尾部）、ListRow（紧凑行）、TailNum（等宽尾部数字）、TailChevron（尾部箭头）、ProgressBar（胶囊进度条）。负责「一行一条信息」。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 1px 分隔线（原型 .row 的 border-top，首行不画）。 */
@Composable
internal fun TokenDivider(first: Boolean) {
    val t = LocalDesignTokens.current
    if (first) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
}

/**
 * 该行是否参与按压反馈（纯函数，便于单测钉住「静态行不加反馈」）。
 *
 * 纯展示行（两个回调都为 null）不缩放、不发触感，也不进 Tab 顺序（s12-rows「静态行点按无变化」）；
 * 只要可点或可长按，就按 s09-goto-* 的 0.96 + LIGHT 走。抽成纯函数是为了不用上设备就能反向验证。
 */
internal fun tokenRowPressFeedback(onClick: (() -> Unit)?, onLongClick: (() -> Unit)?): Boolean =
    onClick != null || onLongClick != null

/**
 * 列表行（原型 .row：左图标 + 标题 / 副标题 + 右侧尾部）。
 *
 * 可点 / 可长按时整行按下缩到 0.96（[pressFeedback]，时长取 --dur-press）并触发 LIGHT
 * ——锚点 s09-goto-* 的「整行按压缩到 0.96（120ms，LIGHT）」。缩放只走绘制期，不改布局与点击语义；
 * s12-rows 写的「按下底色转 fg 5%」不另做：共享行只有一套行为，s09 为主口径。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TokenRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressModifier = if (tokenRowPressFeedback(onClick, onLongClick)) {
        Modifier.pressFeedback(interaction)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(pressModifier)
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = {
                            haptic(HapticTone.LIGHT)
                            onClick?.invoke()
                        },
                        onLongClick = {
                            haptic(HapticTone.LIGHT)
                            onLongClick()
                        },
                    )
                    onClick != null -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                    ) {
                        haptic(HapticTone.LIGHT)
                        onClick()
                    }
                    else -> Modifier
                }
            )
            // 原型 .group-row/.row：min-height 52（--row-min-h）是含 padding 8 的盒子，
            // 内容区 = 52 - 2×8 = 36（heightIn 在 padding 之内生效，所以要减掉两边的 8）
            .padding(horizontal = t.space4, vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = t.fg2, modifier = Modifier.size(t.space5))
            }
            Spacer(Modifier.width(t.space3))
        }
        // 行盒（台账「固定盒 + unbounded」）：原型 .row-title 17px×1.25=21.25、.row-sub 14px×1.3=18.2，
        // 两行 + .row-body 的 2px gap 共 41.4；Compose 单行 Text 是字体行盒（中文 1.47 倍），
        // 17sp 撑到 25dp，整行会比原型高 4~7dp。
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.height(TITLE_LINE)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor ?: t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("row.token").wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
            if (subtitle != null) {
                Box(Modifier.height(SUB_LINE)) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor ?: t.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space3))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                trailing()
            }
        }
    }
}

/**
 * 紧凑行（原型 .list-row）：min-height 48px + 上下 padding 8 + 行标题/副标题各自的行盒。
 *
 * 行盒口径：原型 .row-title 是 17px × line-height 1.25 = 21.25px、.row-sub 是 14px × 1.3 = 18.2px，
 * 两行之间还有 .row-body 的 2px gap；Compose 的单行 Text 高度是字体行盒（中文 1.47 倍），
 * 17sp 会撑到 25dp、14sp 到 20.6dp，三行累计比原型高 2–7dp。故按台账「行盒」解法：
 * 外层 Box 钉原型行盒、Text 用 wrapContentHeight(unbounded) 溢出绘制，字形不裁。
 */
@Composable
internal fun ListRow(title: String, value: String, subtitle: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        // 原型 .list-row：min-height 48 + 上下 padding 8（水平 padding 在原型由卡提供，这里行内自带）
        Modifier.fillMaxWidth().heightIn(min = t.space12).padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.height(LIST_ROW_TITLE_LINE)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
            if (subtitle != null) {
                Box(Modifier.height(LIST_ROW_SUB_LINE)) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                    )
                }
            }
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/** 原型 .row-title = 17px × 1.25；[ListRow] / [TokenRow] 用它钉单行的行盒。 */
private val LIST_ROW_TITLE_LINE = 21.25.dp

/** [TokenRow] 的主标题行盒（与 [LIST_ROW_TITLE_LINE] 同口径，别名让两处调用读起来更清楚）。 */
private val TITLE_LINE = LIST_ROW_TITLE_LINE

/** [TokenRow] 的副标题行盒（.row-sub = 14px × 1.3）。 */
private val SUB_LINE = 18.2.dp

/** 原型 .row-sub = 14px × 1.3；与 .row-body 的 2px gap 一起决定行高。 */
private val LIST_ROW_SUB_LINE = 18.2.dp

/** 尾部数字（原型 .num：等宽、tabular）。 */
@Composable
internal fun TailNum(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
}

/** 尾部箭头（原型 .chev）。 */
@Composable
internal fun TailChevron() {
    val t = LocalDesignTokens.current
    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = t.muted, modifier = Modifier.size(t.space5))
}

/** 进度条（原型 .bar：pill 轨道 + 填充，入场 700ms --ease-out）。 */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    val t = LocalDesignTokens.current
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grown = true }
    val f by animateFloatAsState(
        targetValue = if (grown) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(t.durChart, easing = t.easeOut),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(t.space2)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.borderSoft),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(color ?: t.accent),
        )
    }
}
