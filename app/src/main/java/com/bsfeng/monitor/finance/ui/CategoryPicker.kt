package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 两级分类选择器：一级 chips + 二级 6 列网格（浮窗与「记一笔」共用）。
 *
 * 数据来自 [CategoryTree]（分类表 parent_id 父子关系）；交易 `category_name` 存二级名
 * （有细分）或一级名（选「不细分」），所以选择器只回传名字，落库口径由调用方负责。
 *
 * 交互契约（见 docs/记账/分类体系设计.md「UI」）：
 * - 点一级 chip **只切换下方网格，不回调、不关闭**；
 * - 点二级或「不细分」才通过 [onPick] 回传最终分类名。
 *
 * @param recognizedName 自动记账识别出的分类（可能是二级）；命中的格子右上角标圆点
 * @param gridMaxHeight 二级网格最大高度，超出后可滚动（浮窗里高度受限，抽屉里较宽裕）
 * @param iconSize 二级格子图标尺寸（浮窗比抽屉略小）
 */
@Composable
fun CategoryPicker(
    tree: CategoryTree,
    type: Int,
    selectedName: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    recognizedName: String = "",
    gridMaxHeight: Dp = PICKER_DEFAULT_GRID_MAX_H,
    iconSize: Dp = PICKER_DEFAULT_ICON_SIZE,
    topStyle: CategoryPickerTopStyle = CategoryPickerTopStyle.CHIPS,
) {
    val t = LocalDesignTokens.current
    // 识别到的名字属于哪个一级（空 = 没识别 / 不在本类型树里）
    val recognizedTop = remember(tree, type, recognizedName) {
        recognizedTopName(tree, type, recognizedName)
    }
    // 当前展示哪个一级是纯 UI 状态：外部选中名没变时，用户点一级的结果必须保住
    var activeTopName by remember(tree, type) {
        mutableStateOf(
            recognizedTop.ifBlank { CategoryPickerState(tree, type, selectedName).activeTopName }
        )
    }
    // 默认只展示一级；**识别项自动展开它的一级**（原型 pkApplyRecognized 口径），点一级也展开
    var expanded by remember(tree, type) { mutableStateOf(recognizedTop.isNotBlank()) }
    // 同一浮窗换了识别结果（下一笔账单）时重新定位回识别项
    var lastRecognizedTop by remember(tree, type) { mutableStateOf(recognizedTop) }
    LaunchedEffect(tree, type, recognizedName) {
        if (recognizedTop.isBlank() || recognizedTop == lastRecognizedTop) return@LaunchedEffect
        lastRecognizedTop = recognizedTop
        activeTopName = recognizedTop
        expanded = true
    }
    // 外部选中变化（切换类型 / 编辑交易）时重新定位到选中项的一级；
    // 同一级里点二级不收起已展开的网格（原型 pkPickL2 保持面板可见）
    LaunchedEffect(tree, type, selectedName) {
        val top = CategoryPickerState(tree, type, selectedName).activeTopName
        if (top.isNotBlank() && top != activeTopName) activeTopName = top
    }
    val state = remember(tree, type, selectedName, activeTopName) {
        CategoryPickerState(tree, type, selectedName, activeTopName)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (state.topLevel.isEmpty()) {
            Text(
                "分类数据还没准备好",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(vertical = t.space3),
            )
            return@Column
        }
        when (topStyle) {
            CategoryPickerTopStyle.CHIPS -> TopLevelRow(
                state = state,
                onSelectTop = { name -> activeTopName = name; expanded = true },
            )
            CategoryPickerTopStyle.GRID -> {
                SectionTitle("一级分类")
                TopLevelGrid(
                    state = state,
                    onSelectTop = { name -> activeTopName = name; expanded = true },
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(t.space3))
            SecondaryGrid(
                state = state,
                recognizedName = recognizedName,
                gridMaxHeight = gridMaxHeight,
                iconSize = iconSize,
                onPick = onPick,
            )
        }
    }
}

/**
 * 选择器纯逻辑状态（无 Compose 依赖，可 JVM 单测）。
 *
 * 负责三件事：
 * 1. 从「当前选中名」反推应该高亮哪个一级（选中名可能是二级，例如「电商」→「购物」）；
 * 2. 给出当前一级下的二级列表（网格内容）；
 * 3. 把用户操作翻译成回传值：选二级 → 二级名；选「不细分」→ 当前一级名。
 *
 * 不可变：切换一级返回新实例（[selectTop]），Compose 侧用 mutableState 持有当前展示的一级。
 */
class CategoryPickerState(
    private val tree: CategoryTree,
    val type: Int,
    val selectedName: String = "",
    initialTopName: String? = null,
) {

    /** 该类型的一级分类（按 sortOrder） */
    val topLevel: List<Category> = tree.topLevel(type)

    /** 选中名所属的一级；不是本类型的一级/二级（未知、脏数据）时为空 */
    val selectedTopName: String = run {
        if (selectedName.isBlank() || !tree.contains(selectedName)) return@run ""
        val top = tree.topLevelNameOf(selectedName)
        if (topLevel.any { it.name == top }) top else ""
    }

    /** 当前展示的一级：优先显式切换结果，其次选中项的一级，最后第一个一级 */
    val activeTopName: String = run {
        if (!initialTopName.isNullOrBlank() && topLevel.any { it.name == initialTopName }) {
            return@run initialTopName
        }
        selectedTopName.ifBlank { topLevel.firstOrNull()?.name ?: "" }
    }

    /** 一级 chips 的初始定位下标 */
    val activeTopIndex: Int = topLevel.indexOfFirst { it.name == activeTopName }.coerceAtLeast(0)

    /** 当前一级下的二级名（网格内容，不含「不细分」项） */
    val children: List<String> = tree.childrenOf(activeTopName).map { it.name }

    /** 选中的是本一级下的二级时，返回该二级名（网格高亮用）；否则空串 */
    val selectedChildName: String = if (children.contains(selectedName)) selectedName else ""

    /** 选中的正好是当前一级（「不细分」项高亮） */
    val isTopLevelOnlySelected: Boolean =
        selectedName.isNotBlank() && selectedName == activeTopName

    /** 只切换展示的一级，不动选中值（用户还在挑细分） */
    fun selectTop(name: String): CategoryPickerState =
        CategoryPickerState(tree, type, selectedName, name)

    /**
     * 选二级：名字必须是当前一级下的二级才回传，否则返回空串。
     *
     * 校验的意义是防「把别的类型/别的父级的名字写进交易」——选择器本身只会传回点击到的格子。
     */
    fun chooseChild(name: String): String = if (children.contains(name)) name else ""

    /** 选「不细分」：记一级 */
    fun chooseTopOnly(): String = activeTopName
}

/** 二级网格默认最大高度：6 列下约 2 行，够放下任一常见的 4-9 个二级 */
private val PICKER_DEFAULT_GRID_MAX_H: Dp = 176.dp

/** 二级格子图标默认尺寸（抽屉里 6 列，图标略收小） */
private val PICKER_DEFAULT_ICON_SIZE: Dp = 32.dp

/** 一级 chip 高度：走 --tap-min，触控目标不缩水（分类区总高的另一半留给二级网格） */
private val TOP_CHIP_MIN_H: Dp = 44.dp

/** 二级网格列数：一行 6 个（与自动记账浮窗一致，减少滑动） */
private const val PICKER_COLUMNS = 6

/** 识别标记圆点直径（沿用自动记账浮窗既有视觉：9dp 实心 + 面板色描边） */
private val RECOGNIZED_DOT_SIZE: Dp = 9.dp

/** 选中格子淡底（原型 accent 描边 + 极淡底，避免 6 列网格一片空白） */
private const val SELECTED_BG_ALPHA = 0.08f

/** 识别格淡底（原型 .ab-sheet .cat-l2.is-recognized = accent 10%） */
private const val RECOGNIZED_BG_ALPHA = 0.12f

/**
 * 识别到的分类名属于哪个一级（纯函数，可 JVM 单测）。
 *
 * 空串 = 没识别、名字不在树里、或不属于该类型的分类树 —— 调用方按「无识别」处理（默认折叠）。
 */
internal fun recognizedTopName(tree: CategoryTree, type: Int, recognizedName: String): String {
    if (recognizedName.isBlank()) return ""
    if (!tree.contains(recognizedName)) return ""
    val top = tree.topLevelNameOf(recognizedName)
    return if (tree.topLevel(type).any { it.name == top }) top else ""
}

/**
 * 一级显示名：网格里直接用全称（6 列 11sp 放得下 4 字，与原型 s05/s14a 一致）。
 */
private fun displayName(name: String): String = name

/** 区块标题：一级 / 二级两层用标题和名字区分，用户一眼知道当前在挑哪一层 */
@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = t.space2, bottom = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = t.muted)
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(t.space1))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = t.accentText,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 一级展示样式：抽屉里完整展示全部一级（网格），浮窗空间小用横向 chips */
enum class CategoryPickerTopStyle { CHIPS, GRID }

