package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的三张卡；职责：类型+金额卡、字段卡（分类/账户/时间/说明）、单据卡、边界演示态。
// 锚点（s14a）：卡片级 s14a-amount / s14a-fields / s14a-receipt 打在外层 [AddTransactionFormSection]
// （原型这三处都是 .pad：含左右 16 的整块，卡片在其内），行级 s14a-kind（含三段 s14a-kind-*）/
// s14a-cat-row / s14a-acct-row / s14a-time-row / s14a-note-row / s14a-receipt-row；
// 边界演示态 s05-edge / s05-edge-cat 也在本文件。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.segmentNavTarget
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 类型分段的逐段锚点（锚点 s14a-kind-expense / -income / -transfer，顺序同 [RecordForm.TYPE_OPTIONS]）。
 *
 * 写成 when 而不是 List：每个锚点都要以 testTag 的字面形态被 grep 逐字查到（验收口径）。
 */
private fun Modifier.kindAnchor(index: Int): Modifier = when (index) {
    0 -> testTag("s14a-kind-expense")
    1 -> testTag("s14a-kind-income")
    else -> testTag("s14a-kind-transfer")
}

/** 分段容器内边距（与共享 SegmentedControl 同口径：.segmented padding 4px） */
private val KIND_SEG_PAD = 4.dp

/**
 * 金额行行盒高：原型 .s14a-amt 量得 79（padding-block 4×2 + input 71）。
 *
 * 为什么不交给字体自动撑：原型 input 的 line-height 是 normal（56px 字 → 71 行盒），
 * Compose 侧 .s14a-input 用的是 headlineLarge 的行高（41sp），单行 BasicTextField 量出来只有
 * 63.7 —— 整张金额卡因此比原型矮 5.3dp，并把它下面三个锚点全部上移。这里按原型钉死行盒，
 * 字形照常绘制（Text / BasicTextField 不裁剪溢出）。
 */
private val AMOUNT_ROW_H = 79.dp

/**
 * 卡片 1px 描边补偿。
 *
 * 原型 .card 是 border-box（1px 描边占位），Compose 的 border **绘制不占位** —— 直接照抄
 * padding 12/16 会让卡内每一行比原型左移 / 上移 1dp、宽 2dp。补偿按台账「自绘卡补 1dp」的口径
 * 加在内容内边距上（同 FinanceStatsDailyAvgCard）。
 */
private val CARD_BORDER = 1.dp

/**
 * 卡片级锚点（s14a-amount / s14a-fields / s14a-receipt）的落点。
 *
 * 原型这三处挂在 `.pad` 上 —— 即「含左右 16 内边距的整块」，宽 393、x=0，卡片本身缩进 16；
 * 锚点落在卡片上会差 x 16 / w 32。
 */
@Composable
private fun AddTransactionFormSection(
    anchor: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(Modifier.fillMaxWidth().testTag(anchor)) {
        Column(Modifier.padding(horizontal = t.space4), content = content)
    }
}

/**
 * 类型 + 金额卡（原型 s14a-amount）。
 *
 * @param shakeOffset 金额校验失败时的横向位移（px）：由屏幕层 [rememberAmountShake] 驱动，
 *   0 表示不动。位移只作用在本卡，不牵动下面的字段卡（原型抖 .s05-input 单条输入）。
 */
