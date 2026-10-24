package com.bsfeng.monitor.skip

// 来源：原 skip/BuiltInRules.kt；职责：内置规则入口与版本/ID/全局排除名单（具体规则内容分见
// BuiltInGlobalSplashRule / BuiltInAppRules / BuiltInWeChatRule / BuiltInSelectors / BuiltInRuleBuilder）。

import com.bsfeng.monitor.domain.model.SkipRule

/**
 * 内置开屏跳过规则库（自研，选择器语法与字段语义对齐 GKD）。
 *
 * ## 设计原则
 * 1. **全局规则为主**：开屏跳过按钮的通用特征是「文本含 跳过/skip 的短文本可见叶子节点」
 *    或「广告 SDK 的固定 View ID」（字节系 `tt_splash_skip_btn`、倒计时 `count_down`）。
 *    一条全局规则即可覆盖绝大多数 App，避免为每个 App 维护易腐烂的选择器。
 * 2. **时间闸门防误触**：全局规则仅在「切换到 App 后的 [SkipConfig.watchWindowMs] 毫秒内」参与匹配
 *    （对应 GKD 的 `matchTime`），且每次进入 App 仅执行一次（`actionMaximum=1` + `resetMatch=APP`），
 *    因此正常页面里的"跳过"按钮不会被误点。
 * 3. **应用规则补充**：只对节点特征明确、且全局规则不够稳的 App 补规则。
 *
 * ## 与 GKD 社区订阅的关系
 * 社区订阅（AIsouler 12k★ / Lin-arm 5.9k★）**没有 LICENSE 文件**，其规则数据不可内置，
 * 但可解析为本模块同一套模型（见 [GkdSubscription]）由用户自行添加订阅 URL。
 *
 * 关联文档：→ `docs/开屏跳过/设计.md` · `docs/开屏跳过/业界调研与改进方案.md`
 */
object BuiltInRules {

    /** 内置规则版本：内容有增删改时递增，触发本地规则合并（保留用户启停状态） */
    const val BUILT_IN_VERSION = 10

    /** 全局规则 ID */
    const val GLOBAL_SPLASH_RULE_ID = "builtin_global_splash"

    /** 全局规则默认不生效的包名（系统 UI / 桌面 / 自身 / 浏览器 / 输入法：这些界面里的"跳过"多为正常功能） */
    val GLOBAL_EXCLUDED_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",
        "com.miui.home",
        "com.miui.aod",
        "miui.systemui.plugin",
        "com.miui.securitycenter",
        "com.bsfeng.monitor",
        "com.quark.browser",
        "com.tencent.mtt",
        "com.UCMobile",
        "mark.via",
        "com.android.chrome",
        "com.sohu.inputmethod.sogou",
        "com.android.inputmethod.latin"
    )

    /**
     * 全部内置规则（全局规则在首位）。
     *
     * @param matchTimeMs 规则参与匹配的时间窗口（毫秒，自 App 切换起算）。
     *   由 [SkipRuleManager.load] 传入 [SkipConfig.watchWindowMs]，保证与引擎扫描窗口一致——
     *   否则窗口调大后规则仍会在旧的时间闸门处失效。
     */
    fun getRules(matchTimeMs: Long = SkipConfig.DEFAULT_WATCH_WINDOW_MS): List<SkipRule> =
        listOf(globalSplashRule(matchTimeMs)) + appRules(matchTimeMs)
}
