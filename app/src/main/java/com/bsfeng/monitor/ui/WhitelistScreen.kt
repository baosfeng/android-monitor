package com.bsfeng.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.launch

/**
 * s11 应用白名单（原型 .open-design/src/screens/s11-应用白名单.html）。
 *
 * 锚点（10 个，逐字取自原型 data-od-id）：
 * screen-s11 / s11-head / s11-body / s11-search / s11-list / s11-none /
 * s11-st-loading / s11-st-empty / s11-st-error / s11-st-edge。
 *
 * 空 / 错 / 边界态直接调共享 [EmptyState] / [ErrorState] / [EdgeState]（StateMessages.kt），
 * 不用 StateContainer：后者不接受块级锚点，本页每个态都要带契约锚点。
 *
 * 「s11-list」是列表容器锚点，但列表项是 LazyColumn 的 items、没有可包住子集的容器节点，
 * 故与 s03-records 同一处置：锚点落在「应用列表」小节头那一行（见交接文档处置表）。
 */
@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    viewModel: WhitelistViewModel = viewModel(),
) {
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val filteredApps by viewModel.filteredApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val allApps by viewModel.apps.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    var demoState by remember { mutableStateOf(FiveState.POPULATED) }

    val effective = when {
        demoState != FiveState.POPULATED -> demoState
        error != null -> FiveState.ERROR
        loading -> FiveState.LOADING
        allApps.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    Box(Modifier.fillMaxSize().pageBackdrop().testTag("screen-s11")) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "应用白名单",
                meta = "白名单内的应用不会被跳过开屏广告",
                onBack = onBack,
                modifier = Modifier.testTag("s11-head"),
                consumeTopInset = true,
            )
            Column(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
                // 五态切换器（演示用）留在 content **之外**：状态分支不渲染 content，
                // 放进去就再没有切回有数据态的入口（release 构建不渲染）。
                StateSwitchRow(
                    state = demoState,
                    onSelect = { demoState = it },
                    modifier = Modifier.padding(bottom = t.space3),
                )

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (effective) {
                        FiveState.LOADING -> WhitelistStateSkeleton(Modifier.testTag("s11-st-loading"))

                        FiveState.EMPTY -> EmptyState(
                            text = "授予使用情况访问权限后，这里会列出可加入白名单的应用。",
                            title = "还没有扫描到应用",
                            actionLabel = "去授权",
                            onAction = { toast.show("已跳转系统「使用情况访问」设置") },
                            modifier = Modifier.testTag("s11-st-empty"),
                        )

                        FiveState.ERROR -> ErrorState(
                            text = error ?: "PackageManager 拒绝查询（QUERY_ALL_PACKAGES 未声明）。",
                            title = "读取应用列表失败",
                            // 原型 s11-whitelist.html:139 的错态 CTA 文案是「重新扫描」，非默认「重试」
                            actionLabel = "重新扫描",
                            onRetry = {
                                viewModel.clearError()
                                viewModel.refresh()
                                toast.show("正在重新扫描已安装应用…")
                            },
                            modifier = Modifier.testTag("s11-st-error"),
                        )

                        FiveState.EDGE -> EdgeState(
                            text = "边界态：超长应用名 / 缺失字段",
                            modifier = Modifier.testTag("s11-st-edge"),
                        ) {
                            WhitelistEdgeContent()
                        }

                        FiveState.POPULATED -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("s11-body"),
                        ) {
                            // ── 搜索 + 快捷 chips + 扫描计数 ──
                            item(key = "search") {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = t.space4, end = t.space4, bottom = t.space3)
                                        .testTag("s11-search"),
                                ) {
                                    AppField(
                                        value = searchQuery,
                                        // 契约 s11-search：每字符即时回显，停手 300ms 后过滤（防抖在 VM 内）
                                        onValueChange = { viewModel.updateSearchQuery(it) },
                                        placeholder = "搜索应用名或包名",
                                        leading = {
                                            Icon(
                                                Icons.Outlined.Search,
                                                contentDescription = null,
                                                tint = t.muted,
                                                modifier = Modifier.size(t.space5).padding(end = t.space2),
                                            )
                                        },
                                    )
                                    Spacer(Modifier.height(t.space2))
                                    Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                                        listOf("微信", "哔哩哔哩", "淘宝", "重置").forEach { keyword ->
                                            Chip(
                                                label = keyword,
                                                selected = keyword != "重置" && searchQuery.trim() == keyword,
                                                onClick = {
                                                    // Chip 内部已发 LIGHT 触感，此处不再叠加
                                                    if (keyword == "重置") {
                                                        viewModel.clearSearch()
                                                        toast.show("已重置筛选")
                                                    } else {
                                                        viewModel.applySearchNow(keyword)
                                                        toast.show("已筛选：" + keyword)
                                                    }
                                                    // 契约：重置 / 筛选后滚动位置回列表顶部
                                                    scope.launch { listState.animateScrollToItem(0) }
                                                },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(t.space1))
                                    Text(
                                        "已扫描 " + allApps.size + " 个已授权应用 · 搜索「抖音」可查看无结果空态",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.muted,
                                    )
                                }
                            }

                            // ── 小节头（列表容器锚点 s11-list）──
                            item(key = "apps-head") {
                                SecHead(
                                    "应用列表",
                                    Modifier.testTag("s11-list").padding(bottom = t.space3),
                                    right = "匹配 " + filteredApps.size + " 个",
                                )
                            }

                            // ── 无结果卡（契约 s11-none：150ms 淡入 / 淡出）──
                            item(key = "apps-empty") {
                                // 淡入淡出包在独立 composable 里：直接写在 LazyListScope 的 item 内
                                // 会被解析到 ColumnScope.AnimatedVisibility（扩展重载）而不是顶层重载。
                                WhitelistNoResultCard(
                                    visible = filteredApps.isEmpty(),
                                    total = allApps.size,
                                    onClear = {
                                        viewModel.clearSearch()
                                        scope.launch { listState.animateScrollToItem(0) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = t.space4)
                                        .testTag("s11-none"),
                                )
                            }

                            // ── 应用列表 ──
                            // 一卡一行：StackRow 首行圆上角 / 末行圆下角、行间 0 间距，
                            // 视觉上仍是改动前那一张连续卡片（相邻行 1dp 描边相接 = 原来的 TokenDivider）。
                            itemsIndexed(
                                items = filteredApps,
                                // key 用包名：数据源是 PackageManager 单次调用后 filter/map，包名唯一。
                                key = { _, app -> app.packageName },
                            ) { index, app ->
                                // 入场动画只给首屏可见项：staggerEnterAt 会给每行挂 graphicsLayer，
                                // 长列表滚动时合成成本被放大（AdScreen / SkipRuleScreen 同一处理）。
                                val enterMod = if (index < STAGGER_MAX_STEPS) Modifier.staggerEnterAt(index) else Modifier
                                StackRow(
                                    first = index == 0,
                                    last = index == filteredApps.lastIndex,
                                    modifier = Modifier.padding(horizontal = t.space4).then(enterMod),
                                ) {
                                    // StackRow 的 content 落在 Box 内：末尾留白必须与 AppRow 同处一个 Column。
                                    Column(Modifier.fillMaxWidth()) {
                                        AppRow(
                                            app = app,
                                            onToggle = {
                                                // 开关自带 LIGHT 触感（Switches.kt:86），此处不再叠加；
                                                // 文案取切换前的状态 → 与切换后的归属一致。
                                                viewModel.toggleWhitelist(app.packageName)
                                                toast.show(
                                                    app.appName +
                                                        if (app.isInWhitelist) " 已移出白名单" else " 已加入白名单",
                                                )
                                            },
                                        )
                                        if (index == filteredApps.lastIndex) Spacer(Modifier.height(t.space1))
                                    }
                                }
                            }

                            // ── 底部留白 ──：组间距 space3 + 页面尾余量 space6
                            item(key = "bottom-space") {
                                Spacer(Modifier.height(t.space3 + t.space6))
                            }
                        }
                    }
                }
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )
    }
}

