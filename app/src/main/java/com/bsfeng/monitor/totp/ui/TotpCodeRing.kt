package com.bsfeng.monitor.totp.ui

// 验证码展示的两个原子：30 秒环形倒计时（原型 .totp-ring）与等宽验证码文本（原型 .totp-code）。
// 环形只读传入的 Countdown；秒/进度一律由调用方用 domain 的 TotpCountdown 算好后传入。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.totp.Countdown
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoNumber
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlinx.coroutines.delay

/** 环形直径与描边（原型 --size 18 / 内圈 3px 空隙）。 */
private val RING_SIZE = 18.dp
private val RING_STROKE = 3.dp

/**
 * 整屏共用的秒级时钟：**唯一**读系统时间的地方，只产出时间戳。
 *
 * 把时钟收敛在这里，是为了让各屏都能以 [nowMillis] 形参注入固定时间（测试 / 截图），
 * 屏幕内部只做纯换算，不各自调 System.currentTimeMillis()。
 */
@Composable
fun rememberTotpNow(intervalMs: Long = 1_000L): Long {
    val state by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return state
}

/**
 * 30 秒环形倒计时（原型 .totp-ring：底 fg 13%，进度 fg-2；剩余 ≤5 秒切 --warn）。
 *
 * 进度取 [Countdown.progress]（剩余比例），从 -90° 起顺时针收缩，与原型 conic-gradient 同向。
 */
@Composable
fun TotpCountdownRing(
    countdown: Countdown,
    modifier: Modifier = Modifier,
    ringSize: Dp = RING_SIZE,
) {
    val t = LocalDesignTokens.current
    val warn = isCountdownWarning(countdown)
    val color = if (warn) t.warn else t.fg2
    val track = t.fgFade(13)
    Canvas(modifier.size(ringSize)) {
        val stroke = RING_STROKE.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * countdown.progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
    }
}

/**
 * 等宽验证码（原型 .totp-code：--font-mono + tabular-nums，--text-xl 28/600）。
 *
 * [wide] 对应原型 .totp-code--wide：8 位码降一档字号，窄屏不被压成两行。
 */
@Composable
fun TotpCodeText(
    code: String,
    digits: Int,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    warning: Boolean = false,
    placeholder: String = "— — —",
) {
    val t = LocalDesignTokens.current
    val style = if (wide) {
        monitorMonoNumber.copy(fontSize = t.textLg, lineHeight = t.textLg * 1.15f)
    } else {
        monitorMonoNumber
    }
    Text(
        text = formatCode(code, digits).ifEmpty { placeholder },
        style = style,
        color = if (warning) t.warnText else t.fg,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

/**
 * 账户行（原型 .totp-row）：34dp 平台色块 + 平台/账号 + 右侧验证码与倒计时。
 *
 * 无障碍：整行合成一个节点，description 报「平台 + 账号 + 验证码 + 剩余秒」，
 * 读屏不会把验证码当密码字段（这里没有任何 Password 语义）。
 * 点按 = 复制（副作用上抛），长按 = 编辑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TotpAccountRow(
    account: TotpAccount,
    countdown: Countdown,
    code: String,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onCopy: () -> Unit = {},
    onEdit: () -> Unit = {},
    /** HOTP 账户：右侧不画倒计时环，改画「下一码」按钮。 */
    onAdvanceHotp: (() -> Unit)? = null,
    /**
     * 落在**水平内边距之内**的修饰符（锚点用）。
     *
     * 原型 s22 的行锚点挂在 .row 本体（x=33 / w=327）：卡片本身有 16 水平内缩，
     * 锚点若写在 [modifier]（调制链最前）会连卡片缩进一起量进去（x=24 / w=345）。
     */
    innerModifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val hotp = isHotp(account.params)
    val steam = isSteam(account.variant)
    val warn = !paused && !hotp && isCountdownWarning(countdown)
    // 读屏会逐字念码：Steam 的 5 位字母码念成字母序列，数字码按位念；
    // HOTP 没有「剩余 N 秒」，改念计数器，否则读屏会播报一个永远不变的倒计时。
    val desc = buildString {
        append(account.issuer)
        if (account.accountName.isNotBlank()) append(" ").append(account.accountName)
        when {
            paused -> append("，已暂停")
            hotp -> {
                append("，").append(if (steam) "Steam 字母码 " else "验证码 ").append(code)
                append("，计数器 ").append(account.counter)
            }
            else -> {
                append("，").append(if (steam) "Steam 字母码 " else "验证码 ").append(code)
                append("，剩余 ").append(countdown.remainingSec).append(" 秒")
            }
        }
    }
    // HOTP 行里有独立的「下一码」按钮：语义合并会把子节点的点击动作并进父节点，
    // 读屏就点不到那个按钮（ACTION_CLICK 只剩一个）。所以 HOTP 行不合并，
    // 描述挂在左侧信息列上，按钮保持独立节点。
    val rowSemantics = if (hotp) {
        Modifier
    } else {
        Modifier.semantics(mergeDescendants = true) { contentDescription = desc }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(innerModifier)
            .heightIn(min = ROW_MIN_HEIGHT)
            .then(rowSemantics)
            .combinedClickable(
                onClick = {
                    haptic(HapticTone.LIGHT)
                    onCopy()
                },
                onLongClick = {
                    haptic(HapticTone.LIGHT)
                    onEdit()
                },
            )
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Box(
            Modifier
                .size(AVATAR_SIZE)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(8)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = iconInitial(account.issuer),
                style = monitorMonoMeta.copy(fontSize = t.textSm),
                color = t.fg2,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .then(if (hotp) Modifier.semantics { contentDescription = desc } else Modifier),
        ) {
            Text(
                text = account.issuer,
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = account.accountName,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            TotpCodeText(
                code = if (paused) "" else code,
                digits = account.params.digits,
                // Steam 是 5 位字母码：降档显示，不按「8 位宽码」处理（后者会压字号）
                wide = !steam && account.params.digits == 8,
                warning = warn,
                placeholder = if (paused) "— — —" else "… … …",
            )
            Spacer(Modifier.width(t.space2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space1),
            ) {
                when {
                    paused -> Text(
                        text = "暂停",
                        style = monitorMonoMeta.copy(fontSize = t.textXs),
                        color = t.muted,
                    )
                    // HOTP 不随时间换码：没有倒计时，给一个「下一码」按钮推进计数器
                    hotp -> AppButton(
                        text = "下一码",
                        onClick = { onAdvanceHotp?.invoke() },
                        variant = AppButtonVariant.Ghost,
                        enabled = onAdvanceHotp != null,
                    )
                    else -> {
                        TotpCountdownRing(countdown)
                        Text(
                            text = "${countdown.remainingSec}s",
                            style = monitorMonoMeta.copy(fontSize = t.textXs),
                            color = if (warn) t.warnText else t.muted,
                        )
                    }
                }
            }
        }
    }
}

/** 原型 .totp-row 最小高度 64px（含 8px 上下内边距）。 */
private val ROW_MIN_HEIGHT = 64.dp

/** 原型 .totp-ava 34px。 */
private val AVATAR_SIZE = 34.dp
