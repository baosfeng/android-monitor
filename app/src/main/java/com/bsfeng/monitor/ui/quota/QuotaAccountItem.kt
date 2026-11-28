package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 账号列表里的一项（原型 .acct：行 + 可展开的详情，外面套一张卡片的外框）。
 *
 * 单独成文件：列表项同时决定「卡片圆角在谁身上 / 展开区挂在哪个锚点下 / 行与详情的层级」，
 * 与整屏的页头、结论条分开后，QuotaScreen 只剩编排。
 */
@Composable
internal fun QuotaAccountItem(
    account: QuotaAccountUi,
    key: String?,
    index: Int,
    total: Int,
    state: QuotaRepository.ProviderState?,
    expanded: Boolean,
    resetMode: QuotaResetMode,
    nowEpochSecond: Long,
    onToggle: () -> Unit,
    onRangeChange: (QuotaRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val position = quotaCardPosition(index, total)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            // 原型 s18-list 的几何 = 列表首行的上沿与 361dp 宽：锚点落在首行容器上
            .then(if (index == 0) Modifier.testTag("s18-list") else Modifier)
            .quotaCardChrome(position, t.surface, t.borderSoft),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (key != null) Modifier.testTag(quotaAccountBodyTag(key)) else Modifier,
                    ),
            ) {
                QuotaAccountRow(
                    account = account,
                    key = key,
                    expanded = expanded,
                    showWhen = quotaShowsResetWhen(resetMode, account.urgent),
                    onToggle = onToggle,
                )
            }
            if (expanded && key != null) {
                QuotaAccountDetail(
                    account = account,
                    key = key,
                    state = state ?: QuotaRepository.ProviderState(account.instanceId),
                    supportsUsage = account.supportsUsage,
                    nowEpochSecond = nowEpochSecond,
                    onRangeChange = onRangeChange,
                )
            }
        }
    }
}