/**
 * 无结果卡（契约 s11-none）：关键词非空且过滤结果为 0 时出现，150ms 淡入 / 淡出。
 *
 * 独立成 composable 是为了让 AnimatedVisibility 解析到顶层重载（LazyListScope 的 item 内
 * 没有 BoxScope 之类的无冲突接收者，扩展重载会抢解析）。
 */
@Composable
private fun WhitelistNoResultCard(
    visible: Boolean,
    total: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(t.durOverlayOut, easing = t.easeOut)),
        exit = fadeOut(tween(t.durOverlayOut, easing = t.easeIn)),
    ) {
        AppCard {
            Text("没有匹配的应用", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space2))
            Text(
                "换个关键词试试，或清空搜索看全部 " + total + " 个已授权应用。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space3))
            // AppButton 内置触感口径见交接文档（共享件缺陷，本路不改）
            AppButton(
                "清空搜索",
                onClick = onClear,
                variant = AppButtonVariant.Ghost,
                block = true,
            )
        }
    }
}

/** s11 加载态：6 条骨架行（原型 .st-loading 的 6 个 .sk），不接收点击与焦点。 */
@Composable
private fun WhitelistStateSkeleton(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier.padding(horizontal = t.space4)) {
        StateSkeleton(rows = 6)
    }
}

/** 边界态示例：超长应用名 / 缺失字段（原型 .st-edge）。 */
@Composable
private fun WhitelistEdgeContent() {
    TokenRow(
        title = "超长应用名：中国建设银行个人网上银行移动客户端（鸿蒙兼容版）",
        subtitle = "com.example.very.long.package.name.for.edge.case.testing",
        trailing = { TailNum("9,999") },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "边界态：缺失字段",
        subtitle = "该应用未提供图标与包名，开关状态未知",
        trailing = { TailNum("—") },
    )
}
