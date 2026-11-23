package com.bsfeng.monitor.totp.ui

// s26 账户详情：改名 / 换平台 / 排序 / 删除（二次确认）+ 密钥掩码与 15 秒自动收回。
// account == null 走加载态；删除确认只上抛 onDelete，不在这里弹 Toast 或返回。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpCountdown
import com.bsfeng.monitor.domain.totp.TotpParams
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlinx.coroutines.delay

/**
 * 详情页的算法选项：导入来的 MD5 账户把 MD5 补成一段，避免选中态落空（否则用户
 * 一进详情就看到「SHA1 选中」，一保存就把 MD5 改成了 SHA1，码立刻全错）。
 */
internal fun algorithmOptionsFor(current: String): List<String> {
    val normalized = current.trim().uppercase()
    return if (normalized.isBlank() || normalized in TOTP_ALGORITHM_OPTIONS) {
        TOTP_ALGORITHM_OPTIONS
    } else {
        TOTP_ALGORITHM_OPTIONS + normalized
    }
}

/** 密钥明文停留时长：显示 15 秒后自动收回（原型口径）。 */
private const val SECRET_REVEAL_MS = 15_000L

/**
 * 账户详情（原型 s26）。
 *
 * @param account null = 尚未读到（加载态），读到后由上层换成实体
 * @param positionLabel 「第 4 位 / 共 5」这类排序说明，由上层按列表算好后传入
 */
