package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookConfirmSheet.kt 拆出：确认卡片内部的排版原子
// （来源胶囊 / 分段项 / 标签 / 分隔线 / 元信息行 / 说明行）。均走设计 token。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.CategoryTree
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorRadius
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.opaquePanel

@Composable
internal fun SourceChip(label: String, channel: String) {
    val tokens = LocalDesignTokens.current
    val brandColor = when (channel) {
        AutoBookRules.CHANNEL_WECHAT -> tokens.success
        else -> tokens.accent
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MonitorRadius.circle))
            .background(brandColor.copy(alpha = 0.14f))
            .padding(start = 4.dp, end = MonitorSpacing.sm, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(brandColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.accentOn,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = brandColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun SegmentItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MonitorRadius.sm - 2.dp))
            .background(if (selected) tokens.surface else tokens.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(vertical = MonitorSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.fg else tokens.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun Label(text: String) {
    val tokens = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.muted,
        modifier = Modifier.padding(top = MonitorSpacing.md, bottom = MonitorSpacing.sm)
    )
}

@Composable
internal fun Divider() {
    val tokens = LocalDesignTokens.current
    HorizontalDivider(
        modifier = Modifier.padding(vertical = MonitorSpacing.md),
        thickness = 1.dp,
        color = tokens.borderSoft
    )
}

@Composable
internal fun MetaRow(key: String, value: String) {
    val tokens = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MonitorSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.labelMedium, color = tokens.muted)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.fg,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 可编辑的「说明」行：未编辑时是灰色占位「写一下买了什么」，点整行进入编辑态。
 *
 * 编辑态**只影响窗口的输入焦点**，不会关闭浮窗；失焦 / 点「完成」/ 键盘 Done 都只收起键盘。
 * 之所以点整行而不是直接点输入框：窗口默认 FLAG_NOT_FOCUSABLE，输入框在窗口不可聚焦时
 * 拿不到焦点，必须先把窗口切成可聚焦（见 [AutoBookOverlay.setImeFocusable]）。
 */
@Composable
internal fun RemarkRow(
    remark: String,
    onRemarkChange: (String) -> Unit,
    editing: Boolean,
    onRequestEdit: () -> Unit,
    onFinishEdit: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    focusRequester: FocusRequester,
) {
    val tokens = LocalDesignTokens.current
    val focusManager = LocalFocusManager.current
    // 「曾经拿到过焦点后失焦」才算结束编辑：首次组合时输入框本来就是无焦点，不能误判
    var hadFocus by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 44dp 与记账页输入行同高（触摸目标下限，布局语义尺寸故留字面量）
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(MonitorRadius.sm))
            .background(tokens.fg.copy(alpha = 0.06f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onRequestEdit() }
            .padding(horizontal = MonitorSpacing.md, vertical = MonitorSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = remark,
                onValueChange = { onRemarkChange(it.take(AutoBookDraft.REMARK_MAX_LENGTH)) },
                enabled = editing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.fg,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    onFinishEdit()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        onFocusChange(state.isFocused)
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            onFinishEdit()
                        }
                    },
            )
            if (remark.isBlank()) {
                Text(
                    text = "写一下买了什么",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.muted,
                )
            }
        }
        if (editing) {
            Spacer(Modifier.width(MonitorSpacing.sm))
            Text(
                text = "完成",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(MonitorRadius.xs))
                    .clickable { onFinishEdit() }
                    .padding(horizontal = MonitorSpacing.xs, vertical = MonitorSpacing.xxs),
            )
        }
    }
}

/**
 * 编辑说明时的「抢焦点」时序（无障碍浮窗特有）。
 *
 * 窗口要先从 `FLAG_NOT_FOCUSABLE` 切到可聚焦输入法才会弹；`flags → updateViewLayout`
 * 需要过一次 View 刷新，因此必须等两帧再 `requestFocus()`。本版本 `requestFocus()`
 * 没有返回值，只能靠 `onFocusChanged` 回读是否真的拿到焦点；拿不到就退回不可编辑，
 * 不假装能输入（ROM 限制 / 窗口未及时可聚焦）。
 */
@Composable
internal fun RememberRemarkFocusEffect(
    editing: Boolean,
    focused: () -> Boolean,
    onGiveUp: () -> Unit,
    focusRequester: FocusRequester,
    onImeFocusRequest: (Boolean) -> Unit,
) {
    LaunchedEffect(editing) {
        if (!editing) {
            onImeFocusRequest(false)
            return@LaunchedEffect
        }
        onImeFocusRequest(true)
        withFrameNanos { }
        withFrameNanos { }
        var attempt = 0
        while (!focused() && attempt < 3) {
            runCatching { focusRequester.requestFocus() }
            withFrameNanos { }
            attempt++
        }
        if (!focused()) onGiveUp()
    }
}
