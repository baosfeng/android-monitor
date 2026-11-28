package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 剩余量横条（原型 .track + .track>i）：账号行 5dp、展开区窗口行 6dp、组件样张 4dp 共用。
 *
 * 宽度口径只有一条：填充 = 轨道 × fraction（同一函数画所有横条，避免「数值 12%、条却像 24%」）。
 * 轨道与填充各带一个组件级锚点，供像素级断言（见 QuotaMeterTrackTest）；
 * fraction 为 null（纯余额型）时**不画轨道**——余额没有「剩余比例」这回事。
 */
@Composable
internal fun QuotaMeterTrack(
    fraction: Float?,
    color: Color,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    if (fraction == null) {
        // 占位保持行高：不画轨道，但把高度留给它（余额型与百分比型行高一致）
        Box(modifier.fillMaxWidth().height(height))
        return
    }
    val value = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(t.fgFade(12))
            .testTag("quota.track"),
    ) {
        if (value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(color)
                    .testTag("quota.track-fill"),
            )
        }
    }
}
