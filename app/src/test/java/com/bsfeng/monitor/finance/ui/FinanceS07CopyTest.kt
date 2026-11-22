package com.bsfeng.monitor.finance.ui

import org.junit.Assert.assertEquals
import org.junit.Test

//
// s07 统计分析空态文案的回归（原型 .open-design/src/screens/s07-统计分析.html:195-200）。
//
// 原型写「记一笔账或从熊猫记账导入历史数据后…」。本仓库确有这条通道（finance/PandaImporter、
// FinanceViewModel.importFromPanda，导入页 ImportStepsCard 也写明支持熊猫记账 .db），
// 所以实现事实与原型一致：文案逐字保留，不泛化成「导入历史账单」。
//
class FinanceS07CopyTest {

    @Test
    fun `空态描述与原型逐字一致`() {
        assertEquals(
            "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
            S07_EMPTY_DESC,
        )
    }
}
