package com.bsfeng.monitor.autobook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.ui.categoryColorOf
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorRadius
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.opaquePanel

/**
 * 自动记账确认卡片（浮窗内容）。
 *
 * 信息层级与 `.open-design/23-autobook-styles.html` 的风格 1/4 一致：
 * 来源胶囊 → 金额 → 商户 → 类型 → 分类 → 账户/时间/说明 → 操作按钮。
 *
 * 所有颜色 / 圆角 / 间距都走设计 token，因此 8 套主题下自动成立。
 *
 * **关闭契约（2026-10-20）**：卡片里只有「记一笔」和「忽略」两个按钮能让浮窗消失；
 * 说明行只进入编辑态，绝不关闭浮窗（点遮罩同样不关闭，见 [AutoBookDismissPolicy]）。
 */
@Composable
fun AutoBookConfirmSheet(
    draft: AutoBookDraft,
    categoriesByType: Map<Int, List<Category>>,
    onConfirm: (type: Int, categoryName: String, remark: String) -> Unit,
    onIgnore: () -> Unit,
    onImeFocusRequest: (Boolean) -> Unit = {},
) {
    val tokens = LocalDesignTokens.current

    var type by remember(draft.sourceId) { mutableStateOf(draft.type) }
    var category by remember(draft.sourceId) { mutableStateOf(draft.categoryName) }
    // 说明：预填付款前页面的备注，用户可在浮窗里改；确认时以这里为准
    var remark by remember(draft.sourceId) { mutableStateOf(draft.userRemark) }
    var editing by remember(draft.sourceId) { mutableStateOf(false) }
    var remarkFocused by remember { mutableStateOf(false) }
    val remarkFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 收起编辑态：只关键盘、不关浮窗。忽略按钮与说明行的「完成」/ 失焦共用同一条路径。
    val finishEdit = {
        if (editing) {
            editing = false
            focusManager.clearFocus()
        }
    }

    // 编辑说明：窗口要先从 FLAG_NOT_FOCUSABLE 切到可聚焦，输入法才会弹，否则输入框拿不到焦点。
    // 失焦 / 点「完成」后恢复不可聚焦，把输入交还目标 App。
    LaunchedEffect(editing) {
        if (!editing) {
            onImeFocusRequest(false)
            return@LaunchedEffect
        }
        onImeFocusRequest(true)
        // flags → updateViewLayout 要过一次 View 刷新，等两帧再抢焦点
        withFrameNanos { }
        withFrameNanos { }
        // FocusRequester.requestFocus() 在本版本没有返回值，只能靠 onFocusChanged 回读是否拿到焦点
        var attempt = 0
        while (!remarkFocused && attempt < 3) {
            runCatching { remarkFocus.requestFocus() }
            withFrameNanos { }
            attempt++
        }
        // 拿不到焦点（ROM 限制 / 窗口未及时可聚焦）就退回不可编辑，不假装能输入
        if (!remarkFocused) editing = false
    }

    val isIncome = type == AutoBookDraft.TYPE_INCOME
    val amountColor = if (isIncome) tokens.success else tokens.danger
    // 识别出的分类不一定在预设分类表里（例如平台分类「保险」当前没有对应分类，解析器兜底成「其他」，
    // 而支出预设里没有「其他」）。这里把它补进网格，保证「识别到的分类」一定有可高亮的格子，
    // 否则用户看到的就是「一个都没选中」。
    val categories = remember(type, categoriesByType, draft.sourceId, draft.type, draft.categoryName) {
        val base = categoriesByType[if (type == AutoBookDraft.TYPE_INCOME) 1 else 0].orEmpty()
        val recognized = draft.categoryName
        if (type == draft.type && recognized.isNotBlank() && base.none { it.name == recognized }) {
            base + Category(name = recognized, type = type, sortOrder = Int.MAX_VALUE)
        } else {
            base
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = MonitorRadius.lg, topEnd = MonitorRadius.lg))
            // 实心面板：玻璃主题的 surface 是 74% alpha，直接用会透出下面的支付页文字。
            // opaquePanel 把 surface-warm → surface → bg 合成到 100% 不透明（有意偏离玻璃语言）。
            .background(tokens.opaquePanel)
            // 浮窗已铺满全屏：底色铺到屏幕底，内容上抬到手势条之上，否则「记一笔」会被压住
            .navigationBarsPadding()
            .padding(horizontal = MonitorSpacing.lg)
            .padding(top = MonitorSpacing.md, bottom = MonitorSpacing.xl)
    ) {
        // 顶部把手
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(tokens.fg.copy(alpha = 0.16f))
        )

        Spacer(Modifier.height(MonitorSpacing.md))

        // 标题行：识别结果 + 来源
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "识别到一笔${if (isIncome) "收入" else "支出"}",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.fg
            )
            SourceChip(draft.channelLabel, draft.channel)
        }

        Spacer(Modifier.height(MonitorSpacing.sm))

        // 金额
        Text(
            text = (if (isIncome) "+¥" else "-¥") + formatAmount(draft.amount),
            style = MaterialTheme.typography.headlineLarge,
            color = amountColor
        )

        // 商户
        if (draft.merchant.isNotBlank()) {
            Spacer(Modifier.height(MonitorSpacing.xs))
            Text(
                text = draft.merchant,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted
            )
        }

        Divider()

        // 类型
        Label("类型")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MonitorRadius.sm))
                .background(tokens.fg.copy(alpha = 0.06f))
                .padding(3.dp)
        ) {
            listOf(
                AutoBookDraft.TYPE_EXPENSE to "支出",
                AutoBookDraft.TYPE_INCOME to "收入",
                AutoBookDraft.TYPE_TRANSFER to "转账"
            ).forEach { (value, label) ->
                SegmentItem(
                    text = label,
                    selected = type == value,
                    modifier = Modifier.weight(1f)
                ) { type = value }
            }
        }

        Divider()

        // 分类：6 列图标网格（与「记一笔」同一套分类格）。
        // 识别到的分类额外标一个圆点 + 选中底色；标题行再写明「识别：xx」，
        // 用户一眼能确认系统猜的是哪个，改选后也还能对照。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MonitorSpacing.md, bottom = MonitorSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "分类",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.muted,
            )
            if (draft.categoryName.isNotBlank()) {
                Text(
                    text = "识别：" + draft.categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.accentText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        CategoryGrid(
            categories = categories,
            selectedName = category,
            recognizedName = draft.categoryName,
            onSelect = { category = it },
        )

        Divider()

        MetaRow("账户", draft.accountName)
        if (draft.payMethod.isNotBlank()) {
            MetaRow("支付方式", draft.payMethod)
        }
        if (draft.timeText.isNotBlank()) {
            MetaRow("时间", draft.timeText)
        }

        // 说明：账本里唯一的自由文本。取代原来只读的「备注」行 ——
        // 2026-10-20 用户要求：说明由用户自己写，不再自动拼商户 / 商品说明 / 付款方式。
        Label("说明")
        RemarkRow(
            remark = remark,
            onRemarkChange = { remark = it },
            editing = editing,
            onRequestEdit = { if (!editing) editing = true },
            onFinishEdit = finishEdit,
            onFocusChange = { remarkFocused = it },
            focusRequester = remarkFocus,
        )

        Spacer(Modifier.height(MonitorSpacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MonitorSpacing.sm)
        ) {
            Button(
                onClick = {
                    // 唯一的「忽略」入口：先收起编辑态，再交给引擎写忽略记忆
                    finishEdit()
                    onIgnore()
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(MonitorRadius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.fg.copy(alpha = 0.07f),
                    contentColor = tokens.fg
                )
            ) {
                Text("忽略", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { onConfirm(type, category, AutoBookDraft.normalizeRemark(remark)) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(MonitorRadius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.accent,
                    contentColor = tokens.accentOn
                )
            ) {
                Text(
                    text = "记一笔",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, channel: String) {
    val tokens = LocalDesignTokens.current
    val brandColor = when (channel) {
        AutoBookRules.CHANNEL_WECHAT -> tokens.success
        else -> tokens.accent
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MonitorRadius.circle))
            .background(brandColor.copy(alpha = 0.14f))
            .padding(start = 4.dp, end = MonitorSpacing.sm, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(brandColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.accentOn,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = brandColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MonitorRadius.sm - 2.dp))
            .background(if (selected) tokens.surface else tokens.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(vertical = MonitorSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.fg else tokens.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 分类网格：6 列，图标 + 名称，一屏约 3 行。
 *
 * 与「记一笔」的 [com.bsfeng.monitor.finance.ui] 分类抽屉同一套视觉：
 * 选中 = 图标外描边 + 淡色底；识别到的分类右上角多一个圆点（改选后仍保留，便于对照）。
 *
 * 用普通 Column + verticalScroll 而不是 LazyVerticalGrid：分类最多 22 个，一次性组合成本很低，
 * 但能避免懒加载网格在受限高度里滚动时反复组合/回收 item。真机反馈浮窗里滑动「很卡」，
 * 这里优先保证滚动跟手（2026-10-20）。
 *
 * 识别项可能在第二屏：打开后按行高把它滚进可视区，用户不需要自己翻（2026-10-20 反馈）。
 */
@Composable
private fun CategoryGrid(
    categories: List<Category>,
    selectedName: String,
    recognizedName: String,
    onSelect: (String) -> Unit,
) {
    val rows = remember(categories) { categories.chunked(CATEGORY_COLUMNS) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val rowGapPx = with(density) { MonitorSpacing.sm.toPx() }
    // 行高由第一行实测（不同字号/主题会变），拿到后才知道该滚多少
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(selectedName, categories, rowHeightPx) {
        if (rowHeightPx <= 0f) return@LaunchedEffect
        val index = categories.indexOfFirst { it.name == selectedName }
        if (index < 0) return@LaunchedEffect
        // 先等一帧，保证 maxValue 已按实际内容高度更新
        withFrameNanos { }
        val row = index / CATEGORY_COLUMNS
        val target = (row * (rowHeightPx + rowGapPx)).toInt()
        scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 176.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(MonitorSpacing.sm),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (rowHeightPx <= 0f) rowHeightPx = coords.size.height.toFloat()
                    },
                horizontalArrangement = Arrangement.spacedBy(MonitorSpacing.sm),
            ) {
                row.forEach { item ->
                    CategoryGridCell(
                        name = item.name,
                        selected = item.name == selectedName,
                        recognized = item.name == recognizedName,
                        onClick = { onSelect(item.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 6 个时补空位，保持每列等宽
                repeat(CATEGORY_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 分类网格列数（一行 6 个：一屏能扫到更多分类，减少滑动） */
private const val CATEGORY_COLUMNS = 6

@Composable
private fun CategoryGridCell(
    name: String,
    selected: Boolean,
    recognized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalDesignTokens.current
    val shape = RoundedCornerShape(MonitorRadius.md)
    // categoryColorOf 读取组合局部 token，只能在 @Composable 上下文直接调用（不能塞进 remember）
    val color = categoryColorOf(name)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) tokens.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = MonitorSpacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .border(2.dp, if (selected) tokens.accent else Color.Transparent, shape)
                    .padding(2.dp),
            ) {
                CategoryIcon(name = name, color = color, size = 26.dp)
            }
            // 识别标记：系统猜的分类，改选后仍保留（用户可对照自己选的和系统猜的）
            if (recognized) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .border(1.dp, tokens.opaquePanel, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(MonitorSpacing.xxs))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) tokens.accentText else tokens.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Label(text: String) {
    val tokens = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.muted,
        modifier = Modifier.padding(top = MonitorSpacing.md, bottom = MonitorSpacing.sm)
    )
}

@Composable
private fun Divider() {
    val tokens = LocalDesignTokens.current
    HorizontalDivider(
        modifier = Modifier.padding(vertical = MonitorSpacing.md),
        thickness = 1.dp,
        color = tokens.borderSoft
    )
}

@Composable
private fun MetaRow(key: String, value: String) {
    val tokens = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MonitorSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.labelMedium, color = tokens.muted)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.fg,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 可编辑的「说明」行：未编辑时是灰色占位「写一下买了什么」，点整行进入编辑态。
 *
 * 编辑态**只影响窗口的输入焦点**，不会关闭浮窗；失焦 / 点「完成」/ 键盘 Done 都只收起键盘。
 * 之所以点整行而不是直接点输入框：窗口默认 FLAG_NOT_FOCUSABLE，输入框在窗口不可聚焦时
 * 拿不到焦点，必须先把窗口切成可聚焦（见 [AutoBookOverlay.setImeFocusable]）。
 */
@Composable
private fun RemarkRow(
    remark: String,
    onRemarkChange: (String) -> Unit,
    editing: Boolean,
    onRequestEdit: () -> Unit,
    onFinishEdit: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    focusRequester: FocusRequester,
) {
    val tokens = LocalDesignTokens.current
    val focusManager = LocalFocusManager.current
    // 「曾经拿到过焦点后失焦」才算结束编辑：首次组合时输入框本来就是无焦点，不能误判
    var hadFocus by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 44dp 与记账页输入行同高（触摸目标下限，布局语义尺寸故留字面量）
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(MonitorRadius.sm))
            .background(tokens.fg.copy(alpha = 0.06f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onRequestEdit() }
            .padding(horizontal = MonitorSpacing.md, vertical = MonitorSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = remark,
                onValueChange = { onRemarkChange(it.take(AutoBookDraft.REMARK_MAX_LENGTH)) },
                enabled = editing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.fg,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    onFinishEdit()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        onFocusChange(state.isFocused)
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            onFinishEdit()
                        }
                    },
            )
            if (remark.isBlank()) {
                Text(
                    text = "写一下买了什么",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.muted,
                )
            }
        }
        if (editing) {
            Spacer(Modifier.width(MonitorSpacing.sm))
            Text(
                text = "完成",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(MonitorRadius.xs))
                    .clickable { onFinishEdit() }
                    .padding(horizontal = MonitorSpacing.xs, vertical = MonitorSpacing.xxs),
            )
        }
    }
}

/** 金额格式化：千分位 + 固定两位小数（与记账页交易行口径一致） */
internal fun formatAmount(amount: Double): String =
    String.format(java.util.Locale.CHINA, "%,.2f", kotlin.math.abs(amount))
