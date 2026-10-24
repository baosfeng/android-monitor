package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt 的 BudgetEditorTarget；职责：预算编辑弹窗的目标（总预算的 categoryName 为空串）。

/** 预算编辑目标（总预算的 categoryName 为空串） */
internal data class BudgetEditorTarget(
    val categoryName: String,
    val title: String,
    val currentAmount: Double,
)
