package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04 时间单位查询卡）；职责：五档周期枚举 —— 界面上 今日/本周/本月/本年/全部
// 与「关闭周期聚合入口时不再拉数据」共用同一份 key，避免界面字符串散落在 ViewModel 与 DAO 两侧。

/**
 * 记账统计的时间单位（s04 unitbar 的顺序即枚举声明顺序）。
 *
 * @param key 稳定标识：单测、日志与「周期聚合入口」开关都用它，不随文案改动而变化
 * @param label 界面文案（unitbar 标签 / KPI 前缀，如「今日支出」）
 */
enum class FinancePeriod(val key: String, val label: String) {
    DAY("day", "今日"),
    WEEK("week", "本周"),
    MONTH("month", "本月"),
    YEAR("year", "本年"),
    ALL("all", "全部"),
    ;

    companion object {
        /** 按 key 反查（持久化恢复 / 需求里出现字符串时用）；未知返回 [DAY]。 */
        fun ofKey(key: String): FinancePeriod =
            entries.firstOrNull { it.key == key } ?: DAY
    }
}
