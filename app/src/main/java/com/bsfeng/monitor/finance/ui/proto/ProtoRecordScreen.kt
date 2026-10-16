package com.bsfeng.monitor.finance.ui.proto

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.appBackdrop
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorIconSize
import com.bsfeng.monitor.ui.theme.fgFade
import java.time.LocalDate

/**
 * s14a「记一笔 · 一屏 · 全抽屉」主屏（定版原型 `.open-design/26-a-quan-chouti.html`）。
 *
 * 界面结构逐段对齐原型：玻璃金额卡（类型段 + 大金额）→ 字段卡（分类 / 账户 / 时间 / 备注四行同构）
 * → 单据卡 → 吸底保存条；四个字段行点开各自的底部抽屉，抽屉开合不影响上方表单高度。
 *
 * 关于锚点：每个原型 `data-od-id` 都在实现里落成 `Modifier.testTag(...)`（名字逐字一致，见 [ProtoAnchors]），
 * 供「原型 ↔ 实现」机器对照。四个抽屉在 [ProtoRecordSheets]。
 *
 * @param onSave 保存回调（proto 屏不落库，把表单载荷交给调用方）。
 * @param state 表单状态；默认取 [rememberProtoRecordState]（原型首屏的「有数据」态）。
 */
@Composable
internal fun ProtoRecordScreen(
    onSave: (ProtoRecordDraft) -> Unit = {},
    state: ProtoRecordState = rememberProtoRecordState(),
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val today = LocalDate.now()
    val draft = state.toDraft()
    val canSave = ProtoRecordLogic.canSave(state.amountText, state.categoryName, state.accountName)

    // 抽屉打开时接管返回键：返回 = 关闭抽屉（原型点遮罩 / 关闭按钮的等价物）
    BackHandler(enabled = state.openSheet != ProtoSheet.NONE) { state.openSheet = ProtoSheet.NONE }

    Box(modifier.fillMaxSize().appBackdrop()) {
        // 原型 .content：一个滚动容器，吸底动作条是它的最后一个子节点
        // （s14a-content 因此挂在外层 —— 让 s14a-actions 在语义树里仍是它的后代）
        Column(Modifier.fillMaxSize().testTag(ProtoAnchors.CONTENT)) {

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 原型 .content 上内边距 --space-2 与首卡 .mt-3 叠加 = 20
                Spacer(Modifier.height(t.space5))
                AmountCard(
                    state = state,
                    modifier = Modifier
                        .padding(horizontal = t.space4)
                        .testTag(ProtoAnchors.AMOUNT),
                )
                // 原型 flex gap --space-3 + 每段 .mt-3 = 24
                Spacer(Modifier.height(t.space6))
                FieldsCard(
                    state = state,
                    today = today,
                    modifier = Modifier
                        .padding(horizontal = t.space4)
                        .testTag(ProtoAnchors.FIELDS),
                )
                Spacer(Modifier.height(t.space6))
                ReceiptCard(
                    state = state,
                    modifier = Modifier
                        .padding(horizontal = t.space4)
                        .testTag(ProtoAnchors.RECEIPT),
                )
                Spacer(Modifier.height(t.space8))
            }

            ActionsBar(
                enabled = canSave,
                onSave = { onSave(draft) },
                modifier = Modifier.testTag(ProtoAnchors.ACTIONS),
            )
        }

        // 四个抽屉互斥（原型同一时刻只开一个）
        when (state.openSheet) {
            ProtoSheet.CATEGORY -> CategorySheet(
                visible = true,
                selectedName = state.categoryName,
                onPick = {
                    state.categoryName = it
                    state.openSheet = ProtoSheet.NONE
                },
                onDismiss = { state.openSheet = ProtoSheet.NONE },
            )

            ProtoSheet.ACCOUNT -> AccountSheet(
                visible = true,
                selectedName = state.accountName,
                onPick = {
                    state.accountName = it
                    state.openSheet = ProtoSheet.NONE
                },
                onDismiss = { state.openSheet = ProtoSheet.NONE },
            )

            ProtoSheet.TIME -> TimeSheet(
                visible = true,
                today = today,
                parts = state.time,
                onDateChange = { state.time = state.time.copy(date = it) },
                onTimeChange = { state.time = it },
                onDismiss = { state.openSheet = ProtoSheet.NONE },
            )

            ProtoSheet.RECEIPT -> ReceiptSheet(
                visible = true,
                receipt = state.receipt,
                onDismiss = { state.openSheet = ProtoSheet.NONE },
            )

            ProtoSheet.NONE -> Unit
        }
    }
}

// ─────────────────────────── 状态 ───────────────────────────

/** 一次只开一个抽屉（原型四个抽屉互斥）。 */
internal enum class ProtoSheet { NONE, CATEGORY, ACCOUNT, TIME, RECEIPT }

/** 保存载荷：proto 屏只负责采集，落库交给调用方。 */
internal data class ProtoRecordDraft(
    val kind: ProtoKind,
    val amount: Double,
    val categoryName: String,
    val accountName: String,
    val time: ProtoTimeParts,
    val note: String,
)

