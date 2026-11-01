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
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpCountdown
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
) {
    val t = LocalDesignTokens.current
    var revealed by rememberSaveable(account?.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(revealed) {
        if (revealed) {
            delay(SECRET_REVEAL_MS)
            revealed = false
        }
    }

    TotpScaffold(title = "账户详情", testTag = "screen-s26", onBack = onBack, modifier = modifier) {
        if (account == null) {
            AppCard { StateSkeleton(rows = 4) }
            return@TotpScaffold
        }
        val countdown = remember(nowMillis, account.params.periodSec) {
            TotpCountdown.of(nowMillis, account.params.periodSec)
        }
        val code = remember(nowMillis, account.params, account.secretBase32) {
            deriveCode(account.secretBase32, nowMillis / 1_000L, account.params)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
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

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fg.copy(alpha = t.fg.alpha * 0.05f))
                .padding(t.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前验证码", style = MaterialTheme.typography.bodySmall, color = t.muted)
                TotpCodeText(code = code, digits = account.params.digits, wide = account.params.digits == 8)
            }
            TotpCountdownRing(countdown)
            Text(
                "${countdown.remainingSec}s",
                style = monitorMonoMeta.copy(fontSize = t.textXs),
                color = if (isCountdownWarning(countdown)) t.warnText else t.muted,
                modifier = Modifier.padding(start = t.space2),
            )
        }

        TotpTextField(
            value = issuerDraft,
            onValueChange = onIssuerChange,
            label = "平台名",
            placeholder = account.issuer,
        )
        TotpTextField(
            value = accountDraft,
            onValueChange = onAccountChange,
            label = "账户",
            placeholder = "例如 alice@acme.com",
        )
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
                )
            },
        )
        TotpFine("掩码显示，默认不落屏；显示 15 秒后自动收回。")

        AppCard {
            TotpParamRow("算法", "${account.params.algorithm} · ${account.params.digits} 位 · ${account.params.periodSec} 秒")
            TotpHairline()
            TotpParamRow("来源", sourceLabel)
            TotpHairline()
            TotpParamRow("最近使用", lastUsedLabel)
        }
        TotpFine("参数由二维码决定，改错会算出错码，所以这里只读；要换参数请重新扫码添加。")

        AppCard(padding = t.space2) {
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
                AppButton("上移一位", onMoveUp, variant = AppButtonVariant.Ghost, modifier = Modifier.weight(1f))
                AppButton("下移一位", onMoveDown, variant = AppButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(t.space1))
        AppButton(text = "保存修改", onClick = onSave, block = true, tone = HapticTone.SUCCESS)

        Column(
            Modifier
                .fillMaxWidth()
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
            )
        }
        TotpFine("改名与换平台只影响本机展示，不影响网站上已绑定的两步验证。")
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
