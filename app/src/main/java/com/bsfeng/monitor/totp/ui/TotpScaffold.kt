package com.bsfeng.monitor.totp.ui

// 六屏共用的页壳与输入控件：TotpScaffold（push 屏：底部渐变 + 导航栏 + 可滚动内容）、
// TotpTextField（原型 .s25-in / .s26-in：surface 底 + 1px 描边 + 最小 48dp）。
// 只承接布局与 token，不含业务判断（校验在上层或 TotpFormat）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/**
 * push 屏页壳：全屏底衬 + iOS 导航栏 + 占满剩余高度、可纵向滚动的内容区。
 *
 * [onBack] 为 null 时不画返回键（由外壳接管返回），踩不到「双返回键」。
 */
@Composable
fun TotpScaffold(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize().testTag(testTag)) {
            IOSNavigationBar(title = title, onBack = onBack, actions = actions)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = t.space4, vertical = t.space2),
                verticalArrangement = Arrangement.spacedBy(t.space2),
                content = content,
            )
        }
    }
}

/**
 * 表单输入框（原型 .s25-in / .s26-in / .s27-in）。
 *
 * 只做视觉与 IME：值 / 错误 / 是否掩码都由调用方给。[obscure] 仅用于「备份密码」这类真密码；
 * 验证码本身永不走掩码（否则读屏会把它当密码字段朗读）。
 */
@Composable
fun TotpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    invalid: Boolean = false,
    obscure: Boolean = false,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val baseStyle = if (mono) {
        monitorMonoMeta.copy(color = t.fg, fontSize = t.textBase)
    } else {
        MaterialTheme.typography.bodyLarge.copy(color = t.fg)
    }
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = t.fg2)
            Spacer(Modifier.height(t.space1))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = t.tapMin)
                .clip(shape)
                .background(t.surface)
                .border(1.dp, if (invalid) t.danger else t.borderSoft, shape)
                .padding(horizontal = t.space3, vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = baseStyle,
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (obscure) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = label ?: placeholder },
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(placeholder, style = baseStyle, color = t.muted, maxLines = 1)
                        }
                        inner()
                    },
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(t.space2))
                trailing()
            }
        }
    }
}

/** 说明性小字（原型 .fine）：14sp、muted、行高 1.5。 */
@Composable
fun TotpFine(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
        modifier = modifier.fillMaxWidth().padding(top = t.space2, bottom = t.space1),
    )
}

/** 分组标签（原型 .group-label）：14sp muted，带顶部间距。 */
@Composable
fun TotpGroupLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = t.muted,
        modifier = modifier.fillMaxWidth().padding(top = t.space3, bottom = t.space1),
    )
}

/** 轻分隔线（原型 border-soft 1px）；[interaction] 仅用于让调用点复用同一按压源。 */
@Composable
fun TotpHairline(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
}

/** 调试/集成用的触点标记（不改变布局）。 */
internal fun Modifier.totpTag(tag: String): Modifier = this.testTag(tag)

internal fun rememberNoIndication(): MutableInteractionSource = MutableInteractionSource()
