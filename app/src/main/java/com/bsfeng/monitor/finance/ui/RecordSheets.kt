package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt；职责：「记一笔」四个抽屉的入口说明。抽屉按类型分文件：
// RecordSheetCategorySheet（分类）、RecordSheetAccountSheet（账户）、RecordSheetTimeSheet（日期+时刻滚轮）、
// RecordSheetReceiptSheet（单据明细），共用外壳见 RecordSheetScaffold。

/**
 * 「记一笔」的四个抽屉（定版原型 s14a：全抽屉方案）。
 *
 * 四个抽屉共用同一套外壳（[SheetShell] + [com.bsfeng.monitor.ui.components.BottomSheet] + 标题），
 * 差异只在内容 —— 原型选 A 版的核心理由就是这个：分类 / 账户 / 时间 / 单据**一个交互模型**，
 * 抽屉开合不影响上方表单高度（B 版内联展开会把表单顶出屏幕，实测 850 > 738）。
 *
 * 关闭语义：点遮罩 = 关闭；分类 / 账户**选中即关**；时间要连着选日期与时刻，
 * 由「确定」关闭（用户明确要求过这一点）。
 */
