package com.bsfeng.monitor.autobook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.ui.categorySlotOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorRadius
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.categoryDot

/**
 * 自动记账确认卡片（浮窗内容）。
 *
 * 信息层级与 `.open-design/23-autobook-styles.html` 的风格 1/4 一致：
 * 来源胶囊 → 金额 → 商户 → 类型 → 分类 → 账户/时间 → 操作按钮。
 *
 * 所有颜色 / 圆角 / 间距都走设计 token，因此 8 套主题下自动成立。
 */
@Composable
fun AutoBookConfirmSheet(
    draft: AutoBookDraft,
    categoriesByType: Map<Int, List<Category>>,
    onConfirm: (type: Int, categoryName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalDesignTokens.current

    var type by remember(draft.sourceId) { mutableStateOf(draft.type) }
    var category by remember(draft.sourceId) { mutableStateOf(draft.categoryName) }

    val isIncome = type == AutoBookDraft.TYPE_INCOME
    val amountColor = if (isIncome) tokens.success else tokens.danger
    val categories = remember(type, categoriesByType) {
        categoriesByType[if (type == AutoBookDraft.TYPE_INCOME) 1 else 0].orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = MonitorRadius.lg, topEnd = MonitorRadius.lg))
            .background(tokens.surface)
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
                text = "识别到一笔${if (isIncome) "收入" else "支出"}",
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

        Divider()

        // 类型
        Label("类型")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MonitorRadius.sm))
                .background(tokens.fg.copy(alpha = 0.06f))
                .padding(3.dp)
        ) {
            listOf(
                AutoBookDraft.TYPE_EXPENSE to "支出",
                AutoBookDraft.TYPE_INCOME to "收入",
                AutoBookDraft.TYPE_TRANSFER to "转账"
            ).forEach { (value, label) ->
                SegmentItem(
                    text = label,
                    selected = type == value,
                    modifier = Modifier.weight(1f)
                ) { type = value }
            }
        }

        Divider()

        // 分类
        Label("分类")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MonitorSpacing.sm)
        ) {
            categories.take(12).forEach { item ->
                CategoryChip(
                    name = item.name,
                    dotIndex = categorySlotOf(item.name),
                    selected = item.name == category
                ) { category = item.name }
            }
        }

        Divider()

        MetaRow("账户", draft.accountName)
        if (draft.payMethod.isNotBlank()) {
            MetaRow("支付方式", draft.payMethod)
        }
        if (draft.timeText.isNotBlank()) {
            MetaRow("时间", draft.timeText)
        }

        Spacer(Modifier.height(MonitorSpacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MonitorSpacing.sm)
        ) {
            Button(
                onClick = onDismiss,
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
                onClick = { onConfirm(type, category) },
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
private fun CategoryChip(
    name: String,
    dotIndex: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MonitorRadius.circle))
            .background(if (selected) tokens.accent.copy(alpha = 0.12f) else tokens.fg.copy(alpha = 0.06f))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = tokens.accent.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(MonitorRadius.circle)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tokens.categoryDot(dotIndex))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.accent else tokens.fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
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

/** 金额格式化：千分位 + 固定两位小数（与记账页交易行口径一致） */
internal fun formatAmount(amount: Double): String =
    String.format(java.util.Locale.CHINA, "%,.2f", kotlin.math.abs(amount))
