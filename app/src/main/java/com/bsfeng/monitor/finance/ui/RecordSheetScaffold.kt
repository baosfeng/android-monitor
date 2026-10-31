package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的抽屉外壳与按钮；职责：四个抽屉共用的 SheetShell（BottomSheet + 标题）与主/次按钮。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 抽屉外壳：底部面板 + 标题（原型 .scrim.is-bottom > .sheet + .h3）。
 *
 * 遮罩不在页面里自绘：[BottomSheet] 走窗口级全屏承载，遮罩与面板一起覆盖整屏（含状态栏与手势条）；
 * 页面内的遮罩只能画到系统栏之内（真机表现为顶部仍亮、不像模态），还会与窗口遮罩叠成两层。
 *
 * 声明成 [BoxScope] 扩展是为了 [BottomSheet] 的底对齐（align 是 Box 的 parentData）。
 */
@Composable
internal fun BoxScope.SheetShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space3))
        content()
    }
}

/** 抽屉次级按钮（原型 .btn-ghost：透明底 + 1px --border 描边 + --fg 文字） */
@Composable
internal fun SheetGhostButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    // 原型 .btn{border-radius:var(--radius-md)} = 12dp
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.border, shape)
            .testTag("button.ghost")
            .clickable { onClick() }
            .padding(vertical = t.space3),   // 原型 .btn 的 min-height:48px 由「上下 --space-3 + 17px 行高」达成
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = t.fg)
    }
}

/** 抽屉主按钮（原型 .btn.btn-block：用于时间抽屉的「确定」） */
@Composable
internal fun SheetPrimaryButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.accent)
            .testTag("button.primary")
            .clickable { onClick() }
            .padding(vertical = t.space3),
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
