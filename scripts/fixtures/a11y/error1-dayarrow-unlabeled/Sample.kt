package fixture.a11y

import androidx.compose.material.icons.Icons

// 门禁 fixture：只用于 scripts/check-a11y.sh --self-test 的静态扫描，从不参与编译。
// 复现审计 I3 的第二个口子：自建封装的名字不带 IconButton，旧实现完全看不见它。
@Composable
fun DelegatingWrapperWithoutLabel() {
    DayArrow(icon = Icons.Outlined.ChevronLeft, onClick = {})
}
