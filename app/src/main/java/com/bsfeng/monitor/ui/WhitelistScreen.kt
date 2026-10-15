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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                StateSwitchRow(state = demoState, onSelect = { demoState = it })

                // 五态统一走 StateContainer（ui/components/States.kt）；文案取自原型 s11-st-*。
                StateContainer(
                    state = effective,
                    modifier = Modifier.fillMaxWidth(),
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




                        // 搜索（真过滤）
                        Column(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
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

                        SecHead("应用列表", "匹配 " + filteredApps.size + " 个")

                        if (filteredApps.isEmpty()) {
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
                        } else {
                            AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
                                filteredApps.forEachIndexed { index, app ->
                                    TokenDivider(first = index == 0)
                                    AppRow(
                                        app = app,
                                        index = index,
                                        onToggle = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            viewModel.toggleWhitelist(app.packageName)
                                            toast.show(app.appName + if (app.isInWhitelist) " 已移出白名单" else " 已加入白名单")
                                        },
                                    )
                                }
                                Spacer(Modifier.height(t.space1))
                            }
                        }
                        Spacer(Modifier.height(t.space6))
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

/** 白名单应用行：真实图标（无图标回落首字） + 应用名 + 包名 + 开关。 */
@Composable
private fun AppRow(app: AppInfo, index: Int, onToggle: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(rememberStaggerEnter(index))
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