/**
 * 一级网格：6 列完整展示全部一级（18 个 = 3 行，不用横向翻）。
 *
 * 记账抽屉里一级是「主入口」，要一眼看全；浮窗高度有限仍用 chips（[CategoryPickerTopStyle.CHIPS]）。
 */
@Composable
private fun TopLevelGrid(state: CategoryPickerState, onSelectTop: (String) -> Unit) {
    val t = LocalDesignTokens.current
    val rows = remember(state.topLevel) { state.topLevel.chunked(PICKER_COLUMNS) }
    Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(t.space1),
            ) {
                row.forEach { top ->
                    TopLevelCell(
                        name = top.name,
                        selected = top.name == state.activeTopName,
                        onClick = { onSelectTop(top.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(PICKER_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TopLevelCell(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    // 原型定稿口径：图标是单色线描（未选中 fg2、选中 accent），不再用分类色底纹
    val color = if (selected) t.accentText else t.fg2
    Column(
        modifier = modifier
            .clip(shape)
            // 选中：2dp accent 描边 + 淡底（原型 .cat-l1[aria-pressed] 的 accent-text + outline）
            .background(if (selected) t.accent.copy(alpha = SELECTED_BG_ALPHA) else Color.Transparent)
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(vertical = t.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryIcon(name = name, color = color, size = 32.dp)
        Spacer(Modifier.height(2.dp))
        Text(
            displayName(name),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一级 chips：横向滚动，点一下只切换下方网格 */
@Composable
private fun TopLevelRow(state: CategoryPickerState, onSelectTop: (String) -> Unit) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    // 初始定位到选中项的一级：识别到的可能是「购物·电商」，不给定位就要用户自己翻 chips
    LaunchedEffect(state.activeTopIndex, state.topLevel) {
        if (state.activeTopIndex > 0) listState.scrollToItem(state.activeTopIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items(state.topLevel.size) { index ->
            val top = state.topLevel[index]
            TopLevelChip(
                name = top.name,
                selected = top.name == state.activeTopName,
                onClick = { onSelectTop(top.name) },
            )
        }
    }
}

@Composable
private fun TopLevelChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = Modifier
            .heightIn(min = TOP_CHIP_MIN_H)
            .clip(shape)
            .background(if (selected) t.accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) t.accent else t.border, shape)
            .clickable { onClick() }
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 二级网格：第一格是「不细分」（直接把一级名当分类），后面是当前一级的二级。
 *
 * 高度受限时整块可滚动（普通 Column + verticalScroll，不用 LazyVerticalGrid：项数 ≤ 12，
 * 一次性组合成本低，滚动不会反复组合/回收 item —— 真机反馈浮窗里懒加载网格滑动很卡）。
 * 识别到的二级可能落在第二行以后：按实测行高把它滚进可视区，用户不用自己翻。
 */
@Composable
private fun SecondaryGrid(
    state: CategoryPickerState,
    recognizedName: String,
    gridMaxHeight: Dp,
    iconSize: Dp,
    onPick: (String) -> Unit,
) {
    val t = LocalDesignTokens.current
    // 二级网格只展示真实二级分类：不再有「不细分」伪分类，一级只是导航
    val cells = remember(state.activeTopName, state.children) {
        state.children.map { PickerCellModel(name = it, label = it) }
    }
    val rows = remember(cells) { cells.chunked(PICKER_COLUMNS) }
    // 切换一级时回到顶部：上一级的滚动位置对新网格没有意义
    val scrollState = remember(state.activeTopName) { ScrollState(0) }
    val rowHeightPx = remember(state.activeTopName) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowGapPx = with(density) { t.space2.toPx() }

    LaunchedEffect(state.activeTopName, recognizedName, rowHeightPx.floatValue) {
        val rowHeight = rowHeightPx.floatValue
        if (rowHeight <= 0f) return@LaunchedEffect
        val index = cells.indexOfFirst { it.name == recognizedName }
        if (index < 0) return@LaunchedEffect
        // 等一帧：maxValue 要按实际内容高度更新后才知道能滚多远
        withFrameNanos { }
        val row = index / PICKER_COLUMNS
        val target = (row * (rowHeight + rowGapPx)).toInt()
        scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    SectionTitle("二级分类", state.activeTopName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = gridMaxHeight)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(t.space1),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (rowHeightPx.floatValue <= 0f) {
                            rowHeightPx.floatValue = coords.size.height.toFloat()
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(t.space1),
            ) {
                row.forEach { cell ->
                    PickerCell(
                        cell = cell,
                        selected = cell.name == state.selectedChildName,
                        recognized = cell.name == recognizedName,
                        iconName = state.activeTopName,
                        iconSize = iconSize,
                        onClick = {
                            val picked = state.chooseChild(cell.name)
                            if (picked.isNotBlank()) onPick(picked)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 6 个时补空位，保持每列等宽
                repeat(PICKER_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格项：二级名字，或当前一级的「不细分」 */
private data class PickerCellModel(
    val name: String,
    val label: String,
)

@Composable
private fun PickerCell(
    cell: PickerCellModel,
    selected: Boolean,
    recognized: Boolean,
    iconName: String,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    // 与一级格同一套视觉：图标单色线描（未选中 fg2、选中 accent），无分类色底纹
    val color = if (selected) t.accentText else t.fg2
    Column(
        modifier = modifier
            .clip(shape)
            // 与一级格同一套视觉：无底色，选中 2dp 描边 + 淡底；识别项再淡一层（原型 .is-recognized）
            .background(
                when {
                    recognized -> t.accent.copy(alpha = RECOGNIZED_BG_ALPHA)
                    selected -> t.accent.copy(alpha = SELECTED_BG_ALPHA)
                    else -> Color.Transparent
                }
            )
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(vertical = t.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            // 「不细分」用一级分类的图标；二级多数还没有专属线描图标，CategoryIcon 会回落彩点
            // 二级多数还没有专属线描图标：统一用父级图标，避免一堆同色圆点
            CategoryIcon(name = iconName, color = color, size = iconSize)
            if (recognized) {
                // 识别标记（沿用自动记账浮窗）：右上角小圆点，用户改选后仍能对照系统猜的是哪个
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(RECOGNIZED_DOT_SIZE)
                        .clip(CircleShape)
                        .background(t.accent)
                        .border(1.dp, t.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(t.space1))
        Text(
            displayName(cell.label),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
