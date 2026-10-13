package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
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
    const val BUILT_IN_VERSION = 8

    /** 全局规则 ID */
    const val GLOBAL_SPLASH_RULE_ID = "builtin_global_splash"

    /** 两次动作最小间隔（毫秒） */
    private const val ACTION_CD_MS = 1_000L

    /** 跳过按钮文本特征：可见 + 短文本 + 含"跳过/skip" */
    private const val TEXT_SKIP =
        "[childCount=0][visibleToUser=true][text.length<10][text*=\"跳过\"||text*=\"跳過\"||text*=\"skip\"||text*=\"Skip\"]"

    /** 跳过按钮描述特征 */
    private const val DESC_SKIP =
        "[childCount=0][visibleToUser=true][desc.length<10][desc*=\"跳过\"||desc*=\"skip\"||desc*=\"Skip\"]"

    /** 跳过按钮 View ID 特征（字节系穿山甲 SDK、倒计时按钮） */
    private const val VIEW_ID_SKIP =
        "[visibleToUser=true][id$=\"tt_splash_skip_btn\"||vid*=\"skip\"||vid*=\"Skip\"||(vid*=\"count\"&&vid*=\"down\"&&vid!*=\"download\")]"

    /** 关闭广告文本特征（不含裸"关闭"，避免误触普通弹窗） */
    private const val CLOSE_AD_TEXT =
        "[childCount=0][visibleToUser=true][text.length<10][text*=\"关闭广告\"||desc*=\"关闭广告\"]"

    /** 小尺寸跳过按钮（限制宽高，进一步降低误触） */
    private const val TEXT_SKIP_SMALL =
        "[visibleToUser=true][text*=\"跳过\"][text.length<10][width<500&&height<300]"

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

    // ==================== 全局规则 ====================

    private fun globalSplashRule(matchTimeMs: Long): SkipRule = SkipRule(
        id = GLOBAL_SPLASH_RULE_ID,
        app = SkipRule.GLOBAL_APP,
        name = "开屏广告（全局）",
        enabled = true,
        groups = listOf(
            RuleGroup(
                key = 0,
                name = "开屏广告-跳过",
                order = 0,
                matchTime = matchTimeMs,
                actionMaximum = 1,
                actionCd = ACTION_CD_MS,
                resetMatch = ResetMatch.APP,
                rules = listOf(
                    rule(0, "跳过文本", TEXT_SKIP),
                    rule(1, "跳过描述", DESC_SKIP),
                    rule(2, "跳过按钮 ID", VIEW_ID_SKIP)
                )
            ),
            RuleGroup(
                key = 1,
                name = "开屏广告-关闭广告",
                order = 1,
                matchTime = matchTimeMs,
                actionMaximum = 1,
                actionCd = ACTION_CD_MS,
                resetMatch = ResetMatch.APP,
                rules = listOf(rule(0, "关闭广告文本", CLOSE_AD_TEXT))
            )
        )
    )

    // ==================== 应用规则 ====================

    private fun appRules(matchTimeMs: Long): List<SkipRule> = listOf(
        appRule(
            id = "bilibili_splash",
            app = "tv.danmaku.bili",
            name = "哔哩哔哩",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    activityIds = listOf("tv.danmaku.bili.MainActivityV2"),
                    rules = listOf(
                        rule(0, "倒计时/跳过按钮", "[visibleToUser=true][vid=\"count_down\"||vid=\"skip\"||id$=\"tt_splash_skip_btn\"]"),
                        rule(1, "跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        ),
        appRule(
            id = "taobao_lite_splash",
            app = "com.taobao.litetao",
            name = "淘特",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    rules = listOf(
                        rule(0, "剩余秒数倒计时", "[visibleToUser=true][text^=\"还剩\"][text$=\"秒\"]"),
                        rule(1, "跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        ),
        appRule(
            id = "meituan_splash",
            app = "com.sankuai.meituan",
            name = "美团",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    rules = listOf(
                        rule(0, "可点击跳过文本", "TextView[text*=\"跳过\"][text.length<8][clickable=true][visibleToUser=true]"),
                        rule(1, "跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        ),
        appRule(
            id = "eleme_splash",
            app = "me.ele",
            name = "饿了么",
            groups = listOf(splashGroup(matchTimeMs = matchTimeMs, rules = listOf(rule(0, "跳过文本", TEXT_SKIP_SMALL))))
        ),
        appRule(
            id = "jd_splash",
            app = "com.jingdong.app.mall",
            name = "京东",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    rules = listOf(
                        rule(0, "穿山甲跳过按钮", "[visibleToUser=true][id$=\"tt_splash_skip_btn\"]"),
                        rule(1, "小尺寸跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        ),
        appRule(
            id = "pdd_splash",
            app = "com.xunmeng.pinduoduo",
            name = "拼多多",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    rules = listOf(
                        rule(0, "穿山甲跳过按钮", "[visibleToUser=true][id$=\"tt_splash_skip_btn\"]"),
                        rule(1, "小尺寸跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        ),
        appRule(
            id = "douyin_splash",
            app = "com.ss.android.ugc.aweme",
            name = "抖音",
            groups = listOf(
                splashGroup(
                    matchTimeMs = matchTimeMs,
                    rules = listOf(
                        rule(0, "穿山甲跳过按钮", "[visibleToUser=true][id$=\"tt_splash_skip_btn\"]"),
                        rule(1, "小尺寸跳过文本", TEXT_SKIP_SMALL)
                    )
                )
            )
        )
    )

    // ==================== 构造辅助 ====================

    private fun appRule(id: String, app: String, name: String, groups: List<RuleGroup>): SkipRule =
        SkipRule(id = id, app = app, name = name, enabled = true, groups = groups)

    /** 开屏规则组：与监控窗口一致的匹配时间 + 每次进入 App 仅执行一次 */
    private fun splashGroup(
        matchTimeMs: Long,
        activityIds: List<String> = emptyList(),
        rules: List<MatchRule>
    ): RuleGroup = RuleGroup(
        key = 0,
        name = "开屏广告",
        order = 0,
        activityIds = activityIds,
        matchTime = matchTimeMs,
        actionMaximum = 1,
        actionCd = ACTION_CD_MS,
        resetMatch = ResetMatch.APP,
        rules = rules
    )

    private fun rule(key: Int, name: String, vararg matches: String): MatchRule = MatchRule(
        key = key,
        name = name,
        matches = matches.toList(),
        action = MatchAction.CLICK,
        fastQuery = true
    )
}
