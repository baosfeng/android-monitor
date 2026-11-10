package com.bsfeng.monitor.totp.ui

// 参数编辑区（新增 + 详情共用）：类型（TOTP / HOTP）+ 算法 + 位数 + 周期 + HOTP 计数器。
//
// 为什么单独一层：手动输入页把它折叠在「高级参数」里、详情页把它摊平在参数卡里，
// 两处的可选项与校验口语必须逐字一致——各写一份就会出现「手动能填 7 位、详情只能选 6/8」。
//
// 布局判据（契约 §3.2）：分段控件是 Row + weight，天然不换行（640dp 宽屏同样单行）；
// 触达区由 SegmentedControl 内部恒为 t.tapMin（44dp），自定义输入框复用 TotpTextField（≥ tapMin）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.bsfeng.monitor.domain.totp.TotpParams
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 算法分段选项（手动输入不引导 MD5；从导入来的 MD5 账户在详情页按原值补一段显示）。 */
internal val TOTP_ALGORITHM_OPTIONS = listOf(
    TotpParams.ALGORITHM_SHA1,
    TotpParams.ALGORITHM_SHA256,
    TotpParams.ALGORITHM_SHA512,
)

/** 位数常用档；不在档内的值（4 / 5 / 7 / 9 / 10）落到「自定义」。 */
internal val TOTP_DIGITS_PRESETS = listOf(6, 8)

/** 周期常用档（秒）；其余值（1–86400 内）落到「自定义」。 */
internal val TOTP_PERIOD_PRESETS = listOf(15, 30, 60, 300)

/** 类型分段：HOTP 不按时间换码，改由用户点「下一码」推进计数器。 */
internal val TOTP_TYPE_OPTIONS = listOf(TotpParams.TYPE_TOTP, TotpParams.TYPE_HOTP)

/** 「自定义」段标签：分段控件最后一段，选中后展开数字输入。 */
internal const val TOTP_CUSTOM_LABEL = "自定义"

/** 位数落在常用档时的段下标；否则指向「自定义」段。 */
internal fun digitsSegmentIndex(digits: Int): Int {
    val index = TOTP_DIGITS_PRESETS.indexOf(digits)
    return if (index >= 0) index else TOTP_DIGITS_PRESETS.size
}

/** 周期落在常用档时的段下标；否则指向「自定义」段。 */
internal fun periodSegmentIndex(periodSec: Int): Int {
    val index = TOTP_PERIOD_PRESETS.indexOf(periodSec)
    return if (index >= 0) index else TOTP_PERIOD_PRESETS.size
}

/** 账户是不是 HOTP（只有 HOTP 才显示计数器与「下一码」）。 */
internal fun isHotp(params: TotpParams): Boolean =
    params.type.trim().uppercase() == TotpParams.TYPE_HOTP

/** Steam Guard 变体：5 位字母码、30 秒周期、不分段。 */
internal fun isSteam(variant: String): Boolean =
    variant.trim().uppercase() == TotpAccountVariantSteam

/** 与 domain 的 TotpAccount.VARIANT_STEAM 同值；这里写成常量是为了让 UI 不依赖账户实例。 */
internal const val TotpAccountVariantSteam = "STEAM"

/**
 * 参数编辑区。
 *
 * @param params 当前参数（受控）
 * @param counter HOTP 计数器；仅在 [params] 为 HOTP 时渲染
 * @param algorithmOptions 详情页会把「导入来的 MD5」临时追加为一段，避免选中态落空
 * @param digitsTag / periodTag / counterTag 调用点传入的稳定锚点（参数透传，静态对账不判自增）
 */
