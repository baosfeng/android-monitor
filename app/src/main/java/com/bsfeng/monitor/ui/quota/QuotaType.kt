package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import com.bsfeng.monitor.ui.theme.monitorTypography

/**
 * 额度主屏专用的原型字阶（原型 s18 的 20 / 13 / 11 / 20 等宽 / 16 等宽）。
 *
 * 为什么不改 ui/theme/Type.kt：全局字阶一次改动会牵动所有屏的行高与换行（8 套主题 × 全部页面），
 * 本轮只做额度页；这些档位由本文件局部表达，页面里仍然不写裸 fontSize。
 * 每一档都从既有 token copy 出来（copy(fontSize = …) 是规范允许的「派生档」写法），
 * 等宽数字基线取 monitorMonoMeta，保证逐位对齐。
 */
internal val quotaHeadTitle: TextStyle = monitorTypography.headlineSmall.copy(fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 23.sp,
    letterSpacing = (-0.24).sp,
)

/** 原型 .s18 .ncol .w / .wrow .wk：13px/400（窗口名，行盒 14.95）。 */
internal val quotaLabel13: TextStyle = monitorTypography.bodySmall.copy(fontSize = 13.sp,
    lineHeight = 15.sp,
    letterSpacing = (-0.07).sp,
)

/** 原型 .s18 .when / .concl-k / .chart-l：11px/400（重置时刻、轴标签，行盒 12.65）。 */
internal val quotaTextSmall: TextStyle = monitorTypography.labelSmall.copy(fontSize = 11.sp,
    lineHeight = 13.sp,
    letterSpacing = 0.06.sp,
)

/** 原型 .s18 .ncol .n / .wrow .wv：20px/700 等宽（数位列，行盒 21）。 */
internal val quotaMonoRow: TextStyle = monitorMonoMeta.copy(fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 21.sp,
    letterSpacing = (-0.6).sp,
)

/** 原型 .s18 .wr b：16px/700 等宽（组件样张数值，行盒 17.6）。 */
internal val quotaMonoWidget: TextStyle = monitorMonoMeta.copy(fontSize = 16.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 18.sp,
    letterSpacing = (-0.48).sp,
)
