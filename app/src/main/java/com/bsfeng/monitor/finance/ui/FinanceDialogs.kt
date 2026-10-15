package com.bsfeng.monitor.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AppField
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 记账模块的文本输入弹窗 — 记账模块内复用（备注、日期、预算金额）。
 *
 * 视觉按原型 s06 `#s06-dlg`（08-prototype.html:4008-4021）自绘：`.scrim > .dialog` ——
 * 标题 `.h3`、说明 `.meta`、输入容器 `.card`、动作 1fr 1fr 的 `.btn-ghost`「取消」+ `.btn`「确定」。
 * 底色 / 圆角 / 阴影 / 按钮 / 字号 / 间距一律取 [LocalDesignTokens] 与主题字号表（Type.kt），
 * 不再用 M3 `AlertDialog` + `OutlinedTextField` + `TextButton` —— 那套的底色、圆角、阴影、
 * 按钮几何由 Material 默认值接管，与 App 其余弹窗不是一个体系。
 *
 * @param isValid 实时校验；不通过时禁用「确定」并展示 [errorMessage]
 */
@Composable
internal fun FinanceInputDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    errorMessage: String? = null,
    isValid: (String) -> Boolean = { true },
) {
    val t = LocalDesignTokens.current
    var text by remember { mutableStateOf(initialValue) }
    val valid = isValid(text)

    // 原来的 M3 AlertDialog 可被系统返回键关闭（onDismissRequest）；自绘浮层要显式接管返回键，
    // 关闭语义与 onDismiss 完全一致（同项目既有写法：AdScreen.kt:101 / MainScreen.kt:126）。
    BackHandler { onDismiss() }

    AppDialog(visible = true, onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space3))
        AppField(
            value = text,
            onValueChange = { text = it },
            placeholder = placeholder,
            keyboardType = keyboardType,
        )
        // 校验提示：原实现用 MaterialTheme.colorScheme.error，这里换成原型 --danger-text；
        // 间距沿用改动前的 4dp（= 原型 --space-1），只换体系不换几何。
        if (text.isNotEmpty() && !valid && errorMessage != null) {
            Spacer(Modifier.height(t.space1))
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
        }
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DialogAction(
                label = "取消",
                primary = false,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            DialogAction(
                label = "确定",
                primary = true,
                enabled = valid,
                onClick = { if (valid) onConfirm(text) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 弹窗动作按钮（原型 `.btn` / `.btn-ghost` / `.btn[disabled]`）：
 * min-height --tap-min、圆角 --radius-md、文字 17sp/600（主题 titleMedium，与
 * [com.bsfeng.monitor.ui.components.ConfirmDialog] 的动作按钮同一口径）。
 *
 * 与 ui/components/Surfaces.kt 的 `DialogAction` 同款；那个是 private，本次写 scope
 * 又只含记账两个文件（不动 W1 维护的共享基元），故按同一 token 口径在此重绘，
 * 并补上 `DialogAction` 没有的禁用态：原型 `.btn[disabled]{opacity:.45}`，
 * 表达方式沿用本模块既有事实标准（AddTransactionScreen.kt 的 PrimaryButton：禁用 = --accent 45%）。
 */
@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(shape)
            .background(if (primary) t.accent.copy(alpha = if (enabled) 1f else 0.45f) else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else t.border, shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> t.muted
                primary -> t.accentOn
                else -> t.fg
            },
        )
    }
}
