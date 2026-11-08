package fixture.a11y

import androidx.compose.material.icons.Icons

// 门禁 fixture：只用于 scripts/check-a11y.sh --self-test 的静态扫描，从不参与编译。

@Composable
fun LabeledCalls() {
    AppIconButton(Icons.Filled.Add, "更多操作", onClick = {})
    HeadIconButton(icon = Icons.Filled.Add, contentDescription = "返回", onClick = {})
    FinanceIconButton(onClick = {}, contentDescription = "关闭") { label ->
        Icon(Icons.Filled.Close, contentDescription = label)
    }
    IconButton(onClick = {}) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