/** 表单状态（项目既有写法：`@Stable` 状态类 + `rememberXxxState()`，见 FinanceStateControl / ToastHostState）。 */
@Stable
internal class ProtoRecordState(
    initialKind: ProtoKind,
    initialAmount: String,
    initialCategory: String,
    initialAccount: String,
    initialTime: ProtoTimeParts,
    initialNote: String,
) {
    var kind by mutableStateOf(initialKind)
    var amountText by mutableStateOf(initialAmount)
    var categoryName by mutableStateOf(initialCategory)
    var accountName by mutableStateOf(initialAccount)
    var time by mutableStateOf(initialTime)
    var note by mutableStateOf(initialNote)
    var openSheet by mutableStateOf(ProtoSheet.NONE)

    /** 单据是自动记账带过来的只读事实（原型里不可编辑，只能查看 / 关闭）。 */
    val receipt: ProtoReceipt get() = ProtoRecordSample.receipt

    fun toDraft(): ProtoRecordDraft = ProtoRecordDraft(
        kind = kind,
        amount = ProtoRecordLogic.amountOf(amountText),
        categoryName = categoryName,
        accountName = accountName,
        time = time,
        note = note,
    )
}

/**
 * 原型首屏状态的默认值（金额 23.90 / 餐饮 / 支付宝 / 今天 18:32:21 / 长备注）。
 *
 * 日期取真实的今天，日期条的「今天 / 昨天 / 前天」才有意义；时分秒取原型静态样值。
 */
@Composable
internal fun rememberProtoRecordState(today: LocalDate = LocalDate.now()): ProtoRecordState =
    remember(today) {
        ProtoRecordState(
            initialKind = ProtoRecordSample.defaultKind,
            initialAmount = ProtoRecordSample.DEFAULT_AMOUNT,
            initialCategory = ProtoRecordSample.DEFAULT_CATEGORY,
            initialAccount = ProtoRecordSample.DEFAULT_ACCOUNT,
            initialTime = ProtoTimeParts(
                date = today.toString(),
                hour = ProtoRecordSample.DEFAULT_HOUR,
                minute = ProtoRecordSample.DEFAULT_MINUTE,
                second = ProtoRecordSample.DEFAULT_SECOND,
            ),
            initialNote = ProtoRecordSample.DEFAULT_NOTE,
        )
    }

// ─────────────────────────── ① 金额卡（原型 s14a-amount） ───────────────────────────

