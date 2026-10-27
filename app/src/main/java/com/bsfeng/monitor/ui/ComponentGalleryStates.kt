package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的五态区 —— GalleryFiveStates（五种状态同屏并列）与 StateCard（单格演示壳）。负责 s12 的五态陈列。
// 契约 docs/交互设计/s12-组件与状态-交互契约.md：
//   · s12-states  块顺序固定 加载 → 空 → 错误 → 有数据 → 边界；非「有数据」态只渲染当前状态一块
//   · s12-st-loading / s12-st-empty / s12-st-error / s12-st-populated / s12-st-edge 各自带块级锚点

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五态展区（契约 s12-states）：
 * 有数据态下五块同屏陈列、无遮罩无折叠；切到某一状态时只渲染该状态一块，其余四块整块不渲染。
 * 块顺序固定 加载 → 空 → 错误 → 有数据 → 边界（契约「块顺序固定」）。
 */
@Composable
internal fun GalleryFiveStates(
    state: FiveState,
    onSelect: (FiveState) -> Unit,
    toast: ToastHostState,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val showAll = state == FiveState.POPULATED
    DemoSection("五种状态 State coverage", modifier.testTag("s12-states")) {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            if (showAll || state == FiveState.LOADING) {
                StateCard("loading · 骨架屏", Modifier.testTag("s12-st-loading")) { StateSkeleton(rows = 5) }
            }
            if (showAll || state == FiveState.EMPTY) {
                StateCard("empty · 空态", Modifier.testTag("s12-st-empty")) {
                    // 契约 s12-st-empty：标题「今天还没有记录」+ 说明 + CTA「开启监控」→ toast「已开启监控」
                    EmptyState(
                        text = "开启监控后，事件会按小时汇总到这里。",
                        title = "今天还没有记录",
                        actionLabel = "开启监控",
                        onAction = { toast.show("已开启监控") },
                    )
                }
            }
            if (showAll || state == FiveState.ERROR) {
                StateCard("error · 错误态", Modifier.testTag("s12-st-error")) { GalleryErrorState(toast) }
            }
            if (showAll || state == FiveState.POPULATED) {
                GalleryPopulatedCard()
            }
            if (showAll || state == FiveState.EDGE) {
                StateCard("edge · 超长文案与计数", Modifier.testTag("s12-st-edge")) { GalleryEdgeBlock() }
            }
            if (!showAll) {
                Text(
                    "当前只看 " + state.name.lowercase() + " 一个状态块（其余四块在有数据态下同屏陈列）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                    modifier = Modifier.padding(horizontal = t.space4),
                )
                AppButton(
                    "回到有数据态",
                    { onSelect(FiveState.POPULATED) },
                    Modifier.padding(horizontal = t.space4),
                    AppButtonVariant.Ghost,
                    block = true,
                )
            }
        }
    }
}

/**
 * 错误态（契约 s12-st-error）：标题「数据加载失败」+ 说明 + 「重试」→ toast「正在重试…」，
 * 800ms 内重复点击不重复请求（[retryLock] 单调计数闸门）。
 */
@Composable
private fun GalleryErrorState(toast: ToastHostState) {
    // 800ms 闸门：契约「重试 800ms 内重复点击不重复请求」。用递增令牌 + LaunchedEffect 解锁，
    // 不引第三方计时器；点一次加一，effect 到点后清空 → 空 = 可重试。
    var retryLock by remember { mutableIntStateOf(0) }
    ErrorState(
        text = "数据库连接已断开（SQLITE_BUSY），未返回任何记录。",
        title = "数据加载失败",
        onRetry = {
            if (retryLock == 0) {
                retryLock = 1
                toast.show("正在重试…")
            }
        },
    )
    androidx.compose.runtime.LaunchedEffect(retryLock) {
        if (retryLock == 1) {
            kotlinx.coroutines.delay(RETRY_LOCK_MS)
            retryLock = 0
        }
    }
}

/** 重试闸门时长（契约 s12-st-error「按钮禁用 800ms」）。 */
private const val RETRY_LOCK_MS = 800L

/** 有数据态（契约 s12-st-populated）：纯展示记录行，无按下态、不进 Tab 顺序。 */
@Composable
private fun GalleryPopulatedCard() {
    val t = LocalDesignTokens.current
    AppCard(padding = 0.dp, modifier = Modifier.testTag("s12-st-populated")) {
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
}

/**
 * 边界态（契约 s12-st-edge）：超长标题最多 2 行省略、右侧计数不换行；缺结束时间按 0 计。
 *
 * 共享 EdgeState 的内容槽只接一段说明，表达不了「两行 = 标题/副标题 + 右侧定宽计数」，
 * 故本页局部实现两条只读行（仅本页使用，不改共享件）。
 */
@Composable
private fun GalleryEdgeBlock() {
    val t = LocalDesignTokens.current
    Column {
        GalleryEdgeRow(
            title = "超长标题：国家电网掌上电力营业厅客户端（企业内网版）前台切换记录",
            subtitle = "com.example.very.long.package.name.edge.case",
            value = "10,286",
        )
        TokenDivider(first = false)
        GalleryEdgeRow(
            title = "边界态：字段缺失",
            subtitle = "该记录没有结束时间，时长按 0 计算",
            value = "10,000",
        )
    }
}

/** 只读边界行：标题 2 行省略、右侧计数单行不换行、长按标题可复制全文（selection 默认开）。 */
@Composable
private fun GalleryEdgeRow(title: String, subtitle: String, value: String) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(t.space3))
        Text(value, style = MaterialTheme.typography.bodySmall, color = t.fg2, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** 单格演示壳：块级锚点由调用点逐字传 literal（契约锚点不做字符串拼接）。 */
@Composable
private fun StateCard(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space3))
        content()
    }
}
