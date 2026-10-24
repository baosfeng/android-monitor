package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的五态区 —— GalleryFiveStates（五种状态同屏并列）与 StateCard（单格演示壳）。负责 s12 的五态陈列。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun GalleryFiveStates(
    state: FiveState,
    onSelect: (FiveState) -> Unit,
    toast: com.bsfeng.monitor.ui.components.ToastHostState,
) {
    val t = LocalDesignTokens.current
    DemoSection("五种状态 State coverage") {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            StateCard("loading · 骨架屏", show = true) {
                StateSkeleton(rows = 5)
            }
            StateCard("empty · 空态", show = true) {
                EmptyState("开启监控后，事件会按小时汇总到这里。")
            }
            StateCard("error · 错误态", show = true) {
                ErrorState("数据库连接已断开（SQLITE_BUSY），未返回任何记录。", onRetry = { toast.show("正在重试…") })
            }
            StateCard("edge · 超长文案与计数", show = true) {
                EdgeState("边界态：超长文案 / 缺失字段 / 10000+ 计数")
            }
            AppCard(padding = 0.dp) {
                Text(
                    "populated · 有数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(start = t.space4, top = t.space3),
                )
                Spacer(Modifier.height(t.space2))
                ListRow("微信", "42 分钟", "前台切换 · 9:12")
                TokenDivider(first = false)
                ListRow("哔哩哔哩", "3 次", "广告跳过 · 9:05")
                TokenDivider(first = false)
                ListRow("高德地图", "16 分钟", "前台切换 · 8:41")
                Spacer(Modifier.height(t.space1))
            }
            if (state != FiveState.POPULATED) {
                Text(
                    "当前只看 " + state.name.lowercase() + " 一个状态块（其余四块在有数据态下同屏陈列）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                    modifier = Modifier.padding(horizontal = t.space4),
                )
                AppButton("回到有数据态", { onSelect(FiveState.POPULATED) }, Modifier.padding(horizontal = t.space4), AppButtonVariant.Ghost, block = true)
            }
        }
    }
}

@Composable
private fun StateCard(label: String, show: Boolean, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    if (!show) return
    AppCard {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space3))
        content()
    }
}
