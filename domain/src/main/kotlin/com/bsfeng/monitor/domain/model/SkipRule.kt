package com.bsfeng.monitor.domain.model

/**
 * 开屏跳过规则数据模型（领域层，语义对齐 GKD 订阅规则）。
 *
 * 不含 JSON 序列化方法，序列化由 app 模块的扩展函数实现
 * （`app/src/main/java/com/bsfeng/monitor/skip/SkipRuleSerialization.kt`）。
 */
data class SkipRule(
    /** 规则唯一标识（应用规则为包名或自定义 ID；全局规则为 [SkipRule.GLOBAL_APP]） */
    val id: String,
    /** 应用包名；[GLOBAL_APP] 表示对所有 App 生效的全局规则 */
    val app: String,
    /** 应用/规则名称 */
    val name: String,
    /** 是否启用 */
    val enabled: Boolean = true,
    /**
     * 按应用排除名单（仅全局规则使用，对应 GKD `globalGroups[].apps[{id, enable:false}]`）。
     * 命中名单的 App 不应用该全局规则，避免误点系统界面/浏览器等场景中的"跳过"。
     */
    val excludedApps: List<String> = emptyList(),
    /** 规则组列表 */
    val groups: List<RuleGroup> = emptyList()
) {
    companion object {
        /** 全局规则的应用标识 */
        const val GLOBAL_APP = "*"
    }

    /** 是否为全局规则 */
    val isGlobal: Boolean get() = app == GLOBAL_APP
}

/**
 * 规则组：一组针对特定界面的匹配规则。
 *
 * [activityIds]/[excludeActivityIds] 为 **前缀匹配**（GKD 语义）：
 * 以 `.` 开头时自动拼接应用包名；为空表示不限定界面。
 */
data class RuleGroup(
    /** 组在应用内的唯一键（GKD 兼容字段） */
    val key: Int = 0,
    /** 组名（如"开屏广告"） */
    val name: String = "",
    /** 匹配顺序，越小越先匹配 */
    val order: Int = 0,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 限定生效的界面（前缀匹配，空 = 全部界面） */
    val activityIds: List<String> = emptyList(),
    /** 排除的界面（优先级高于 [activityIds]） */
    val excludeActivityIds: List<String> = emptyList(),
    /** 规则参与匹配的时间窗口（毫秒，自 App 切换起算；<=0 表示不限制） */
    val matchTime: Long = 10_000L,
    /** 组内规则默认最大执行次数（达到后休眠，由 [resetMatch] 重置） */
    val actionMaximum: Int = 1,
    /** 组内规则默认冷却时间（毫秒，两次动作最小间隔） */
    val actionCd: Long = 1_000L,
    /** 计数/计数窗口的重置时机 */
    val resetMatch: ResetMatch = ResetMatch.APP,
    /** 规则列表（按数组顺序匹配） */
    val rules: List<MatchRule> = emptyList()
)

/**
 * 匹配规则：命中选择器后执行 [action]。
 */
data class MatchRule(
    /** 规则在组内的唯一键 */
    val key: Int = 0,
    /** 规则名（便于排查） */
    val name: String = "",
    /** 选择器列表：全部匹配时点击最后一个选择器的目标节点 */
    val matches: List<String> = emptyList(),
    /** 候选选择器：任一匹配即点击该节点 */
    val anyMatches: List<String> = emptyList(),
    /** 排除选择器：任一匹配则放弃本规则 */
    val excludeMatches: List<String> = emptyList(),
    /** 命中后执行的动作 */
    val action: MatchAction = MatchAction.CLICK,
    /** 匹配延迟（毫秒，规则挂起一段时间后再参与匹配） */
    val matchDelay: Long = 0L,
    /** 命中后延迟执行（毫秒）：等待后重新匹配仍命中才点击，用于抗误触 */
    val actionDelay: Long = 0L,
    /** 后续超时（毫秒，自 App 切换起算；null 表示继承组配置） */
    val matchTime: Long? = null,
    /** 最大执行次数（null 表示继承组配置） */
    val actionMaximum: Int? = null,
    /** 冷却时间（毫秒，null 表示继承组配置） */
    val actionCd: Long? = null,
    /** 与同组内该 key 的规则共享冷却（GKD `actionCdKey`） */
    val actionCdKey: Int? = null,
    /** 与同组内该 key 的规则共享执行次数（GKD `actionMaximumKey`） */
    val actionMaximumKey: Int? = null,
    /**
     * 要求同组内该 key 的规则**刚刚执行过**本规则才允许触发（GKD `preKeys`）。
     * 用于"点击关闭按钮 → 选择原因 → 确认关闭"这类多步流程防止误触。
     */
    val preKeys: List<Int> = emptyList(),
    /** 是否启用快速查询（false 时强制深度优先遍历，保证匹配顺序） */
    val fastQuery: Boolean = true,
    /** 自定义点击位置（归一化 0..1，相对屏幕；仅 [MatchAction.CLICK_CENTER] 生效） */
    val position: ClickPosition? = null
)

/** 匹配动作（语义对齐 GKD） */
enum class MatchAction(val value: String) {
    /** 默认：节点可点击时点节点，否则点节点中心 */
    CLICK("click"),

    /** 点击无障碍节点（要求节点 clickable） */
    CLICK_NODE("clickNode"),

    /** 点击节点中心坐标（可命中被遮挡的最上层节点） */
    CLICK_CENTER("clickCenter"),

    /** 返回键 */
    BACK("back"),

    /** 默认：可长按时长按节点，否则长按中心 */
    LONG_CLICK("longClick"),

    /** 不做动作，仅作为匹配标记 */
    NONE("none");

    companion object {
        fun from(value: String?): MatchAction =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: CLICK
    }
}

/** 自定义点击位置（归一化坐标，0..1 相对屏幕宽高） */
data class ClickPosition(val x: Float, val y: Float)

/** matchTime/actionMaximum 的重置时机（GKD 语义） */
enum class ResetMatch(val value: String) {
    /** Activity 刷新时重置（如播放页切到另一个播放页） */
    ACTIVITY("activity"),

    /** 由不匹配界面切换到匹配界面时重置 */
    MATCH("match"),

    /** 重新进入 App 时重置 */
    APP("app");

    companion object {
        fun from(value: String?): ResetMatch =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: ACTIVITY
    }
}
