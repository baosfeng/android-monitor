package fixture.a11y

import androidx.compose.material.icons.Icons

// 门禁 fixture：只用于 scripts/check-a11y.sh --self-test 的静态扫描，从不参与编译。

@Composable
fun NullLabelAppIconButton() {
    AppIconButton(icon = Icons.Filled.Add, contentDescription = null, onClick = {})
}

