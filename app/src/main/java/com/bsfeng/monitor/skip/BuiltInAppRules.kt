package com.bsfeng.monitor.skip

// 来源：原 skip/BuiltInRules.kt 的 appRules；职责：内置应用规则清单（QQ阅读/哔哩哔哩/淘特/美团/饿了么/京东/拼多多/抖音）。

import com.bsfeng.monitor.domain.model.SkipRule

/**
 * QQ阅读开屏页 Activity（真机快照 `files/snapshots/1791906264682.json` 实测）。
 *
 * 注意：快照里的 Activity 名依赖 `ActivityNames` 过滤 widget 类事件——修复前它会被 Toast 冲成
 * `android.widget.TextView`，按错误界面名写规则会一直不生效。
 */
private const val QQ_READER_SPLASH_ACTIVITY = "com.qq.reader.activity.launch.DefaultAliasSplashActivity"

/**
 * QQ阅读跳过按钮：`splash_root` 内的短文本叶子节点。
 *
 * 真机快照结构（2026-10-13）：`RelativeLayout[vid=splash_root] > TextView[text="跳过5"]`
 * （189x84、clickable=true、childCount=0）；广告内容在
 * `splash_root > splash_container > RelativeLayout[vid=ywad_gdt_splash_container]`
 * （优量汇 SDK 容器，整屏且不可点——绝不能点它，面积守卫也会丢弃）。
 */
private const val QQ_READER_SKIP_TEXT =
    "[vid=\"splash_root\"] > TextView[text^=\"跳过\"][text.length<10][visibleToUser=true]"

internal fun appRules(matchTimeMs: Long): List<SkipRule> = listOf(
    appRule(
        id = "qq_reader_splash",
        app = "com.qq.reader",
        name = "QQ阅读",
        groups = listOf(
            splashGroup(
                matchTimeMs = matchTimeMs,
                activityIds = listOf(QQ_READER_SPLASH_ACTIVITY),
                rules = listOf(rule(0, "splash_root 内的跳过文本", QQ_READER_SKIP_TEXT))
            )
        )
    ),
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
) + wechatWxaRule(matchTimeMs)