@Composable
internal fun AddTransactionAmountCard(
    txType: Int,
    amountText: String,
    onTypeChange: (Int) -> Unit,
    onAmountChange: (String) -> Unit,
    isRefundEntry: Boolean,
    shakeOffset: Float = 0f,
) {
    val t = LocalDesignTokens.current
    // 原型 .s14-card{padding:12px 16px} —— GlassCard 默认四边 16，这里按原型给 12/16（+1px 描边补偿）
    AddTransactionFormSection("s14a-amount") {
        GlassCard(
            padding = 0.dp,
            modifier = Modifier.graphicsLayer { translationX = shakeOffset },
        ) {
            Column(
                Modifier.padding(
                    start = t.space4 + CARD_BORDER,
                    end = t.space4 + CARD_BORDER,
                    top = t.space3 + CARD_BORDER,
                    bottom = t.space3 + CARD_BORDER,
                ),
            ) {
                KindSegment(
                    txType = txType,
                    onTypeChange = onTypeChange,
                    modifier = Modifier.testTag("s14a-kind"),
                )
                Spacer(Modifier.height(t.space2))
                // 原型 .s14a-amt 是 baseline 对齐 + 上下 padding 4px，行盒 79（见 AMOUNT_ROW_H）：
                // ¥ 用 --text-xl(28px)/600，金额用 --text-3xl(56px)/700
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AMOUNT_ROW_H)
                        .padding(vertical = t.space1),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        "¥",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = t.textXl),
                        color = t.muted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(t.space2))
                    BasicTextField(
                        value = amountText,
                        onValueChange = { input ->
                            onAmountChange(input.filter { c -> c.isDigit() || c == '.' })
                        },
                        textStyle = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = t.text3xl,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = AMOUNT_LETTER_SPACING,
                            // 原型 .s14a-input 用 --fg：金额本身不染色，
                            // 方向由上面那排类型段（支出/收入/转账）表达
                            color = t.fg,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        cursorBrush = SolidColor(t.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (amountText.isEmpty()) {
                                    Text(
                                        "0.00",
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontSize = t.text3xl,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = AMOUNT_LETTER_SPACING,
                                        ),
                                        color = t.muted,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                if (isRefundEntry) {
                    Spacer(Modifier.height(t.space2))
                    Text(
                        "退款交易：保存后按负金额存储，用于抵扣当月支出",
                        style = MaterialTheme.typography.labelSmall,
                        color = t.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 收支类型分段「支出／收入／转账」（锚点 s14a-kind + 三段逐段锚点）。
 *
 * 为什么本页不复用共享 [com.bsfeng.monitor.ui.components.SegmentedControl]：交互口径要求**逐段**锚点
 * （s14a-kind-expense / -income / -transfer），而共享件不暴露 per-item modifier。此处按同一 token
 * 口径重构，键盘行为复用共享的 [segmentNavTarget] + [keyboardFocusRing]（roving tabindex）。
 * 待共享件补 itemModifiers 形参后删掉本副本。
 */
@Composable
private fun KindSegment(
    txType: Int,
    onTypeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val options = RecordForm.TYPE_OPTIONS
    val shape = RoundedCornerShape(t.radiusPill)
    // 每段一个 requester：方向键移动后把焦点送到新选中段（否则焦点留在旧段、环画错位置）
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    // 键盘切换后的焦点请求不能在按键栈里零帧发：此刻新段 canFocus 还是 false
    // （下面 focusProperties 的 canFocus 要等重组才按新的 txType 重算），请求当场落空；
    // 重组后旧段 canFocus=false、请求也落空 → 焦点被清空，第一次切换后方向键再也到不了分段。
    // 口径照 ui/components/Chips.kt:179-185 与 ui/MainScreen.kt:358-364：先记「下次该聚焦谁」，
    // 等重组把 canFocus 算完，再在 keyed effect 里请求（LaunchedEffect 在组件树应用之后才启动）。
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { requesters.getOrNull(index)?.requestFocus() }
            pendingFocusIndex = null
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 原型 .segmented：44(tap-min) + 上下各 4 padding + 上下各 1 描边 = 54；
            // Compose 的 border 绘制不占位，所以描边 2dp 要显式算进高度
            .height(t.tapMin + (KIND_SEG_PAD + CARD_BORDER) * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(CARD_BORDER, t.borderSoft, shape)
            .padding(KIND_SEG_PAD)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = segmentNavTarget(event.key, txType, options.size)
                    ?: return@onPreviewKeyEvent false
                if (target != txType) {
                    haptic(HapticTone.LIGHT)
                    onTypeChange(target)
                }
                // 不在按键栈里零帧 requestFocus：先切状态、记下目标段，请求交给重组后的 effect
                pendingFocusIndex = target
                // 导航键一律吞掉：到边界不回绕，也不让父层滚动跟着跑
                true
            },
        horizontalArrangement = Arrangement.spacedBy(KIND_SEG_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == txType
            val bg by animateColorAsState(
                targetValue = if (selected) t.surface else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "kindBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) t.fg else t.muted,
                animationSpec = tween(t.durState),
                label = "kindFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .focusRequester(requesters[index])
                    // roving tabindex：只有选中段可聚焦 → Tab 进组一次，方向键由组统一处理
                    .focusProperties { canFocus = selected }
                    .keyboardFocusRing(shape)
                    .tokenShadow(if (selected) 1 else 0, shape)
                    .clip(shape)
                    .background(bg)
                    .then(Modifier.kindAnchor(index))
                    .clickable {
                        // 切换属轻触级；重复点当前段不再震（口径「已在支出态时再点无变化、不重复触觉」）
                        if (!selected) {
                            haptic(HapticTone.LIGHT)
                            onTypeChange(index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                    modifier = Modifier.testTag("seg.kind"),
                )
            }
        }
    }
}

/**
 * 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 说明 —— 四行同构，与原型一致。
 *
 * 每一行都通过回调上抛字段标识，由屏幕层决定打开哪个抽屉；
 * [catFocus] / [acctFocus] / [timeFocus] 是抽屉关闭后焦点回行的落点（口径「移除/关闭后」列）。
 */
@Composable
internal fun AddTransactionFieldCard(
    txType: Int,
    selectedCategory: String,
    accountName: String,
    toAccountName: String,
    timeText: String,
    remark: String,
    onOpenSheet: (RecordSheet) -> Unit,
    onRemarkChange: (String) -> Unit,
    catFocus: FocusRequester? = null,
    acctFocus: FocusRequester? = null,
    timeFocus: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    AddTransactionFormSection("s14a-fields") {
        AppCard(padding = 0.dp) {
            // 原型 .s14-card 的 padding 是 12/16（+1px 描边补偿）；行分隔线（.s14-toggle 的 border-top）
            // 就在这层内边距之内 —— 内边距必须给在**行外面**，不能塞进行里
            // 横向 +1px：原型 .card 的描边占位，行左边界 = 16(pad) + 1(border) + 16(卡内边距)。
            // 纵向不加：行分隔线在实现里是独立 1dp 元素，已经吃掉原型 border-top 的那 1px。
            Column(
                Modifier.padding(
                    horizontal = t.space4 + CARD_BORDER,
                    vertical = t.space3,
                ),
            ) {
                if (!isTransferType(txType)) {
                    FieldRow(
                        label = "分类",
                        value = selectedCategory,
                        placeholder = "选择分类",
                        onClick = { onOpenSheet(RecordSheet.CATEGORY) },
                        modifier = Modifier.testTag("s14a-cat-row"),
                        focusRequester = catFocus,
                    )
                    FieldDivider()
                }
                FieldRow(
                    label = if (isTransferType(txType)) "转出" else "账户",
                    value = accountName,
                    placeholder = "选择账户",
                    onClick = { onOpenSheet(RecordSheet.ACCOUNT) },
                    modifier = Modifier.testTag("s14a-acct-row"),
                    focusRequester = acctFocus,
                )
                if (isTransferType(txType)) {
                    FieldDivider()
                    // 转账的「转入」行沿用「账户」行语义，但不另贴锚点：一个节点只能有一个 testTag，
                    // 锚点 s14a-acct-row 的「转账态本行标签变「转出」并另出一行「转入」」由本行承接交互
                    FieldRow(
                        label = "转入",
                        value = toAccountName,
                        placeholder = "选择转入账户",
                        onClick = { onOpenSheet(RecordSheet.TO_ACCOUNT) },
                    )
                }
                FieldDivider()
                FieldRow(
                    label = "时间",
                    value = timeText,
                    onClick = { onOpenSheet(RecordSheet.TIME) },
                    modifier = Modifier.testTag("s14a-time-row"),
                    focusRequester = timeFocus,
                )
                FieldDivider()
                NoteRow(
                    value = remark,
                    onValueChange = { onRemarkChange(it.take(REMARK_MAX_LEN)) },
                    modifier = Modifier.testTag("s14a-note-row"),
                )
            }
        }
    }
}

/** 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行。 */
@Composable
internal fun AddTransactionReceiptCard(
    editing: Transaction?,
    onOpenSheet: (RecordSheet) -> Unit,
    receiptFocus: FocusRequester? = null,
) {
    if (!RecordForm.hasReceipt(editing)) return
    val t = LocalDesignTokens.current
    // 块间距由调用方给（与金额卡 / 字段卡一致）：原型三块之间的间距 = .content 的 gap 12 + .pad 的 mt-3 12
    AddTransactionFormSection("s14a-receipt") {
        AppCard(padding = 0.dp) {
            Column(
                Modifier.padding(
                    horizontal = t.space4 + CARD_BORDER,
                    vertical = t.space3 + CARD_BORDER,
                ),
            ) {
                FieldRow(
                    label = "单据",
                    value = RecordForm.receiptSummary(editing),
                    onClick = { onOpenSheet(RecordSheet.RECEIPT) },
                    modifier = Modifier.testTag("s14a-receipt-row"),
                    focusRequester = receiptFocus,
                )
            }
        }
    }
}

// ===== 保存校验的金额抖动（原型 s04-记账.html:738-741 的 is-bad） =====

/** 金额校验的提示文案（原型 s04-记账.html:740 逐字）。 */
internal const val SAVE_AMOUNT_HINT = "请输入大于 0 的金额"

/** 抖动位移（px）：±4 × 3，与 transfer/ui/TransferTaskCardHelpers 的失败抖动同口径。 */
private const val AMOUNT_SHAKE_PX = 4f

/**
 * 金额校验抖动：±4px × 3，时长取 dur-page / 6、曲线 ease-in（与 transfer 的 rememberFailureShake 同参数）。
 *
 * 仓库里现成的抖动件只有 transfer/ui/TransferTaskCardHelpers.rememberFailureShake，那边绑的是
 * 「传输失败」语义；ui/components 层还没有通用 shake，finance 直接引用 transfer 会造出一条
 * finance → transfer 的模块依赖（两个屏互不相干）。所以这里按**同一 token 口径**本地实现，
 * 不新增视觉口径、也不引第三方；等 shake 抽到 ui/components 后本函数删除。
 *
 * @param signal 每次 +1 播一轮（初始 0 = 不动）；动效档为「精简」/系统关闭动画时直接归零。
 */
@Composable
internal fun rememberAmountShake(signal: Int): Animatable<Float, *> {
    val t = LocalDesignTokens.current
    val motion = rememberMotionEnabled()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(signal, motion) {
        if (signal == 0 || !motion) {
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        val step = (t.durPage / 6).coerceAtLeast(1)
        repeat(3) {
            anim.animateTo(AMOUNT_SHAKE_PX, tween(step, easing = t.easeIn))
            anim.animateTo(-AMOUNT_SHAKE_PX, tween(step, easing = t.easeIn))
        }
        anim.animateTo(0f, tween(step, easing = t.easeIn))
    }
    return anim
}

// ===== 边界演示态（原型 s05:482-499；锚点 s05-edge / s05-edge-cat） =====

/** 边界的超长金额：纯演示样本，不入库、不参与任何计算。 */
internal const val EDGE_AMOUNT_SAMPLE = "-¥1,234,567.89"

/** 边界的超长分类名（原型 s05:488 的 .cat-name.is-long，换行不裁切）。 */
internal const val EDGE_LONG_CATEGORY = "人情往来·红白喜事礼金"

/** 边界演示态的其余两个分类（原型 s05:489-490：交通 / 购物）。 */
private val EDGE_CATEGORIES = listOf(EDGE_LONG_CATEGORY, "交通", "购物")

/**
 * 边界演示态的备注样例：正好 [REMARK_MAX_LEN] 字（原型 s05:495「备注（200 字上限）」）。
 *
 * 单测锁住长度 == [REMARK_MAX_LEN]：演示的就是「顶满 200 字上限」这条边界，
 * 少一个字就不再是边界样例了。
 */
internal val EDGE_REMARK_SAMPLE: String =
    "同事婚礼礼金，与另外两位同事合并随礼，事后按人均结算并记入本月人情往来预算，剩余额度将在下月重置。" +
        "备注用于验证超长文本在行内折行与不裁切：写明聚餐人数、结算方式与后续分摊安排，避免月底对账时找不到出处；" +
        "如预算紧张，剩余额度会在下月重置。同行的两位同事分别用不同的方式付款，事后按人均分摊核对账目，" +
        "确认无遗漏后再记一笔。本月剩余额度如不足，系统会在下月重置后重新统计，届时再仔细核对一遍每项明细与分摊。"

/**
 * 保存口径说明行。
 *
 * 原型写的是「金额与备注均为空时，保存按钮不可用。」（s05:498），实现按 #63 改了反馈方式：
 * 金额为空 / 为 0 时按钮**仍可点**，点了抖动金额卡 + toast「请输入大于 0 的金额」
 * （原型 s04-记账.html:735-749 的 data-save 分支）；只有分类未选 / 转账账户不合法才禁用。
 * 演示态照实现写，避免演示态说一套、真机另一套。
 */
internal const val EDGE_SAVE_HINT =
    "金额为空或为 0 时点保存会抖动金额卡并提示「请输入大于 0 的金额」；分类未选时保存按钮不可用。"

/**
 * 记一笔的边界演示态（锚点 s05-edge）。
 *
 * 只在 DEBUG 五态条切「边界」时出现：[addTransactionState] 不产 EDGE，自动态永远走不到这里。
 * 纯展示、没有写入路径 —— 保存条不渲染（原型 s05:454 的 data-state-hide 含 edge）。
 */
@Composable
internal fun AddTransactionEdgeBlock(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = t.space4)
            .testTag("s05-edge"),
    ) {
        Spacer(Modifier.height(t.space3))
        EdgeState(text = "边界演示态：超长金额 / 超长分类名 / 200 字备注")
        Spacer(Modifier.height(t.space3))
        // 超长金额：原型 s05:483-486 的玻璃卡 + kpi-label
        GlassCard(padding = t.space4) {
            Text(
                "超长金额",
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                EDGE_AMOUNT_SAMPLE,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = t.textXl,
                    fontWeight = FontWeight.Bold,
                ),
                color = t.expenseText,
            )
        }
        Spacer(Modifier.height(t.space3))
        // 三个分类芯片：第一格是超长名（锚点 s05-edge-cat），两行换行而不是省略
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.space1),
        ) {
            EDGE_CATEGORIES.forEachIndexed { index, name ->
                EdgeCategoryChip(
                    name = name,
                    selected = index == 0,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (index == 0) Modifier.testTag("s05-edge-cat") else Modifier),
                )
            }
        }
        Spacer(Modifier.height(t.space3))
        // 备注行：原型 s05:492-497 的 group > row（标题 + 200 字正文）
        AppCard(padding = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space4, vertical = t.space3),
                horizontalArrangement = Arrangement.spacedBy(t.space3),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = t.muted,
                    modifier = Modifier.size(t.iconSize),
                ) // decorative：旁边就是「备注（200 字上限）」，图标不承载额外信息
                Column(Modifier.weight(1f)) {
                    Text(
                        "备注（200 字上限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    Spacer(Modifier.height(t.space1))
                    Text(
                        EDGE_REMARK_SAMPLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.fg2,
                    )
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        Text(
            EDGE_SAVE_HINT,
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
        // 给底部五态条让位
        Spacer(Modifier.height(t.space12))
    }
}

/**
 * 边界态的分类芯片：与 CategoryPickerTopLevel.TopLevelCell 同一视觉
 * （选中 = 2dp accent 描边 + 12% accent 淡底），只是不可点 —— 演示态无交互。
 */
@Composable
private fun EdgeCategoryChip(name: String, selected: Boolean, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) t.accent.copy(alpha = EDGE_SELECTED_BG_ALPHA) else Color.Transparent)
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .padding(horizontal = t.space1, vertical = t.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryIcon(name = name, color = categoryColorOf(name), size = t.iconSize)
        Spacer(Modifier.height(t.space1))
        Text(
            displayName(name),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 选中态淡底透明度：与 TopLevelCell 的 SELECTED_BG_ALPHA 同值（12%）。 */
private const val EDGE_SELECTED_BG_ALPHA = 0.12f
