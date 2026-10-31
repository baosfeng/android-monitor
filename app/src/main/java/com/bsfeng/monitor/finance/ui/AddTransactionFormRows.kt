package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的表单行；职责：字段行（标签 + 值 + ▸）、说明输入行、行分隔线。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 字段行最小高度（原型 .s14-toggle min-height:44px = --tap-min） */
internal val FIELD_ROW_MIN_H: Dp = 44.dp

/** 标签列宽（原型 .screen.s14a .s14-label：flex 0 0 44px = --tap-min；两字中文放得下） */
internal val FIELD_LABEL_W: Dp = 44.dp

/**
 * 一手可点：标签 + 值 + ▸（原型 .s14-toggle）。
 *
 * 契约 s14a-*-row：按住时整行底色转 fg 5%（120ms）、LIGHT 触感，抬指才开抽屉。
 * [focusRequester] 用于抽屉关闭后把焦点送回本行（契约「移除/关闭后」列）。
 */
@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "fieldRowBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_ROW_MIN_H)
            .clip(RoundedCornerShape(t.radiusMd))
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        // 原型 .s14-toggle{padding:0} + min-height:44px：行自身零内边距。
        // 注意 modifier 链的顺序 —— heightIn 在 padding 之前时，44 约束的是**内容区**，
        // 总高会变成 44+padding（真机实测多出 8dp，四行累计就很明显了）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(FIELD_LABEL_W).testTag("row.field"),
        )
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isBlank()) t.muted else t.fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(t.iconSize),
        )
    }
}

/** 说明行：与上面三行同构，但值是直接输入的（无 ▸，因为不需要再开一层） */
@Composable
internal fun NoteRow(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_ROW_MIN_H),
        // 同上：说明行也是 .s14-toggle，零内边距 + min-height 44
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Text(
            "说明",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(FIELD_LABEL_W),
        )
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = t.fg,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(t.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(
                    "写一下买了什么",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
}

/** 行分隔线：原型是 .s14-toggle:not(:first-child){border-top} —— **整行宽**，不从标签右侧起 */
@Composable
internal fun FieldDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalDesignTokens.current.borderSoft),
    )
}
