package com.bsfeng.monitor.ui.components

// 横条组基元（原型 .barrows，.open-design/src/styles/20-base.html:295-307）：s02 今日事件构成 /
// s03 命中方式共用。只画「标签 + 按百分比填充的胶囊条 + 数值」三列，卡片外壳与卡头由调用点承担。
// 色值不在这里选：调用方按数据色角色（chart-2 / chart-1 …）传入，组件只管画。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/** 横条组的一行：标签 / 条数 / 条色（色值由调用点按数据色角色给出）。 */
data class BarRow(val label: String, val value: Int, val color: Color)

/** 原型 .barrows .bar-k 的省略上限（max-width:96px）。 */
private val BAR_LABEL_MAX_W = 96.dp

/** 原型 .barrows .bar-t 的轨道高（height:8px）：有布局语义的非常规尺寸，不随间距档位变。 */
private val BAR_TRACK_H = 8.dp

/**
 * 原型 .bar-row 的行盒 = 14px × --leading-body(1.47) ≈ 20.6。
 *
 * 不钉住的话 bodySmall 的 19sp 行高会矮 1.6dp、CJK 自然行盒又会撑到 22 以上，6 行累积
 * 6~18dp 的偏差（同 CardHead 的「固定盒 + 自然绘制」解法；这里行内只有短文本，不需要 unbounded）。
 */
private val BAR_ROW_LINE = 20.6.dp

/** 条宽比例：分母为 0（空数据）或值非正时落 0，避免 NaN 与除零。 */
internal fun barFraction(value: Int, total: Int): Float =
    if (total <= 0 || value <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

/**
 * 横条组：每行「标签 · 胶囊条 · 数值」三列（原型 grid auto / minmax(48px,1fr) / auto）。
 *
 * 条宽 = value / [total]；数值右侧的「<b>N</b> 条」中数字用 fg 加重、单位用 muted，
 * 与原型 .bar-v b 一致。行不可点、不参与焦点（纯展示）。
 *
 * @param unit 数值后缀（s02「条」/ s03「次」）
 * @param total 百分比分母，默认取各行合计
 */
@Composable
fun BarRows(
    rows: List<BarRow>,
    modifier: Modifier = Modifier,
    unit: String = "条",
    total: Int = rows.sumOf { it.value },
) {
    val t = LocalDesignTokens.current
    Column(
        modifier.fillMaxWidth(),
        // 原型 .barrows gap: var(--space-2)
        verticalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(BAR_ROW_LINE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = BAR_LABEL_MAX_W),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(BAR_TRACK_H)
                        .clip(RoundedCornerShape(t.radiusPill))
                        // 原型 color-mix(in srgb, var(--fg) 9%, transparent)
                        .background(t.fgFade(9)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(barFraction(row.value, total))
                            .clip(RoundedCornerShape(t.radiusPill))
                            .background(row.color),
                    )
                }
                Text(
                    remember(row.value, unit, t.fg) { barValueText(row.value, unit, t.fg) },
                    style = monitorMonoMeta,
                    color = t.muted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** 「52 条」：数字用 fg + SemiBold，单位沿用 muted（原型 .bar-v b 的加粗）。 */
private fun barValueText(value: Int, unit: String, valueColor: Color) = buildAnnotatedString {
    withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.SemiBold)) {
        append(value.toString())
    }
    append(' ')
    append(unit)
}
