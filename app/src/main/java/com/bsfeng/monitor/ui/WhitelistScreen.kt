package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * s11 应用白名单（原型 s11-whitelist.html）。
 *
 * 搜索真过滤（走 WhitelistViewModel.filterApps）· 应用开关列表 · 无结果空态 · 五态。
 * 旧页面的 MaterialTheme 直取色已按 LEGACY-TOKEN-MAP 换成本地 token 快照。
 */
@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    viewModel: WhitelistViewModel = viewModel(),
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val toast = rememberToastHostState()
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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "应用白名单",
                meta = "白名单内的应用不会被跳过开屏广告",
                onBack = onBack,
            )
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // 五态切换器（演示用）留在 content **之外**：StateContainer 的 loading / empty /
                // error / edge 分支不渲染 content（States.kt:91-106），放进去就再没有切回有数据态的入口。
                // 与 StateContainer 之间的 space3 间距挂在自身 modifier 上：未解锁演示时它不 emit 任何
                // 节点（ComponentGalleryScreen.kt:453），间距随之消失 —— 与改动前外层
                // Arrangement.spacedBy(t.space3) 的行为一致。
                StateSwitchRow(
                    state = demoState,
                    onSelect = { demoState = it },
                    modifier = Modifier.padding(bottom = t.space3),
                )

                // 五态统一走 StateContainer（ui/components/States.kt）；文案取自原型 s11-st-*。
                StateContainer(
                    state = effective,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    emptyTitle = "还没有扫描到应用",
                    emptyText = "授予使用情况访问权限后，这里会列出可加入白名单的应用。",
                    emptyActionLabel = "去授权",
                    onEmptyAction = { toast.show("已跳转系统「使用情况访问」设置") },
                    errorTitle = "读取应用列表失败",
                    errorText = error ?: "PackageManager 拒绝查询（QUERY_ALL_PACKAGES 未声明）。",
                    // 原型 s11-whitelist.html:131 的错态 CTA 文案是「重新扫描」，非默认「重试」
                    errorActionLabel = "重新扫描",
                    onRetry = {
                        viewModel.clearError()
                        viewModel.refresh()
                        toast.show("正在重新扫描已安装应用…")
                    },
                    edgeText = "边界态：超长应用名 / 缺失字段",
                    edgeContent = {
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
                    },
                ) {
                    // StateContainer 的 POPULATED 分支把 content 放进 Box（States.kt:90）：
                    // Box 下多个兄弟节点会叠在同一原点（「搜索框 / 应用列表 / 空态卡片互压 + 页面不可滚动」
                    // 的根因，本项目刚修过）。因此 content lambda 顶层只留 LazyColumn 这一个子节点。
                    //
                    // 虚拟化（本次修复的同一卡顿模式）：原实现是「整页 verticalScroll(Column) + 应用列表
                    // forEachIndexed」，54 个应用（装机量更大时更多）在同一帧全部走组合 / 测量 / 布局 ——
                    // AppRow 每行含真实应用图标（Bitmap）+ 应用名 + 包名 + 开关，主线程被一次性堵死，
                    // 表现与规则页 620 条时同款「点完像卡死」。LazyColumn 只为可视区组合 item，
                    // 组合量从「应用总数」降到「可视条数」（约 8~10 行）。
                    // 结构对齐既有模式（SkipRuleScreen.kt:192 / AdScreen.kt:153 / MainScreen.kt:179）：
                    // 整页单个 LazyColumn + 卡片级 / 行级 item；左右 16dp 仍由各区块自带 padding 提供
                    // （SecHead 组件内部已含 horizontal space4）。
                    // 组间距沿用本页原实现的 space3，不交给 Arrangement.spacedBy —— 相邻应用行必须 0 间距，
                    // StackRow 才能连成一张卡（与 SkipRuleScreen.kt:322 同款手法），故组间距挂在各 item 的
                    // bottom padding 上。
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // ── 搜索 + 快捷 chips + 扫描计数 ──
                        item(key = "search") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = t.space4, end = t.space4, bottom = t.space3),
                            ) {
                                AppField(
                                    value = searchQuery,
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
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                if (keyword == "重置") {
                                                    viewModel.clearSearch()
                                                    toast.show("已重置筛选")
                                                } else {
                                                    viewModel.updateSearchQuery(keyword)
                                                    toast.show("已筛选：" + keyword)
                                                }
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

                        // ── 小节头 ──
                        item(key = "apps-head") {
                            SecHead(
                                "应用列表",
                                "匹配 " + filteredApps.size + " 个",
                                Modifier.padding(bottom = t.space3),
                            )
                        }

                        // ── 应用列表 ──
                        if (filteredApps.isEmpty()) {
                            // 搜索无结果空态卡片：内容与文案与改动前逐字一致（只换了所在容器）。
                            item(key = "apps-empty") {
                                Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                                    AppCard {
                                        Text("没有匹配的应用", style = MaterialTheme.typography.titleMedium, color = t.fg)
                                        Spacer(Modifier.height(t.space2))
                                        Text(
                                            "换个关键词试试，或清空搜索看全部 " + allApps.size + " 个已授权应用。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = t.muted,
                                        )
                                        Spacer(Modifier.height(t.space3))
                                        AppButton("清空搜索", onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            viewModel.clearSearch()
                                        }, variant = AppButtonVariant.Ghost, block = true)
                                    }
                                }
                            }
                        } else {
                            // 一卡一行（不是一卡一应用）：StackRow 首行圆上角 / 末行圆下角、行间 0 间距，
                            // 视觉上仍是改动前那一张连续卡片（相邻行 1dp 描边相接 = 原来的 TokenDivider）。
                            itemsIndexed(
                                items = filteredApps,
                                // key 用包名：数据源是 PackageManager.getInstalledApplications(0) 单次调用后
                                // filter/map（WhitelistViewModel.kt:89-102），不存在「多来源拼接」或未去重的
                                // 列表合并 —— 同一用户下包名由 PackageManager 保证唯一，故不像 SkipRuleScreen.kt:322
                                // （多订阅源 rule.id 可撞）/ AdScreen.kt:265（同毫秒可撞）那样必须带下标兜底；
                                // 包名做主键还让搜索过滤前后复用同一行的组合槽位（不因下标位移整列重建）。
                                // 若日后 apps 改成多来源拼接，这里要回退成 packageName + "#" + index。
                                key = { _, app -> app.packageName },
                            ) { index, app ->
                                // 入场动画只给首屏可见项：rememberStaggerEnter 会给每行挂一个 graphicsLayer，
                                // 长列表滚动时合成成本被放大（AdScreen.kt:271-278、SkipRuleScreen.kt:334 同一处理）。
                                val enterMod = if (index < STAGGER_MAX_STEPS) rememberStaggerEnter(index) else Modifier
                                StackRow(
                                    first = index == 0,
                                    last = index == filteredApps.lastIndex,
                                    modifier = Modifier.padding(horizontal = t.space4).then(enterMod),
                                ) {
                                    // StackRow 的 content 也落在 Box 内：末尾那 4dp 卡片内留白（原 AppCard 内的
                                    // Spacer(space1)）必须与 AppRow 同处一个 Column，否则两个兄弟节点会叠在同一原点。
                                    Column(Modifier.fillMaxWidth()) {
                                        AppRow(
                                            app = app,
                                            onToggle = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                viewModel.toggleWhitelist(app.packageName)
                                                toast.show(app.appName + if (app.isInWhitelist) " 已移出白名单" else " 已加入白名单")
                                            },
                                        )
                                        if (index == filteredApps.lastIndex) Spacer(Modifier.height(t.space1))
                                    }
                                }
                            }
                        }

                        // ── 底部留白 ──：组间距 space3 + 页面尾余量 space6
                        // （原实现 = 外层 Column 的 spacedBy(space3) + content 末尾 Spacer(space6)）。
                        item(key = "bottom-space") {
                            Spacer(Modifier.height(t.space3 + t.space6))
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
 * 白名单应用行：真实图标（无图标回落首字） + 应用名 + 包名 + 开关。
 *
 * 入场动画（stagger）不在这里做：它由调用方（LazyColumn 的 item）注入到 StackRow 的 modifier 上，
 * 与 SkipRuleScreen 的 RuleRow / AdScreen 的 SkipRecordRow 同一分工。
 */
@Composable
private fun AppRow(app: AppInfo, onToggle: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconBitmap = remember(app.icon) {
            app.icon?.toBitmap(72, 72)?.asImageBitmap()
        }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(t.space8).clip(RoundedCornerShape(t.radiusSm)),
            )
        } else {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.appName.take(1), style = MaterialTheme.typography.titleSmall, color = t.fg2)
            }
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space3))
        AppSwitch(app.isInWhitelist, { onToggle() })
    }
}
