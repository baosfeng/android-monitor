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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 吸底保存条（原型 .s14a-actions）：表单怎么滚，它都在。
 *
 * 背景是从透明到 surface 的竖向渐变，让滚动内容自然淡出到按钮下方。
 *
 * @param canSave 按钮可用态：**结构性**缺失（分类未选 / 转账账户不合法）或提交中才置灰。
 *   金额为空 / 为 0 不在这里 —— 那是可当场纠正的输入错误，按钮保持可点，由屏幕层在 [onSave]
 *   里抖金额卡 + toast「请输入大于 0 的金额」（原型 s04-记账.html:735-749）。取舍理由：禁用态
 *   收不到点击，既没有反馈、读屏也问不出「还差什么」，只适合表达用户改不了的结构性缺失。
 */
@Composable
internal fun AddTransactionActionBar(
    canSave: Boolean,
    showDelete: Boolean,
    onSave: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 「删除这笔交易」的焦点落点（s05-del-dialog / s05-del-cancel 移除后回位）；null = 不复位。 */
    deleteFocus: FocusRequester? = null,
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
            // 原型 s14a:147 / s05:455 都是「保存」：编辑态不再另起「保存修改」（审计 #62）
            text = "保存",
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
                focusRequester = deleteFocus,
            )
        }
    }
}
