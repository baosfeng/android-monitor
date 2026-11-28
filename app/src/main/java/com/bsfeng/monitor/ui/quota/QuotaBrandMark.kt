package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.R
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.QuotaBrandColors

/**
 * 账号行左侧的品牌块（原型 .pav 28dp 底 + 20dp 标）。
 *
 * 四家的标与原型同源：opencode-go / open-design 用原型内联 SVG 的路径字面量
 * （PathParser 解析一次后缓存，绘制期只缩放），Ollama / DeepSeek 用从原型 data URI
 * 解出的 PNG（res/drawable-nodpi，零外链、零第三方图标库）。
 */
@Composable
internal fun QuotaBrandMark(providerTypeId: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            // 原型 .pav 28×28；token 无 28（24 / 32 之间），用 space6 + space1 表达
            .size(t.space6 + t.space1)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(t.surfaceWarm)
            .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusSm)),
        contentAlignment = Alignment.Center,
    ) {
        when (providerTypeId) {
            "opencode-go" -> VectorMark(viewBox = 512f, paths = OPENCODE_PATHS)
            "opendesign" -> VectorMark(viewBox = 82f, paths = OPEN_DESIGN_PATHS)
            OLLAMA_ID -> BrandImage(R.drawable.quota_brand_ollama)
            "deepseek" -> BrandImage(R.drawable.quota_brand_deepseek)
            // 表外 provider（将来新增）：中性字块兜底，不假装是某家的官方标
            else -> Text(providerTypeId.take(1).uppercase(), style = quotaTextSmall, color = t.fg2)
        }
    }
}

/** 标的内框边长（原型 .pav-img 20×20）。 */
private val MARK_SIZE: Dp = 20.dp

@Composable
private fun BrandImage(resId: Int) {
    Image(
        painter = painterResource(resId),
        contentDescription = null, // decorative：账号名就在同一行，重复播报只是噪音
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(MARK_SIZE),
    )
}

/**
 * 矢量标：路径字符串 → Path（remember 里解析一次），绘制期只做缩放与 drawPath。
 *
 * opencode-go 的外框是 evenodd（把内方块挖空）；其余按 SVG 默认 nonzero。
 */
@Composable
private fun VectorMark(viewBox: Float, paths: List<Pair<String, Color>>) {
    val parsed = remember(paths) {
        paths.map { (data, color) ->
            val path = PathParser().parsePathString(data).toPath()
            if (data == OPENCODE_FRAME) {
                path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            }
            color to path
        }
    }
    Canvas(Modifier.size(MARK_SIZE)) {
        val side = this.size.minDimension
        withTransform({ scale(side / viewBox, side / viewBox, pivot = Offset.Zero) }) {
            parsed.forEach { (color, path) -> drawPath(path, color) }
        }
    }
}

/** 原型 s18 --logo-oc 的偶数填充规则外框（512 viewBox）。 */
private const val OPENCODE_FRAME: String =
    "M384 416H128V96H384V416ZM320 160H192V352H320V160Z"

private val OPENCODE_PATHS: List<Pair<String, Color>> = listOf(
    "M320 224V352H192V224H320Z" to QuotaBrandColors.opencodeInner,
    OPENCODE_FRAME to QuotaBrandColors.opencodeFrame,
)

/** 原型 s18 --logo-od 的单个路径（82 viewBox，三个子路径：圆角方底 + 环 + 内标）。 */
private val OPEN_DESIGN_PATHS: List<Pair<String, Color>> = listOf(
    "M41 0.726562C76.5753 0.726562 82 6.15121 82 41.7266C82 77.3019 76.5753 82.7266 41 82.7266C5.42465 82.7266 0 77.3019 0 41.7266C0 6.15121 5.42465 0.726562 41 0.726562ZM40.8906 16.4258C26.9164 16.4258 15.5879 27.7543 15.5879 41.7285C15.5879 46.7757 15.5879 58.0219 15.5879 63.665C15.588 65.5281 17.091 67.0312 18.9541 67.0312H40.8906C54.8647 67.0311 66.1932 55.7026 66.1934 41.7285C66.1934 27.7544 54.8647 16.4259 40.8906 16.4258ZM40.8906 21.4863C52.0699 21.4864 61.1328 30.5492 61.1328 41.7285C61.1327 52.9078 52.0699 61.9706 40.8906 61.9707C29.7113 61.9707 20.6485 52.9078 20.6484 41.7285C20.6484 30.5491 29.7113 21.4863 40.8906 21.4863ZM32.6445 32.2549C31.9921 32.0027 31.3503 32.6469 31.5996 33.3037L39.3145 53.6045C39.6345 54.4468 40.877 54.2162 40.877 53.3145V41.6836H52.665C53.5605 41.6836 53.7908 40.4368 52.9551 40.1133L32.6445 32.2549Z"
        to QuotaBrandColors.openDesignInk,
)
