package com.bsfeng.monitor.ui.components

// 从 ui/components/States.kt 拆出：五态枚举 —— FiveState（LOADING/EMPTY/ERROR/POPULATED/EDGE）与 StateContainer 总容器。负责「一个数据面按五种状态切换」的统一入口。

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 五态容器（契约 §8）：loading 骨架 / empty / error / populated / edge。
 * 每个数据面都能演示五态，W1/W2/W3 直接复用，不要各屏手写空态。
 *
 * 形态对齐原型 `.open-design/parts/00-shell.html`：
 * 骨架 = 细行 shimmer（.sk，450-458 行）；空/错态 = 居中列 + 44px 图标 + 17sp 标题 + 14sp 描述 + CTA（.st-*，428-464 行）。
 */
enum class FiveState { LOADING, EMPTY, ERROR, POPULATED, EDGE }

@Composable
fun StateContainer(
    state: FiveState,
    modifier: Modifier = Modifier,
    emptyText: String = "暂无数据",
    errorText: String = "加载失败",
    edgeText: String = "边界态：超长文案 / 缺失字段 / 10000+ 计数",
    onRetry: (() -> Unit)? = null,
    emptyTitle: String = "空空如也",
    errorTitle: String = "出错",
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    /**
     * 错态 CTA 文案（原型 s11-whitelist.html:131 为「重新扫描」）。
     * 默认 null = 沿用旧行为「重试」；传值即可按原型覆盖（s11 白名单页用）。
     */
    errorActionLabel: String? = null,
    /** 空态图标覆盖（原型各屏空态图标不同）；默认 null = 沿用 [EmptyState] 的 Widgets。 */
    emptyIcon: ImageVector? = null,
    edgeContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        when (state) {
            FiveState.LOADING -> StateSkeleton()
            FiveState.EMPTY -> EmptyState(
                text = emptyText,
                title = emptyTitle,
                icon = emptyIcon ?: Icons.Outlined.Widgets,
                actionLabel = emptyActionLabel,
                onAction = onEmptyAction,
            )
            FiveState.ERROR -> ErrorState(
                text = errorText,
                onRetry = onRetry,
                title = errorTitle,
                actionLabel = errorActionLabel ?: ERROR_ACTION_DEFAULT,
            )
            FiveState.EDGE -> EdgeState(edgeText, content = edgeContent)
            FiveState.POPULATED -> content()
        }
    }
}
