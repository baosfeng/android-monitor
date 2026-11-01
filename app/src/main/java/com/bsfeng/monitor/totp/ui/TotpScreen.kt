package com.bsfeng.monitor.totp.ui

// s22 验证器首页：搜索 + 平台分组 + 五态（加载 / 空 / 错误 / 边界 / 正常）。
// 纯展示：账户、搜索词、暂停集合、分组函数全部由上层传入；点行复制、长按编辑都是回调。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.Countdown
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpCountdown
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/**
 * 验证器首页（原型 s22）。
 *
 * @param accounts 账户列表（已按 sortOrder 排好，屏内不再排序）
 * @param state 五态；[FiveState.EDGE] 展示「超长平台名 + 8 位码 + 暂停账户」的边界样例
 * @param nowMillis 秒级时间戳，由 [rememberTotpNow] 或测试注入；屏内只用它做纯换算
 * @param groupOf 分组标题（如「云服务」）；返回空串则整屏不分段
 */
@Composable
fun TotpScreen(
    accounts: kotlin.collections.List<TotpAccount>,
    modifier: Modifier = Modifier,
    state: FiveState = FiveState.POPULATED,
    nowMillis: Long = rememberTotpNow(),
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    pausedIds: Set<Long> = emptySet(),
    groupOf: (TotpAccount) -> String = { "" },
    onCopy: (TotpAccount) -> Unit = {},
    onEdit: (TotpAccount) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onRetry: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    TotpScaffold(title = "验证器", testTag = "screen-s22", onBack = onBack, modifier = modifier) {
        when (state) {
            FiveState.LOADING -> AppCard { StateSkeleton(rows = 5) }
            FiveState.EMPTY -> Column {
                EmptyState(
                    title = "还没有验证器账户",
                    text = "扫码或手动输入 Base32 密钥，验证码只在本机计算，不上传、不联网。",
                    icon = Icons.Outlined.Key,
                    actionLabel = "添加第一个账户",
                    onAction = onAddAccount,
                )
                AppButton(
                    text = "从 Aegis / 2FAS 导入",
                    onClick = onOpenBackup,
                    variant = AppButtonVariant.Ghost,
                    block = true,
                )
            }
            FiveState.ERROR -> Column {
                ErrorState(
                    title = "账户列表读取失败",
                    text = "加密存储暂时不可用（Keystore 未解锁）。系统解锁后重试即可，账户不会丢失。",
                    onRetry = onRetry,
                    actionLabel = "重试",
                )
            }
            FiveState.EDGE -> TotpEdgePreview()
            FiveState.POPULATED -> TotpListBody(
                accounts = accounts,
                nowMillis = nowMillis,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                pausedIds = pausedIds,
                groupOf = groupOf,
                onCopy = onCopy,
                onEdit = onEdit,
                onAddAccount = onAddAccount,
                onOpenBackup = onOpenBackup,
            )
        }
        Spacer(Modifier.height(t.space2))
    }
}

@Composable
private fun TotpListBody(
    accounts: kotlin.collections.List<TotpAccount>,
    nowMillis: Long,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pausedIds: Set<Long>,
    groupOf: (TotpAccount) -> String,
    onCopy: (TotpAccount) -> Unit,
    onEdit: (TotpAccount) -> Unit,
    onAddAccount: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val query = searchQuery.trim()
    val filtered = remember(accounts, query) {
        if (query.isEmpty()) {
            accounts
        } else {
            accounts.filter { a ->
                a.issuer.contains(query, ignoreCase = true) ||
                    a.accountName.contains(query, ignoreCase = true)
            }
        }
    }
    TotpSearchBox(query = searchQuery, onQueryChange = onSearchQueryChange)

    if (filtered.isEmpty()) {
        EmptyState(
            title = "没有匹配的账户",
            text = "换个平台名或邮箱片段试试；也可以直接添加一个新账户。",
            icon = Icons.Outlined.Search,
            actionLabel = "清空搜索",
            onAction = { onSearchQueryChange("") },
        )
    } else {
        val grouped = remember(filtered, groupOf) { filtered.groupBy(groupOf) }
        grouped.forEach { (label, items) ->
            if (label.isNotBlank()) TotpGroupLabel("$label · ${items.size} 个")
            AppCard(padding = t.space2) {
                Column {
                    items.forEachIndexed { index, account ->
                        if (index > 0) TotpHairline(Modifier.padding(horizontal = t.space1))
                        val countdown = remember(nowMillis, account.params.periodSec) {
                            TotpCountdown.of(nowMillis, account.params.periodSec)
                        }
                        val code = remember(account.secretBase32, account.params, nowMillis) {
                            deriveCode(
                                secretBase32 = account.secretBase32,
                                epochSecond = nowMillis / 1_000L,
                                params = account.params,
                            )
                        }
                        TotpAccountRow(
                            account = account,
                            countdown = countdown,
                            code = code,
                            paused = account.id in pausedIds,
                            onCopy = { onCopy(account) },
                            onEdit = { onEdit(account) },
                            modifier = Modifier.padding(horizontal = t.space2),
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(t.space3))
    AppButton(text = "添加账户", onClick = onAddAccount, block = true)
    AppButton(
        text = "备份与导入",
        onClick = onOpenBackup,
        variant = AppButtonVariant.Ghost,
        block = true,
    )
    TotpFine("点按账户即复制验证码，30 秒后自动清空剪贴板；长按可编辑或暂停账户。验证码在本机计算，不联网、不上传。")
}

/** 搜索框（原型 .s22-searchbox）：18dp 放大镜 + 无边框输入。 */
@Composable
private fun TotpSearchBox(query: String, onQueryChange: (String) -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.tapMin + 2.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, if (query.isEmpty()) t.borderSoft else t.accent, shape)
            .padding(horizontal = t.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.fg),
                cursorBrush = SolidColor(t.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "搜索平台或账户",
                            style = MaterialTheme.typography.bodyLarge,
                            color = t.muted,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * 边界态样例（原型 s22-edge）：超长平台名 + 8 位码 + 60 秒周期 + 暂停账户。
 *
 * 文案与密钥全部是公开占位值，不发请求、不落库。
 */
@Composable
private fun TotpEdgePreview() {
    val t = LocalDesignTokens.current
    val longAccount = remember {
        TotpAccount(
            id = -1L,
            issuer = "腾讯云 · 重庆区域对象存储管理控制台",
            accountName = "ops+backup-2024@acme-enterprise-cn.cn",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            params = com.bsfeng.monitor.domain.totp.TotpParams(digits = 8, periodSec = 60),
        )
    }
    val pausedAccount = remember {
        TotpAccount(
            id = -2L,
            issuer = "AWS（已暂停）",
            accountName = "root@acme",
            secretBase32 = "JBSWY3DPEHPK3PXP",
        )
    }
    AppCard(padding = t.space2) {
        Column {
            TotpAccountRow(
                account = longAccount,
                countdown = Countdown(remainingSec = 5, progress = 5f / 60f),
                code = "48291376",
                modifier = Modifier.padding(horizontal = t.space2),
            )
            TotpHairline(Modifier.padding(horizontal = t.space1))
            TotpAccountRow(
                account = pausedAccount,
                countdown = Countdown(remainingSec = 30, progress = 1f),
                code = "",
                paused = true,
                modifier = Modifier.padding(horizontal = t.space2),
            )
        }
    }
    TotpFine("超长平台名与账号单行截断；8 位码降一档字号；暂停账户不参与倒计时。")
}
