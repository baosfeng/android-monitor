package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的吸底保存条；职责：保存主按钮 + 编辑态的删除入口，带渐变背景。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 吸底保存条（原型 .s14a-actions）：表单怎么滚，它都在。
 *
 * 背景是从透明到 surface 的竖向渐变，让滚动内容自然淡出到按钮下方。
 */
@Composable
internal fun AddTransactionActionBar(
    isEditing: Boolean,
    canSave: Boolean,
    showDelete: Boolean,
    onSave: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 原型 .s14a-actions：linear-gradient(to top, --surface 62%, transparent)
            .background(
                Brush.verticalGradient(0f to Color.Transparent, 0.38f to t.surface),
            )
            // 原型 .s14a-actions：padding 12px 16px 20px
            .padding(
                start = t.space4,
                end = t.space4,
                top = t.space3,
                bottom = t.space5,
            ),
    ) {
        PrimaryButton(
            text = if (isEditing) "保存修改" else "保存",
            enabled = canSave,
            onClick = { if (canSave) onSave() },
        )
        if (showDelete) {
            Spacer(Modifier.height(t.space2))
            GhostButton(
                text = "删除这笔交易",
                textColor = t.expenseText,
                background = t.danger.copy(alpha = 0.12f),
                onClick = onDeleteClick,
            )
        }
    }
}