@Composable
fun TotpDetailScreen(
    account: TotpAccount?,
    issuerDraft: String,
    accountDraft: String,
    onIssuerChange: (String) -> Unit,
    onAccountChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberTotpNow(),
    positionLabel: String = "",
    sourceLabel: String = "—",
    lastUsedLabel: String = "—",
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onSave: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** 参数草稿（算法 / 位数 / 周期 / 类型）；null 表示尚未铺开，取 [account] 的原值。 */
    paramsDraft: TotpParams? = null,
    /** HOTP 计数器草稿。 */
    counterDraft: Long? = null,
    onParamsChange: (TotpParams) -> Unit = {},
    onCounterChange: (Long) -> Unit = {},
    /** HOTP 账户：顶部码区给「下一码」按钮（推进计数器并刷新）。 */
    onAdvanceHotp: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    var revealed by rememberSaveable(account?.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val params = paramsDraft ?: account?.params ?: TotpParams()
    val counter = counterDraft ?: account?.counter ?: 0L

    LaunchedEffect(revealed) {
        if (revealed) {
            delay(SECRET_REVEAL_MS)
            revealed = false
        }
    }

    TotpScaffold(title = "账户详情", screenTag = "screen-s26", contentTag = "s26-content", onBack = onBack, modifier = modifier) {
        if (account == null) {
            AppCard { StateSkeleton(rows = 4) }
            return@TotpScaffold
        }
        // 展示码用**草稿参数**：改了位数/周期要当场看到码变化，保存只是把它写回库里
        val liveAccount = remember(account, params, counter) { account.copy(params = params, counter = counter) }
        val hotp = isHotp(params)
        val steam = isSteam(account.variant)
        val countdown = remember(nowMillis, params.periodSec) {
            TotpCountdown.of(nowMillis, params.periodSec)
        }
        val code = remember(nowMillis, liveAccount, params) {
            deriveAccountCode(liveAccount, nowMillis / 1_000L)
        }

        // 原型 s26-body 包住英雄行 + 码块 + 字段区 + 参数卡 + 排序卡 + 保存 + 删除 + 说明
        Column(Modifier.testTag("s26-body")) {
        // 原型 .s26-hero{padding:12px 0}：缺了这段上下内边距会矮 24，下面所有块整体上移
        Row(
            Modifier.testTag("s26-hero").padding(vertical = t.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(t.radiusMd)).background(t.fgFade(8)),
                contentAlignment = Alignment.Center,
            ) {
                Text(iconInitial(account.issuer), style = monitorMonoMeta.copy(fontSize = t.textBase), color = t.fg2)
            }
            Spacer(Modifier.width(t.space3))
            Column(Modifier.weight(1f)) {
                Text(account.issuer, style = MaterialTheme.typography.titleSmall, color = t.fg)
                Text(
                    listOfNotNull(
                        account.accountName.ifBlank { null },
                        if (positionLabel.isBlank()) null else positionLabel,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }

        // 原型 s26-code 包住「当前验证码 + 码 + 倒计时」整块
        Column(Modifier.testTag("s26-code")) {
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
                    if (steam) "当前 Steam 字母码" else if (hotp) "当前 HOTP 码" else "当前验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                TotpCodeText(
                    code = code,
                    digits = params.digits,
                    wide = !steam && params.digits == 8,
                    placeholder = "— — —",
                )
            }
            // HOTP 不随时间换码：不画倒计时环，改画计数器 + 「下一码」
            if (hotp) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = t.space2)) {
                    Text(
                        "计数器 ${counter}",
                        style = monitorMonoMeta.copy(fontSize = t.textXs),
                        color = t.muted,
                    )
                    Spacer(Modifier.height(t.space1))
                    AppButton(
                        text = "下一码",
                        onClick = { onAdvanceHotp?.invoke() },
                        variant = AppButtonVariant.Ghost,
                        enabled = onAdvanceHotp != null,
                    )
                }
            } else {
                TotpCountdownRing(countdown)
                Text(
                    "${countdown.remainingSec}s",
                    style = monitorMonoMeta.copy(fontSize = t.textXs),
                    color = if (isCountdownWarning(countdown)) t.warnText else t.muted,
                    modifier = Modifier.padding(start = t.space2),
                )
            }
        }
        }

        // 原型 s26-fields 包住三个字段（平台名 / 账户 / 掩码密钥）与掩码说明
        Column(Modifier.testTag("s26-fields")) {
            TotpTextField(
                value = issuerDraft,
                onValueChange = onIssuerChange,
                label = "平台名",
                placeholder = account.issuer,
            )
            Spacer(Modifier.height(t.space2))
            TotpTextField(
                value = accountDraft,
                onValueChange = onAccountChange,
                label = "账户",
                placeholder = "例如 alice@acme.com",
            )
            Spacer(Modifier.height(t.space2))
            TotpTextField(
                value = if (revealed) account.secretBase32 else "•••• •••• •••• ••••",
                onValueChange = {},
                label = "密钥（Base32）",
                placeholder = "",
                readOnly = true,
                mono = true,
                trailing = {
                    AppButton(
                        text = if (revealed) "隐藏" else "显示",
                        onClick = { revealed = !revealed },
                        variant = AppButtonVariant.Ghost,
                        modifier = Modifier.testTag("s26-reveal"),
                    )
                },
            )
            TotpFine("掩码显示，默认不落屏；显示 15 秒后自动收回。")
        }

        AppCard(modifier = Modifier.testTag("s26-params")) {
            TotpParamRow("形态", codeKindLabel(account))
            TotpHairline()
            TotpParamRow("来源", sourceLabel)
            TotpHairline()
            TotpParamRow("最近使用", lastUsedLabel)
        }

        // 参数可编辑：二维码给错参数（周期 15 秒、7 位码、HOTP 计数器对不上）时，
        // 用户只能在这里修正；保存走同一套范围校验，改错只会算错码，不会损坏密钥。
        AppCard {
            Text("验证码参数", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space2))
            TotpParamEditor(
                params = params,
                counter = counter,
                onParamsChange = onParamsChange,
                onCounterChange = onCounterChange,
                algorithmOptions = algorithmOptionsFor(params.algorithm),
                digitsTag = "s26-digits",
                periodTag = "s26-period",
                typeTag = "s26-type",
                counterTag = "s26-counter",
            )
        }
        TotpFine("参数由二维码决定，事后可在这里修正；改错会一直算出错的码，但不会损坏密钥。")

        AppCard(modifier = Modifier.testTag("s26-order"), padding = t.space2) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = t.space2, vertical = t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("列表排序", style = MaterialTheme.typography.titleSmall, color = t.fg)
                    Text(
                        "靠近顶部 = 更常用，概览卡片也按此顺序取前 3 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                }
                Text(positionLabel, style = monitorMonoMeta.copy(fontSize = t.textXs), color = t.fg2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                AppButton(
                    "上移一位",
                    onMoveUp,
                    variant = AppButtonVariant.Ghost,
                    modifier = Modifier.weight(1f).testTag("s26-move-up"),
                )
                AppButton(
                    "下移一位",
                    onMoveDown,
                    variant = AppButtonVariant.Ghost,
                    modifier = Modifier.weight(1f).testTag("s26-move-down"),
                )
            }
        }

        Spacer(Modifier.height(t.space1))
        // 原型 .s26-save{padding:12px 0 16px}：缺了这段会矮 28，后面删除块上移
        Box(
            Modifier
                .fillMaxWidth()
                .testTag("s26-save")
                .padding(top = t.space3, bottom = t.space4),
        ) {
            AppButton(
                text = "保存修改",
                onClick = onSave,
                block = true,
                tone = HapticTone.SUCCESS,
                modifier = Modifier.testTag("s26-save-btn"),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s26-danger")
                .padding(top = t.space3)
                .border(width = 0.dp, color = t.borderSoft)
                .padding(top = t.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(t.radiusSm))
                        .background(t.danger.copy(alpha = t.danger.alpha * 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = t.dangerText, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(t.space3))
                Column(Modifier.weight(1f)) {
                    Text("删除该账户", style = MaterialTheme.typography.titleSmall, color = t.dangerText)
                    Text(
                        "删除后本机不再计算此验证码，网站上的两步验证需另行关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                }
            }
            Spacer(Modifier.height(t.space2))
            AppButton(
                text = "删除账户",
                onClick = { confirmDelete = true },
                variant = AppButtonVariant.Ghost,
                block = true,
                tone = HapticTone.REJECT,
                modifier = Modifier.testTag("s26-del"),
            )
        }
        TotpFine("改名与换平台只影响本机展示，不影响网站上已绑定的两步验证。")
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "删除「${account?.issuer ?: ""}」账户？",
        message = "删除后无法从本机恢复，也不会同步到其他设备。网站上的两步验证仍然有效，需要到该网站设置里才能关闭。",
        confirmLabel = "删除",
        dismissLabel = "取消",
        onConfirm = {
            confirmDelete = false
            onDelete()
        },
        onDismiss = { confirmDelete = false },
    )
}
