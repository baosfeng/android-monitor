package com.bsfeng.monitor.autobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.ui.CategoryPicker
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
    categoryTree: CategoryTree,
    onConfirm: (type: Int, categoryName: String, remark: String) -> Unit,
    onIgnore: () -> Unit,
    onImeFocusRequest: (Boolean) -> Unit = {},
) {
    val tokens = LocalDesignTokens.current

    var type by remember(draft.sourceId) { mutableStateOf(draft.type) }
    // 识别到的分类落到树内的合法名字：旧名 / 历史商户默认值可能已经不在分类表里，
    // 回退该类型的兜底一级，避免「识别：出行」却一个格子都没选中（落库口径与之一致）
    var category by remember(draft.sourceId, categoryTree) {
        mutableStateOf(resolveCategory(categoryTree, draft.type, draft.categoryName))
    }
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
    // 分类树只有支出(0)/收入(1)两棵；浮窗里的「转账」沿用它原来的口径（按支出分类选），
    // 落库时由 AutoBookEngine 校验分类名，不在树里就回退一级「其他」。
    val pickerType = if (type == AutoBookDraft.TYPE_INCOME) 1 else 0

    // 切换支出/收入：原选中项可能不属于该类型（例如「餐饮」不是收入分类），换成该类型的合法分类。
    // 用户点分类不触发这里（key 只有 type / tree），改选不会被覆盖。
    LaunchedEffect(type, categoryTree) {
        category = resolveCategory(categoryTree, type, category)
    }

    // 卡片高度上限 ≈ 82% 窗口高（原型 .ab-sheet max-height:82vh）：小屏上内容区先滚，按钮仍在屏内。
    // 取 WindowInfo.containerSize（px → dp）而非 Configuration.screenHeightDp：后者的 insets 口径随
    // targetSdk 变化、还会取整到 dp；浮窗外层窗口的实际可用高度以容器尺寸为准。
    val density = LocalDensity.current
    val panelMaxHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height * PANEL_MAX_HEIGHT_RATIO).toDp()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 高度上限 + 内部滚动：卡片本身不再无限长，底部「记一笔 / 忽略」恒在屏幕内
            .heightIn(max = panelMaxHeight)
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
                text = "自动记账",
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

        // ── 内容区：可滚动（原型 .ab-body）—— 高度受限时只有这里滚，底部按钮固定 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
        Divider()

        // 说明：账本里唯一的自由文本，放在最上面（原型 .ab-sheet 的顺序）
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

        Divider()

        // 结构化单据字段：只读展示，占一行是一行
        MetaRow("账户", draft.accountName)
        if (draft.payMethod.isNotBlank()) {
            MetaRow("支付方式", draft.payMethod)
        }
        if (draft.timeText.isNotBlank()) {
            MetaRow("时间", draft.timeText)
        }

        Divider()

        // 分类：两级同屏（一级 6×3 图标网格 + 二级 6 列网格），与「记一笔」共用 CategoryPicker。
        // 识别到的分类默认选中（一级/二级都支持），命中的格子右上角标圆点；标题行再写明
        // 「识别：xx」，用户一眼能确认系统猜的是哪个，改选后也还能对照。
        // 点一级只切换网格、点二级才改选中 —— 浮窗仍然只能由「记一笔」/「忽略」关闭。
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
        CategoryPicker(
            tree = categoryTree,
            type = pickerType,
            selectedName = category,
            onPick = { category = it },
            recognizedName = draft.categoryName,
            gridMaxHeight = PANEL_GRID_MAX_H,
            iconSize = PANEL_CATEGORY_ICON_SIZE,
            // 浮窗也用 6 列 3 行的图标网格（与记账页一致），不再是一行横向 chips
            topStyle = com.bsfeng.monitor.finance.ui.CategoryPickerTopStyle.GRID,
        )
        }

        // ── 底部按钮：固定（原型 .ab-foot）—— 内容区再长也不把主操作挤出屏幕 ──
        HorizontalDivider(
            modifier = Modifier.padding(top = MonitorSpacing.md),
            thickness = 1.dp,
            color = tokens.borderSoft,
        )
        Spacer(Modifier.height(MonitorSpacing.md))

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

/**
 * 把分类名归一成「该类型分类树里存在的名字」。
 *
 * 识别结果不一定在树里：分类体系升级前的旧名（如「出行」）或历史商户默认值都可能残留。
 * 树里没有时回退该类型的兜底一级（支出「其他」/ 收入「其他收入」），与
 * [com.bsfeng.monitor.autobook.AutoBookEngine] 落库前的校验同一口径 ——
 * 否则浮窗会显示一个「一个都没选中」的分类，用户不知道最终会记成什么。
 * 树为空（加载失败）时原样返回：浮窗照常可用，落库时由引擎兜底。
 */
private fun resolveCategory(tree: CategoryTree, type: Int, name: String): String {
    val pickerType = if (type == AutoBookDraft.TYPE_INCOME) 1 else 0
    val topNames = tree.topLevel(pickerType).map { it.name }
    if (topNames.isEmpty()) return name
    if (name.isNotBlank() && tree.contains(name) && tree.topLevelNameOf(name) in topNames) {
        return name
    }
    val fallback = if (pickerType == 1) INCOME_FALLBACK else CategoryCatalog.FALLBACK
    return if (fallback in topNames) fallback else topNames.first()
}

/** 收入分类的兜底一级：支出侧兜底是 [CategoryCatalog.FALLBACK]「其他」 */
private const val INCOME_FALLBACK = "其他收入"

/**
 * 浮窗分类区高度受限：一级 6 列 3 行（约 3×52dp）+ 间距 + 二级网格。
 *
 * 内容区整体可滚动（见 [AutoBookConfirmSheet]），所以二级网格不必再压到极小；
 * 176dp ≈ 6 列下两行多一点，任一常见的 4-9 个二级都能滚到。
 */
private val PANEL_GRID_MAX_H: Dp = 176.dp

/** 浮窗里的分类格图标：与「记一笔」同一口径 32dp（原型 .cat-icon 32×32） */
private val PANEL_CATEGORY_ICON_SIZE: Dp = 32.dp

/** 卡片高度上限占屏高比例（原型 .ab-sheet max-height:82vh） */
private const val PANEL_MAX_HEIGHT_RATIO = 0.82f

/** 金额格式化：千分位 + 固定两位小数（与记账页交易行口径一致） */
internal fun formatAmount(amount: Double): String =
    String.format(java.util.Locale.CHINA, "%,.2f", kotlin.math.abs(amount))
