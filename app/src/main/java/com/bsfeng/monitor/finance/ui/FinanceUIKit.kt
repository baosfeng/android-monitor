package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt；职责：记账五屏共用基元的入口说明。实现按用途分文件：
// FinanceFormat（文本格式化）、FinanceScreenChrome（屏幕头/月份条/图例点）、FinanceRingGauge（圆环仪表）、
// FinanceCategoryColor（分类色）、FinanceEmptyState（空态与 CTA）、FinanceStateControl（五态演示开关）、
// FinanceViewModelBinding（ViewModel 共享）。

/**
 * 记账五屏（s04–s08）共用的屏幕基元。
 *
 * 全部取值来自 [com.bsfeng.monitor.ui.theme.LocalDesignTokens]，本组文件不含任何色值 / 字号 / 间距字面量；
 * 动效一律走 [com.bsfeng.monitor.ui.components.springOf] 的 token 配方。
 */
