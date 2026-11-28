package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 额度页四家 provider 的品牌色（原型 s18 内联 SVG 的 fill 值）。
 *
 * 与 [AppBrandColors] 同一处置：品牌身份色是产品固定值、不随主题变，但色值字面量
 * 只允许出现在 ui/theme，所以集中登记在本文件，页面侧只读常量。
 * opencode-go / open-design 的标是矢量路径（原型 SVG），Ollama / DeepSeek 是位图。
 */
internal object QuotaBrandColors {
    /** opencode-go 内方块（原型 d="M320 224...Z" fill="#5A5858"）。 */
    val opencodeInner: Color = Color(0xFF5A5858)

    /** opencode-go 外框（原型 evenodd 路径 fill="#131010"）。 */
    val opencodeFrame: Color = Color(0xFF131010)

    /** open-design 墨色标（原型 fill="#26251E"）。 */
    val openDesignInk: Color = Color(0xFF26251E)
}
