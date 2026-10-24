package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的私有枚举 RecordSheet；职责：抽屉种类（同一时刻只开一个）。

/** 一次只开一个抽屉（原型 s14a 的四个抽屉互斥） */
internal enum class RecordSheet { NONE, CATEGORY, ACCOUNT, TO_ACCOUNT, TIME, RECEIPT }
