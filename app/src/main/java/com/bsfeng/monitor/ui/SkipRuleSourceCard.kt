package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：订阅源卡 —— URL 输入 + 更新/新增/快照三个按钮 + 上次同步与镜像说明。
// 负责「规则从哪来、怎么拉」；按钮的禁用态与提示文案都跟着更新中状态走。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 订阅源卡：URL 输入 + 三个动作（更新订阅 / 新增订阅 / 页面快照）+ 同步状态。
 *
 * @param updating 更新中（按钮禁用、文案改「更新中…」，用户不可能重复点）
 * @param updatingUrl 正在更新的源（null = 空闲）
 */
@Composable
internal fun SkipRuleSourceCard(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    updating: Boolean,
    updatingUrl: String?,
    subscriptionCount: Int,
    lastSyncAt: Long,
    snapshotPath: String?,
    onRequestUpdate: () -> Unit,
    onAddSubscription: () -> Unit,
    onRequestSnapshot: () -> Unit,
    // 焦点回位落点（契约 s10-st-empty / s10-confirm / s10-st-error）；null = 本卡不接管焦点。
    // URL 输入框与「更新订阅」按钮都由本卡渲染，回位请求必须由屏幕层持有 requester 再传进来。
    urlFocusRequester: FocusRequester? = null,
    updateFocusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    // 卡片容器锚点 s10-source：卡本身不可按压（原型 .src-card 无 :active），
    // 卡内「更新订阅 / 新增订阅 / 页面快照」三颗按钮共用本锚点（契约 24 行 / 14 锚点）。
    GlassCard(Modifier.testTag("s10-source")) {
        GroupLabel("订阅源 URL", Modifier.padding(start = 0.dp))
        AppField(
            value = urlInput,
            onValueChange = onUrlChange,
            warm = true,
            // URL 输入必须用 Uri 键盘：默认文本键盘会被中文输入法接管（见踩坑记录）
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.testTag("s10-url"),
            focusRequester = urlFocusRequester,
        )
        Spacer(Modifier.height(t.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            AppButton(
                // 原型 s10 没有单独的「更新中」按钮态（08-prototype.html:4574-4586
                // 的 .src-card 内既无 loading 也无 disabled class），因此按原型既有
                // 规则组态：.btn[disabled]{opacity:.45;cursor:default}（同文件 1307 行）
                // → AppButton(enabled = false)（ComponentGalleryScreen.kt:121-152）。
                // 更新中按钮转禁用 + 文案「更新中…」，用户不可能重复点。
                text = if (updating) "更新中…" else "更新订阅",
                onClick = onRequestUpdate,
                // focusRequester 必须在 AppButton 内部 clickable 之前（本 modifier 链在最前）
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (updateFocusRequester != null) {
                            Modifier.focusRequester(updateFocusRequester)
                        } else {
                            Modifier
                        }
                    ),
                enabled = !updating,
                tone = HapticTone.SUCCESS,
            )
            AppButton(
                "新增订阅",
                onClick = onAddSubscription,
                modifier = Modifier.weight(1f),
                variant = AppButtonVariant.Ghost,
                enabled = !updating,
            )
            // 页面快照：AppButton 内部已发一次触感，这里不再叠加（契约「同一操作不重复震动」）
            AppButton(
                "页面快照",
                onClick = onRequestSnapshot,
                modifier = Modifier.weight(1f),
                variant = AppButtonVariant.Ghost,
            )
        }
        Spacer(Modifier.height(t.space3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "上次同步 " + formatSyncTime(lastSyncAt) + " · 镜像 " + mirrorHostOf(urlInput),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(t.space2))
            Badge(subscriptionCount.toString() + " 个源", tone = BadgeTone.Accent)
        }
        if (updating) {
            Spacer(Modifier.height(t.space2))
            Text(
                "正在更新：" + hostOf(updatingUrl.orEmpty()) + " · 大规则包需等待拉取与合并",
                style = MaterialTheme.typography.labelSmall,
                color = t.accent,
            )
        }
        snapshotPath?.let { path ->
            Spacer(Modifier.height(t.space2))
            Text(
                "快照：" + path,
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 更新进行中的骨架提示：原型 s10 的加载态就是一叠 .sk 骨架行（08-prototype.html:4695-4704）。 */
@Composable
internal fun SkipRuleUpdatingCard(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier) {
        Text(
            "正在拉取订阅源并合并规则…（下方仍为本地现有规则）",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        StateSkeleton(rows = 3)
    }
}

/** 边界态示例：超长选择器 / 规则缺字段（原型 s10 的 .st-edge）。 */
@Composable
internal fun SkipRuleEdgeContent() {
    TokenRow(
        title = "某应用名称长到超出一行仍然需要完整展示的极端情况",
        subtitle = "[text=\"跳过广告并关闭\"][id=\"com.example.app.long.package.name:id/skip_button_container\"]",
        trailing = { TailNum("10,286 条") },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "边界态：规则缺字段",
        subtitle = "该规则未提供选择器与优先级，命中次数缺失",
        trailing = { TailNum("—") },
    )
}
