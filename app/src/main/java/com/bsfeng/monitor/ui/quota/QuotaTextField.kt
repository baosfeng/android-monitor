package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.FOCUS_RING_WIDTH
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 单行凭据输入（密码遮罩；明文只在内存里出现）。
 *
 * 原在 QuotaStateViews.kt（随旧主屏一起下线），但额度设置页仍用它，故拆成独立文件：
 * 光标取主色、框底与描边不变；键盘焦点进来时换成 2dp 焦点环（触摸聚焦不画环，
 * 与全站 keyboardFocusRing 口径一致）。
 */
@Composable
fun QuotaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 金额阈值等非敏感输入传 false（明文 + 数字键盘）；凭据保持遮罩。 */
    obscure: Boolean = true,
    /** 读屏标签（如「Ollama API Key」）。 */
    label: String? = null,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    var focused by remember { mutableStateOf(false) }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ring = focused && keyboardMode
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.surfaceWarm)
            .then(
                if (ring) {
                    Modifier.border(FOCUS_RING_WIDTH, t.accent, shape)
                } else {
                    Modifier.border(1.dp, t.border, shape)
                },
            )
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = t.muted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // 必须撑满整行：BasicTextField 的默认宽度随内容走，空值时点击区只剩光标那一小点，
            // 表现就是「输入框点不进去」。
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (label == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = label }
                    },
                ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.fg),
            cursorBrush = SolidColor(t.accent),
            // 凭据一律遮罩：两类令牌都可能被旁人看到，没有「短的就明文」的余地
            visualTransformation = if (obscure) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (obscure) KeyboardType.Password else KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
        )
    }
}