@Composable
fun TotpParamEditor(
    params: TotpParams,
    counter: Long,
    onParamsChange: (TotpParams) -> Unit,
    onCounterChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    algorithmOptions: List<String> = TOTP_ALGORITHM_OPTIONS,
    digitsTag: String? = null,
    periodTag: String? = null,
    typeTag: String? = null,
    counterTag: String? = null,
) {
    val t = LocalDesignTokens.current
    val hotp = isHotp(params)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space3)) {
        ParamSegment(
            label = "类型",
            options = TOTP_TYPE_OPTIONS,
            selectedIndex = TOTP_TYPE_OPTIONS.indexOf(params.type.trim().uppercase()).coerceAtLeast(0),
            optionTags = typeTag?.let { listOf(it + "-totp", it + "-hotp") },
            onSelect = { onParamsChange(params.copy(type = TOTP_TYPE_OPTIONS[it])) },
        )
        ParamSegment(
            label = "算法",
            options = algorithmOptions,
            selectedIndex = algorithmOptions.indexOf(params.algorithm).coerceAtLeast(0),
            onSelect = { onParamsChange(params.copy(algorithm = algorithmOptions[it])) },
        )
        PresetWithCustom(
            label = "位数",
            presets = TOTP_DIGITS_PRESETS,
            selectedIndex = digitsSegmentIndex(params.digits),
            format = { "$it 位" },
            customValue = params.digits,
            customLabel = "位数（1–10）",
            customRange = TotpParams.MIN_DIGITS..TotpParams.MAX_DIGITS,
            hint = "位数需在 1–10 之间",
            presetTag = digitsTag,
            onSelectPreset = { onParamsChange(params.copy(digits = TOTP_DIGITS_PRESETS[it])) },
            onCustomValue = { onParamsChange(params.copy(digits = it)) },
        )
        PresetWithCustom(
            label = "周期（秒）",
            presets = TOTP_PERIOD_PRESETS,
            selectedIndex = periodSegmentIndex(params.periodSec),
            format = { "$it 秒" },
            customValue = params.periodSec,
            customLabel = "周期（1–86400 秒）",
            customRange = TotpParams.MIN_PERIOD_SEC..TotpParams.MAX_PERIOD_SEC,
            hint = "周期需在 1–86400 秒之间",
            presetTag = periodTag,
            onSelectPreset = { onParamsChange(params.copy(periodSec = TOTP_PERIOD_PRESETS[it])) },
            onCustomValue = { onParamsChange(params.copy(periodSec = it)) },
        )
        if (hotp) {
            LongEntry(
                label = "计数器（HOTP）",
                value = counter,
                onValueChange = onCounterChange,
                tag = counterTag,
                hint = "计数器从 0 开始，每点一次「下一码」加 1；填错会一直算出错的码。",
            )
        } else {
            TotpFine("默认 SHA1 / 6 位 / 30 秒，与绝大多数网站一致；改动对当前账户立刻生效。")
        }
    }
}

/** 一段「常用档 + 自定义」：选中常用档用分段，选「自定义」时展开数字输入。 */
@Composable
private fun PresetWithCustom(
    label: String,
    presets: List<Int>,
    selectedIndex: Int,
    format: (Int) -> String,
    customValue: Int,
    customLabel: String,
    customRange: IntRange,
    hint: String,
    presetTag: String?,
    onSelectPreset: (Int) -> Unit,
    onCustomValue: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space1))
        SegmentedControl(
            options = presets.map(format) + TOTP_CUSTOM_LABEL,
            selectedIndex = selectedIndex,
            onSelect = { index ->
                if (index < presets.size) onSelectPreset(index)
            },
            optionTags = presetTag?.let { tag -> presets.indices.map { tag + "-" + (it + 1) } },
        )
        if (selectedIndex >= presets.size) {
            Spacer(Modifier.height(t.space2))
            NumberEntry(
                label = customLabel,
                value = customValue,
                range = customRange,
                hint = hint,
                onValueChange = onCustomValue,
            )
        }
    }
}

/** 单行数字输入（位数 / 周期）：越界只标红不改值，保存时由校验统一拦。 */
@Composable
private fun NumberEntry(
    label: String,
    value: Int,
    range: IntRange,
    hint: String,
    onValueChange: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    // 外部把值改成预设档时（分段被点）同步输入框，避免草稿与真实值长期分叉
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in range
    TotpTextField(
        value = text,
        onValueChange = { raw ->
            val digitsOnly = raw.filter { it.isDigit() }.take(6)
            text = digitsOnly
            digitsOnly.toIntOrNull()?.let(onValueChange)
        },
        label = label,
        placeholder = range.first.toString(),
        invalid = invalid,
        keyboardType = KeyboardType.Number,
        mono = true,
    )
    if (invalid) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = t.dangerText,
            modifier = Modifier.padding(top = t.space1),
        )
    }
}

/** 计数器单行输入（Long，只收数字）。 */
@Composable
private fun LongEntry(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    tag: String?,
    hint: String,
) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toLongOrNull() != value) text = value.toString()
    }
    TotpTextField(
        value = text,
        onValueChange = { raw ->
            val digitsOnly = raw.filter { it.isDigit() }.take(18)
            text = digitsOnly
            digitsOnly.toLongOrNull()?.let(onValueChange)
        },
        label = label,
        placeholder = "0",
        mono = true,
        keyboardType = KeyboardType.Number,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
    TotpFine(hint)
}

/** 单个分段字段：标签 + 分段控件。 */
@Composable
private fun ParamSegment(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    optionTags: List<String>? = null,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space1))
        SegmentedControl(options = options, selectedIndex = selectedIndex, onSelect = onSelect, optionTags = optionTags)
    }
}