@Composable
private fun AmountCard(state: ProtoRecordState, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    // 原型 .card.glass（玻璃卡）+ .s14-card 的内边距 12 / 16
    GlassCard(padding = 0.dp, modifier = modifier) {
        Column(
            Modifier.padding(
                start = t.space4,
                end = t.space4,
                top = t.space3,
                bottom = t.space3,
            ),
        ) {
            KindSegmented(
                selected = state.kind,
                onSelect = { state.kind = it },
                modifier = Modifier.testTag(ProtoAnchors.KIND),
            )
            Spacer(Modifier.height(t.space2))
            // 原型 .s14a-amt：baseline 对齐 + 上下 --space-1
            Row(
                modifier = Modifier.padding(vertical = t.space1),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "¥",
                    // 原型 .s14a-cur：--text-xl(28) / 600 / --muted
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = t.textXl),
                    color = t.muted,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(t.space2))
                // 原型 input#s14a-amount（该输入的锚点在其外层 .pad 上，即上面的 s14a-amount）
                BasicTextField(
                    value = state.amountText,
                    onValueChange = { input ->
                        state.amountText = input.filter { it.isDigit() || it == '.' }
                    },
                    singleLine = true,
                    // 原型 .s14a-input：--text-3xl(56) / 700 / 等宽数字 / letter-spacing -.02em / --fg
                    textStyle = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = t.text3xl,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = AMOUNT_TRACKING,
                        fontFeatureSettings = "tnum",
                        color = t.fg,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(t.accent),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 类型段（原型 `.segmented.s14a-kind`：pill 容器 + 选中项 --surface 底 / --elev-1）。
 *
 * 之所以在 proto 层重建而不是直接用共享 [com.bsfeng.monitor.ui.components.SegmentedControl]：
 * 该组件按「选项字符串列表」渲染，无法给三个按钮分别挂 `s14a-kind-expense / -income / -transfer`
 * 三个锚点，而锚点是本轮「原型 ↔ 实现」对照的硬需求。视觉配方与共享版逐值同源。
 */
@Composable
private fun KindSegmented(
    selected: ProtoKind,
    onSelect: (ProtoKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    // 原型 .segmented：容器内边距 4、项间距 4、1px --border-soft 描边、底 color-mix(fg 5%)
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin + SEG_PAD * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(SEG_PAD),
        horizontalArrangement = Arrangement.spacedBy(SEG_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtoKind.entries.forEach { kind ->
            val isOn = kind == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .tokenShadow(if (isOn) 1 else 0, shape)
                    .clip(shape)
                    .background(if (isOn) t.surface else Color.Transparent)
                    .testTag(ProtoAnchors.kind(kind))
                    .clickable {
                        // 分段切换按「Tab 切换」档位给触觉（设计规范 §8.2）
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSelect(kind)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    kind.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isOn) t.fg else t.muted,
                )
            }
        }
    }
}

// ─────────────────────────── ② 字段卡（原型 s14a-fields） ───────────────────────────

@Composable
private fun FieldsCard(state: ProtoRecordState, today: LocalDate, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    // 原型 .card + .s14-card：内边距 12 / 16（分隔线在行上，故内边距给在行外面）
    AppCard(padding = 0.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
            FieldRow(
                label = "分类",
                value = state.categoryName,
                tag = ProtoAnchors.CAT_ROW,
                onClick = { state.openSheet = ProtoSheet.CATEGORY },
            )
            FieldDivider()
            FieldRow(
                label = "账户",
                value = state.accountName,
                tag = ProtoAnchors.ACCT_ROW,
                onClick = { state.openSheet = ProtoSheet.ACCOUNT },
            )
            FieldDivider()
            FieldRow(
                label = "时间",
                value = ProtoRecordLogic.timeText(state.time, today),
                tag = ProtoAnchors.TIME_ROW,
                onClick = { state.openSheet = ProtoSheet.TIME },
            )
            FieldDivider()
            NoteRow(state = state, modifier = Modifier.testTag(ProtoAnchors.NOTE_ROW))
        }
    }
}

/**
 * 字段行（原型 `.s14-toggle`：标签 + 值 + ▸，min-height 44px）。
 *
 * 标签列固定 44px（原型 `.screen.s14a .s14-label{flex:0 0 44px}`），值列吃掉剩余宽度并省略号收尾。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = t.tapMin)
            .testTag(tag)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        horizontalArrangement = Arrangement.spacedBy(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(t.tapMin),
        )
        Text(
            value,
            // 原型 .s14-value：与标签同字号 --text-sm(14)，层级靠 500 字重与 --fg 拉开
            style = MaterialTheme.typography.bodySmall,
            color = t.fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            // 原型 .chev：18px + opacity .5（就近取图标 token 20dp）
            tint = t.fg.copy(alpha = 0.5f),
            modifier = Modifier.size(MonitorIconSize.md),
        )
    }
}

/** 行分隔线（原型 `.s14-toggle:not(:first-child){border-top:1px solid var(--border-soft)}`）。 */
@Composable
private fun FieldDivider() {
    val t = LocalDesignTokens.current
    HorizontalDivider(thickness = DividerDefaults.Thickness, color = t.borderSoft)
}

/** 备注行：同构但没有箭头 —— 直接就地输入（原型 `.s14-toggle` 里的 `.s14-note`）。 */
@Composable
private fun NoteRow(state: ProtoRecordState, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = t.tapMin),
        horizontalArrangement = Arrangement.spacedBy(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "备注",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(t.tapMin),
        )
        BasicTextField(
            value = state.note,
            onValueChange = { state.note = it },
            singleLine = true,
            // 原型 .s14-note：--text-sm(14)/500/--fg，无边框无底色
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                color = t.fg,
            ),
            cursorBrush = SolidColor(t.accent),
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────── ③ 单据卡（原型 s14a-receipt） ───────────────────────────

@Composable
private fun ReceiptCard(state: ProtoRecordState, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(padding = 0.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
            FieldRow(
                label = "单据",
                value = state.receipt.summary,
                tag = ProtoAnchors.RECEIPT_ROW,
                onClick = { state.openSheet = ProtoSheet.RECEIPT },
            )
        }
    }
}

// ─────────────────────────── ④ 吸底保存条（原型 s14a-actions） ───────────────────────────

@Composable
private fun ActionsBar(enabled: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 原型 .s14a-actions：linear-gradient(to top, --surface 62%, transparent)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.38f to t.surface))
            // 原型 .s14a-actions：padding 12 / 16 / 20
            .padding(
                start = t.space4,
                end = t.space4,
                top = t.space3,
                bottom = t.space5,
            ),
    ) {
        PrimaryButton(
            text = "保存",
            enabled = enabled,
            tag = ProtoAnchors.SAVE,
            onClick = onSave,
        )
    }
}

/** 主按钮（原型 `.btn.btn-block`：--accent 底、--radius-md 圆角、min-height 48、17px/600）。 */
@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.space12)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (enabled) t.accent else t.accent.copy(alpha = DISABLED_ALPHA))
            .testTag(tag)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 原型 .segmented 的容器内边距 / 项间距（4px，非 token 值）。 */
private val SEG_PAD = 4.dp

/** 金额字距（原型 .s14a-input 的 letter-spacing:-.02em）。 */
private val AMOUNT_TRACKING = (-0.02).em

/** 原型 .btn[disabled]{opacity:.45} */
private const val DISABLED_ALPHA = 0.45f
