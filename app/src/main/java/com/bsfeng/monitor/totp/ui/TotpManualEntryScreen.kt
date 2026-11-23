package com.bsfeng.monitor.totp.ui

// s25 手动输入：平台名 / 账户 / Base32 密钥 + 高级参数折叠（算法 / 位数 / 周期）+ 实时预演。
// 表单值、错误文案都由上层持有；本屏只负责渲染与把编辑上抛。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.Base32
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpAlgorithm
import com.bsfeng.monitor.domain.totp.TotpCountdown
import com.bsfeng.monitor.domain.totp.TotpParams
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 手动输入表单的可编辑字段（不含 id / 排序，落库字段由数据层补）。
 *
 * [counter] 只在 params 为 HOTP 时有意义：填错会一直算出错的码，所以它跟着表单一起可改。
 */
data class TotpManualForm(
    val issuer: String = "",
    val accountName: String = "",
    val secretBase32: String = "",
    val params: TotpParams = TotpParams(),
    val counter: Long = 0L,
)

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
    TotpScaffold(title = "手动输入", screenTag = "screen-s25", contentTag = "s25-content", onBack = onBack, modifier = modifier) {
        Text(
            "网站「两步验证」页给出的密钥通常是一串 Base32 字符（A–Z 与 2–7），可带空格。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.padding(top = t.space2),
        )
        // 原型 .s25-form{display:grid;gap:12px;padding:12px 0 16px}：列内 gap 是 space3 不是 space2，
        // 且上下各带 12 / 16 内边距。按 space2 时整块矮 33，预演 / 高级 / 两颗按钮一起上移 36–47
        // 原型 .s25-form{display:grid;gap:12px;padding:12px 0 16px}：列内 gap 是 space3 不是 space2，
        // 上下各带 12 / 16 内边距。用 space2 时整块矮 33，预演 / 高级 / 两颗按钮一起上移 36–47。
        // 注意 .s25-preview / .s25-adv 自己还带 margin-top 12 / 8，与 grid gap 叠加 —— 原型就是叠加的。
        // 原型 .s25-form{display:grid;gap:12px;padding:12px 0 16px}：gap 是 space3（早先用 space2 → 整块矮 33）。
        // 上下 padding 不补：原型 .content 的 padding 已由页壳给出，再补 28 会让整块反而高 31
        Column(
            Modifier.testTag("s25-form"),
            verticalArrangement = Arrangement.spacedBy(t.space3),
        ) {
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
            // 原型 .s25-hint 是密钥字段组内的说明行（组内 gap = space-1），TotpFine 的上下内边距不适用。
            Text(
                "真实密钥请从网站的「无法扫码」入口复制；不要贴到聊天记录或截图里。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.fillMaxWidth().padding(top = t.space1),
            )

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

            // 原型 .s25-preview{self padding:12px}（ManualPreview 内部已含）；外边距由上面的 grid gap 承担
            Column(Modifier.testTag("s25-preview")) {
                ManualPreview(form = form, nowMillis = nowMillis)
            }

            AdvancedParams(
                open = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
                form = form,
                onFormChange = onFormChange,
            )

            Spacer(Modifier.height(t.space2))
            AppButton(text = "添加账户", onClick = onSubmit, block = true, modifier = Modifier.testTag("s25-submit"))
            AppButton(
                text = "改扫码添加",
                onClick = onGoScan,
                variant = AppButtonVariant.Ghost,
                block = true,
                modifier = Modifier.testTag("s25-goto-scan"),
            )
        }
        TotpFine("密钥加密后只存本机，不写日志、不上传、不进导出包；添加后可在账户详情里改名、换平台或删除。")
    }
}

/**
 * 实时预演（原型 .s25-preview）：当前参数下的码 + 右侧倒计时。
 *
 * HOTP 不随时间换码，右侧改成「计数器 N」而不是环形倒计时——否则用户会以为
 * 它在按周期自己走，实际永远不会变。
 */
@Composable
private fun ManualPreview(form: TotpManualForm, nowMillis: Long) {
    val t = LocalDesignTokens.current
    val hotp = isHotp(form.params)
    val countdown = remember(nowMillis, form.params.periodSec) {
        TotpCountdown.of(nowMillis, form.params.periodSec)
    }
    val previewAccount = remember(form) {
        TotpAccount(
            issuer = form.issuer,
            accountName = form.accountName,
            secretBase32 = form.secretBase32,
            params = form.params,
            counter = form.counter,
        )
    }
    val code = remember(nowMillis, previewAccount) {
        if (hotp) {
            val secret = Base32.decodeOrNull(form.secretBase32) ?: ByteArray(0)
            TotpAlgorithm.hotp(secret, form.counter, form.params.digits, form.params.algorithm)
        } else {
            deriveCode(form.secretBase32, nowMillis / 1_000L, form.params)
        }
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
            Text(
                if (hotp) "按当前参数预演（当前计数器）" else "按当前参数预演（假数据）",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            TotpCodeText(code = code, digits = form.params.digits, placeholder = "— — —")
        }
        if (hotp) {
            Text(
                "计数器 ${form.counter}",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        } else {
            TotpCountdownRing(countdown)
            Text(
                "${countdown.remainingSec}s",
                style = MaterialTheme.typography.bodySmall,
                color = if (isCountdownWarning(countdown)) t.warnText else t.muted,
                modifier = Modifier.padding(start = t.space2),
            )
        }
    }
}

/**
 * 高级参数折叠（原型 .s25-adv / details）：类型 / 算法 / 位数 / 周期。
 *
 * 几何按原型 .s25-adv 的口径对齐：margin-top(8) 在锚点盒**外**（用前置 Spacer 占位），
 * 锚点盒 = border-top(1) + padding-top(8) + summary(44，tap-min) = 53dp。
 * 折叠头保持可点（点得开「高级参数里有什么」），展开态由 stateDescription 播报，
 * 不再额外画「展开/收起」两个字——原型 summary 里只有标题 + 旋转的箭头。
 */
@Composable
private fun AdvancedParams(
    open: Boolean,
    onToggle: () -> Unit,
    form: TotpManualForm,
    onFormChange: (TotpManualForm) -> Unit,
) {
    val t = LocalDesignTokens.current
    // 原型 .s25-adv 的 margin-top 是 --space-2(8)：页壳列间距已给 8，差的那份由这里补齐。
    // 剩余 y 差（约 6dp）来自 preview 卡高度（实现 53.7 vs 原型 75.4，见交接文档），不是这里的间距。
    Spacer(Modifier.height(t.space2))
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("s25-adv"),
    ) {
        TotpHairline()
        Column(Modifier.padding(top = t.space2)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = t.tapMin)
                    .clickable { onToggle() }
                    .semantics { stateDescription = if (open) "已展开" else "已收起" },
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
                    contentDescription = null,
                    tint = t.muted,
                    modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f),
                )
            }
            if (open) {
                Spacer(Modifier.height(t.space2))
                TotpParamEditor(
                    params = form.params,
                    counter = form.counter,
                    onParamsChange = { onFormChange(form.copy(params = it)) },
                    onCounterChange = { onFormChange(form.copy(counter = it)) },
                    digitsTag = "s25-digits",
                    periodTag = "s25-period",
                    typeTag = "s25-type",
                    counterTag = "s25-counter",
                )
                TotpFine("默认 SHA1 / 6 位 / 30 秒，与绝大多数网站一致；改错会一直算出错的验证码，但不会损坏密钥。")
            }
        }
    }
}

