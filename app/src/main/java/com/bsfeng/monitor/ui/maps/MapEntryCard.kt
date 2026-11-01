package com.bsfeng.monitor.ui.maps

// 「在地图中查看」入口卡：轨迹卡下方的一整行，点开把当前轨迹点甩到已装地图 App。
//
// 三条路径（严格按顺序）：
// 1. 恰好一个候选（典型 = 只装了高德）→ 直接打开，不弹任何东西；
// 2. 多个候选 → AppDialog 列出「高德 / 腾讯 / 百度 / Google / 浏览器」，选中即打开；
// 3. 一个都没有 → 不打开任何东西，toast 明确说「没有可用的地图或浏览器应用」。
//
// 浏览器兜底永远排在候选末尾，因此「直接打开」不会落到网页上（只有它一个时才落到网页）。
//
// 设计口径：复用 AppCard + 44dp 点击区 + pressFeedback + LIGHT 震，与 s16 其它入口行一致。
// 锚点 s16-map-entry 已在 锚点账本 登记（scripts/check-anchors.mjs
// 要求「代码锚点 ⊆ 锚点」，新增锚点必须先登记后使用）。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 入口行标题：固定文案，点开才决定打开哪个 App。 */
private const val ENTRY_TITLE = "在地图中查看"

/** 入口行副标题：说清会打开什么，不承诺「内置地图」（Android 本身没有）。 */
private const val ENTRY_SUBTITLE = "用手机上已装的高德 / 腾讯 / 百度 / Google 打开"

/** 选择弹窗标题。 */
private const val PICKER_TITLE = "选择地图应用"

/** 选择弹窗说明（解释为什么会有多个候选，以及浏览器是兜底）。 */
private const val PICKER_MESSAGE = "用已安装的地图应用打开这个位置；浏览器仅在没有地图应用时兜底。"

/**
 * 在轨迹卡下方展示的「在地图中查看」入口。
 *
 * @param point 要打开的位置（WGS84，来自当天轨迹）；为 null 或非法时整卡不渲染（没坐标就没可看的）
 * @param label 地点名，可空
 * @param onMessage 需要给用户反馈时回调（无候选 / 打开失败）；由 TripScreen 接 AppToast
 */
@Composable
fun MapEntryCard(
    point: GeoPoint?,
    label: String?,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (point == null || !point.isValid) return
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val haptic = rememberHaptic()
    var candidates by remember(point, label) { mutableStateOf(emptyList<MapCandidate>()) }
    var pickerOpen by remember(point, label) { mutableStateOf(false) }

    /** 直接打开一个候选；失败时报哪个 App 没打开。 */
    fun openOne(candidate: MapCandidate) {
        if (!MapLauncher.open(context, candidate)) onMessage(openFailureText(candidate.target))
    }

    /** 点入口：算候选 → 直接开 / 弹选择 / 报无候选。 */
    fun onClick() {
        haptic(HapticTone.LIGHT)
        val plan = MapLauncher.planFor(context, point, label)
        if (plan.candidates.isEmpty()) {
            onMessage(MapEntryPlan.NO_BROWSER_TEXT)
            return
        }
        val single = plan.single
        if (single != null) {
            // 落到浏览器时先说清为什么打开的是网页而不是地图 App（用户需要知道没装地图）
            if (plan.usingBrowserFallback) onMessage(MapEntryPlan.NO_MAP_TEXT)
            openOne(single)
        } else {
            candidates = plan.candidates
            pickerOpen = true
        }
    }

    // 锚点 s16-map-entry 已在 锚点账本 登记
    AppCard(modifier = modifier.testTag("s16-map-entry")) { EntryRow(onClick = ::onClick) }

    if (pickerOpen) {
        AppDialog(visible = pickerOpen, onDismiss = { pickerOpen = false }) {
            Text(PICKER_TITLE, style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space2))
            Text(PICKER_MESSAGE, style = MaterialTheme.typography.bodySmall, color = t.muted)
            Spacer(Modifier.height(t.space3))
            Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
                candidates.forEach { candidate ->
                    PickerRow(
                        label = candidate.target.label,
                        onClick = {
                            pickerOpen = false
                            haptic(HapticTone.LIGHT)
                            openOne(candidate)
                        },
                    )
                }
            }
            Spacer(Modifier.height(t.space2))
        }
    }
}

/** 入口行：图标 + 标题 + 副标题 + ›，整行 ≥44dp 触达。 */
@Composable
private fun EntryRow(onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusLg))
            .testTag("row.map.entry")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .heightIn(min = t.rowMinH)
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(t.space8)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Map,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(t.space5),
            )
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = ENTRY_TITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(ENTRY_SUBTITLE)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(t.space5),
        )
    }
}

/** 选择弹窗里的一行：整行 ≥44dp，按下 0.96 + LIGHT。 */
@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction)
            .clip(shape)
            .background(t.fgFade(5))
            .testTag("row.map.picker")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 打开失败文案：说清是哪个 App 没打开，用户才知道下一步怎么办。 */
internal fun openFailureText(target: MapTarget): String =
    "打不开" + target.label + "，请检查该应用是否被冻结或已卸载"
