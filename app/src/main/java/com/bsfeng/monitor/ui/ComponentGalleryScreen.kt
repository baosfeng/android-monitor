package com.bsfeng.monitor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.LineChart
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.finance.ui.categoryColorOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatHeight
import com.bsfeng.monitor.ui.theme.heatRamp
import com.bsfeng.monitor.ui.theme.sparkHeight
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
/**
 * s12 组件与五态展示厅（原型 s12-components.html）。
 * 新人开工的参照屏：全量组件陈列 + 五态同屏演示 + 弹窗 / 抽屉 / Toast。
 */
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    var state by remember { mutableStateOf(FiveState.POPULATED) }
    var chipIndex by remember { mutableIntStateOf(0) }
    var segIndex by remember { mutableIntStateOf(0) }
    var switchOn by remember { mutableStateOf(true) }
    var switchOff by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // 锚点 screen-s12「状态切换后滚动位置回到该状态块顶部」：记五态区的纵向位置，切状态即滚过去。
    var statesTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (statesTop > 0) scroll.animateScrollTo(statesTop)
    }

    Box(modifier.fillMaxSize().pageBackdrop().testTag("screen-s12")) {
        Column(Modifier.fillMaxSize()) {
            // 页头只作说明：无按下态、无涟漪、不进 Tab 顺序（锚点 s12-head）
            ScreenHead(
                title = "组件与状态",
                meta = "全量组件陈列 · 五种状态 · 弹窗 / 抽屉 / Toast",
                onBack = onBack,
                modifier = Modifier.testTag("s12-head"),
                consumeTopInset = true,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = state, onSelect = { state = it })

                if (state == FiveState.POPULATED) {
                    GalleryButtons(toast)
                    GalleryChipsAndSegments(chipIndex, { chipIndex = it }, segIndex, { segIndex = it }, toast)
                    GallerySwitchesAndBadges(switchOn, { switchOn = it }, switchOff, { switchOff = it }, toast)
                    GalleryRows(toast)
                    GalleryProgress()
                    GalleryChartRamp()
                    GalleryCharts()
                }

                GalleryFiveStates(
                    state = state,
                    onSelect = { state = it },
                    toast = toast,
                    modifier = Modifier.onGloballyPositioned { statesTop = it.positionInParent().y.roundToInt() },
                )
                if (state == FiveState.POPULATED) {
                    GalleryDots()
                    GalleryMotion(toast)
                    GalleryOverlays(
                        onDialog = { showDialog = true },
                        onSheet = { showSheet = true },
                        onToast = { toast.show("已保存 · 1.6s 后自动消失") },
                    )
                }
                Spacer(Modifier.height(t.space6))
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )

        // 删除确认：统一走 ConfirmDialog（窗口级浮层基元 WindowOverlay），不再自绘遮罩 + 卡片
        ConfirmDialog(
            visible = showDialog,
            modifier = Modifier.testTag("s12-dialog"),
            title = "删除这条记录？",
            message = "删除后无法恢复，本地统计会在下次汇总时更新。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = { showDialog = false; toast.show("记录已删除") },
            onDismiss = { showDialog = false },
        )

        if (showSheet) {
            // 遮罩由 BottomSheet 的窗口级全屏浮层承担（页内遮罩盖不住状态栏/手势条，且会叠成两层）
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter)) {
                    BottomSheetCard(
                        onDismiss = { showSheet = false },
                        onConfirm = { types -> showSheet = false; toast.show(sheetConfirmToast(types)) },
                    )
                }
            }
        }
    }
}

