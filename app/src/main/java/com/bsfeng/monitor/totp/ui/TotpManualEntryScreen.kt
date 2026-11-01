package com.bsfeng.monitor.totp.ui

// s25 手动输入：平台名 / 账户 / Base32 密钥 + 高级参数折叠（算法 / 位数 / 周期）+ 实时预演。
// 表单值、错误文案都由上层持有；本屏只负责渲染与把编辑上抛。

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.TotpCountdown
import com.bsfeng.monitor.domain.totp.TotpParams
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 手动输入表单的可编辑字段（不含 id / 排序，落库字段由数据层补）。 */
data class TotpManualForm(
    val issuer: String = "",
    val accountName: String = "",
    val secretBase32: String = "",
    val params: TotpParams = TotpParams(),
)

/** 算法分段选项（顺序即 domain 支持顺序）。 */
internal val TOTP_ALGORITHM_OPTIONS = listOf(TotpParams.ALGORITHM_SHA1, TotpParams.ALGORITHM_SHA256, TotpParams.ALGORITHM_SHA512)
internal val TOTP_DIGITS_OPTIONS = listOf(6, 8)
internal val TOTP_PERIOD_OPTIONS = listOf(30, 60)

/**
 * 手动输入（原型 s25）。
 *
 * @param error 校验错误（全角中文）；null 时不渲染错误行
 * @param onSubmit 提交回调：**校验通过后**才由调用方触发，本屏不自行落库
 */
@Composable
fun TotpManualEntryScreen(
    form: TotpManualForm,
    onFormChange: (TotpManualForm) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    nowMillis: Long = rememberTotpNow(),
    onSubmit: () -> Unit = {},
    onGoScan: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    TotpScaffold(title = "手动输入", testTag = "screen-s25", onBack = onBack, modifier = modifier) {
        Text(
            "网站「两步验证」页给出的密钥通常是一串 Base32 字符（A–Z 与 2–7），可带空格。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.padding(top = t.space2),
        )
        TotpTextField(
            value = form.issuer,
            onValueChange = { onFormChange(form.copy(issuer = it)) },
            label = "平台名",
            placeholder = "例如 GitHub / 腾讯云",
            invalid = error != null && form.issuer.isBlank(),
        )
        TotpTextField(
            value = form.accountName,
            onValueChange = { onFormChange(form.copy(accountName = it)) },
            label = "账户（可选）",
            placeholder = "例如 alice@acme.com",
            keyboardType = KeyboardType.Email,
        )
        TotpTextField(
            value = form.secretBase32,
            onValueChange = { onFormChange(form.copy(secretBase32 = it.uppercase())) },
            label = "密钥（Base32）",
            placeholder = "例如 JBSWY3DPEHPK3PXP",
            mono = true,
            invalid = error != null,
        )
        TotpFine("真实密钥请从网站的「无法扫码」入口复制；不要贴到聊天记录或截图里。")

        if (error != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = t.space1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = t.dangerText,
                    modifier = Modifier.size(16.dp),
                )
                Text(error, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
            }
        }

        ManualPreview(form = form, nowMillis = nowMillis)

        AdvancedParams(
            open = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
            params = form.params,
            onParamsChange = { onFormChange(form.copy(params = it)) },
        )

        Spacer(Modifier.height(t.space2))
        AppButton(text = "添加账户", onClick = onSubmit, block = true)
        AppButton(text = "改扫码添加", onClick = onGoScan, variant = AppButtonVariant.Ghost, block = true)
        TotpFine("密钥加密后只存本机，不写日志、不上传、不进导出包；添加后可在账户详情里改名、换平台或删除。")
    }
}

/** 实时预演（原型 .s25-preview）：当前参数下的假数据码 + 环形倒计时。 */
@Composable
private fun ManualPreview(form: TotpManualForm, nowMillis: Long) {
    val t = LocalDesignTokens.current
    val countdown = remember(nowMillis, form.params.periodSec) {
        TotpCountdown.of(nowMillis, form.params.periodSec)
    }
    val code = remember(nowMillis, form.params, form.secretBase32) {
        deriveCode(form.secretBase32, nowMillis / 1_000L, form.params)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.fg.copy(alpha = t.fg.alpha * 0.05f))
            .padding(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("按当前参数预演（假数据）", style = MaterialTheme.typography.bodySmall, color = t.muted)
            TotpCodeText(code = code, digits = form.params.digits, placeholder = "— — —")
        }
        TotpCountdownRing(countdown)
        Text(
            "${countdown.remainingSec}s",
            style = MaterialTheme.typography.bodySmall,
            color = if (isCountdownWarning(countdown)) t.warnText else t.muted,
            modifier = Modifier.padding(start = t.space2),
        )
    }
}

/** 高级参数折叠（原型 .s25-adv / details）：算法 / 位数 / 周期三段。 */
@Composable
private fun AdvancedParams(
    open: Boolean,
    onToggle: () -> Unit,
    params: TotpParams,
    onParamsChange: (TotpParams) -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = t.space2)
            .border(width = 0.dp, color = t.borderSoft)
            .padding(top = t.space2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = t.tapMin)
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "高级参数",
                style = MaterialTheme.typography.bodyLarge,
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (open) "收起高级参数" else "展开高级参数",
                tint = t.muted,
                modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f),
            )
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
                ParamSegment(
                    label = "算法",
                    options = TOTP_ALGORITHM_OPTIONS,
                    selectedIndex = TOTP_ALGORITHM_OPTIONS.indexOf(params.algorithm).coerceAtLeast(0),
                    onSelect = { onParamsChange(params.copy(algorithm = TOTP_ALGORITHM_OPTIONS[it])) },
                )
                ParamSegment(
                    label = "位数",
                    options = TOTP_DIGITS_OPTIONS.map { "$it 位" },
                    selectedIndex = TOTP_DIGITS_OPTIONS.indexOf(params.digits).coerceAtLeast(0),
                    onSelect = { onParamsChange(params.copy(digits = TOTP_DIGITS_OPTIONS[it])) },
                )
                ParamSegment(
                    label = "周期",
                    options = TOTP_PERIOD_OPTIONS.map { "$it 秒" },
                    selectedIndex = TOTP_PERIOD_OPTIONS.indexOf(params.periodSec).coerceAtLeast(0),
                    onSelect = { onParamsChange(params.copy(periodSec = TOTP_PERIOD_OPTIONS[it])) },
                )
                TotpFine("默认 SHA1 / 6 位 / 30 秒，与绝大多数网站一致；改错会一直算出错的验证码，但不会损坏密钥。")
            }
        }
    }
}

@Composable
private fun ParamSegment(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space1))
        SegmentedControl(options = options, selectedIndex = selectedIndex, onSelect = onSelect)
    }
}
