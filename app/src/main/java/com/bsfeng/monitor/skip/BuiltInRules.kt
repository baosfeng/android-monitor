package com.bsfeng.monitor.skip

import org.json.JSONArray

/**
 * 内置开屏跳过规则库。
 *
 * 覆盖常用 App 的开屏广告跳过规则。
 * 规则数据参考自 GKD (gkd-kit) 开源订阅规则库。
 *
 * ## 匹配策略（优先级从高到低）
 * 1. 精确 ID 匹配（如 `[id="com.xxx:id/btn_skip"]`）— 最快最准
 * 2. 全局文本匹配（如 `[text*="跳过"]`）— 通用兜底
 * 3. 腾讯广告 SDK ID 匹配（如 `[id$="tt_splash_skip_btn"]`）— 覆盖大量 App
 */
object BuiltInRules {

    /** 获取所有内置规则（JSON 格式） */
    fun getRulesJson(): String = JSONArray(rules.map { it.toJson() }).toString(2)

    /** 获取所有内置规则（对象列表） */
    fun getRules(): List<SkipRule> = rules

    /** 按包名查找内置规则 */
    fun findByPackage(packageName: String): SkipRule? = rules.find { it.app == packageName }

    /** 获取所有包名列表 */
    fun getAllPackageNames(): List<String> = rules.map { it.app }

    private val rules: List<SkipRule> = listOf(

        // ===== 知乎 =====
        SkipRule(
            id = "zhihu_splash",
            app = "com.zhihu.android",
            name = "知乎",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                activityIds = listOf("com.zhihu.android.ui.activity.MainActivity"),
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "TextView[id=\"com.zhihu.android:id/btn_skip\"]",
                        "[text*=\"跳过\"][text.length<10][visibleToUser=true]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 京东 =====
        SkipRule(
            id = "jd_splash",
            app = "com.jingdong.app.mall",
            name = "京东",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 800, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭广告\"]",
                        "[text*=\"跳过广告\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 淘宝 =====
        SkipRule(
            id = "taobao_splash",
            app = "com.taobao.taobao",
            name = "淘宝",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 800, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]",
                        "[vid*=\"Skip\"]",
                        "[text=\"0\"] + [text*=\"跳过\"]"
                    )
                ))
            ))
        ),

        // ===== 微博 =====
        SkipRule(
            id = "weibo_splash",
            app = "com.sina.weibo",
            name = "微博",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "TextView[id=\"com.sina.weibo:id/splash_skip_btn\"]",
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 小红书 =====
        SkipRule(
            id = "xhs_splash",
            app = "com.xingin.xhs",
            name = "小红书",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 哔哩哔哩 =====
        SkipRule(
            id = "bilibili_splash",
            app = "tv.danmaku.bili",
            name = "哔哩哔哩",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                activityIds = listOf("tv.danmaku.bili.MainActivityV2"),
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "TextView[id=\"tv.danmaku.bili:id/btn_skip\"]",
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 百度 =====
        SkipRule(
            id = "baidu_splash",
            app = "com.baidu.searchbox",
            name = "百度",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"跳过广告\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 美团 =====
        SkipRule(
            id = "meituan_splash",
            app = "com.sankuai.meituan",
            name = "美团",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 拼多多 =====
        SkipRule(
            id = "pdd_splash",
            app = "com.xunmeng.pinduoduo",
            name = "拼多多",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 800, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== QQ 音乐 =====
        SkipRule(
            id = "qqmusic_splash",
            app = "com.tencent.qqmusic",
            name = "QQ音乐",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 网易云音乐 =====
        SkipRule(
            id = "neteasemusic_splash",
            app = "com.netease.cloudmusic",
            name = "网易云音乐",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 腾讯视频 =====
        SkipRule(
            id = "tencentvideo_splash",
            app = "com.tencent.qqlive",
            name = "腾讯视频",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 爱奇艺 =====
        SkipRule(
            id = "iqiyi_splash",
            app = "com.qiyi.video",
            name = "爱奇艺",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 优酷 =====
        SkipRule(
            id = "youku_splash",
            app = "com.youku.phone",
            name = "优酷",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 豆瓣 =====
        SkipRule(
            id = "douban_splash",
            app = "com.douban.frodo",
            name = "豆瓣",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 饿了么 =====
        SkipRule(
            id = "eleme_splash",
            app = "me.ele",
            name = "饿了么",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 高德地图 =====
        SkipRule(
            id = "amap_splash",
            app = "com.autonavi.minimap",
            name = "高德地图",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 滴滴出行 =====
        SkipRule(
            id = "didiglobal_splash",
            app = "com.sdu.didi.psnger",
            name = "滴滴出行",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 快手 =====
        SkipRule(
            id = "kuaishou_splash",
            app = "com.smile.gifmaker",
            name = "快手",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 大众点评 =====
        SkipRule(
            id = "dianping_splash",
            app = "com.dianping.v1",
            name = "大众点评",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 懂车帝 =====
        SkipRule(
            id = "dongchedi_splash",
            app = "com.bytedance.auto",
            name = "懂车帝",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 百度贴吧（新增） =====
        SkipRule(
            id = "tieba_splash",
            app = "com.baidu.tieba",
            name = "百度贴吧",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 酷安（新增） =====
        SkipRule(
            id = "coolapk_splash",
            app = "com.coolapk.market",
            name = "酷安",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== QQ 浏览器（新增） =====
        SkipRule(
            id = "qqbrowser_splash",
            app = "com.tencent.mtt",
            name = "QQ浏览器",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== QQ 邮箱（新增） =====
        SkipRule(
            id = "qqmail_splash",
            app = "com.tencent.androidqqmail",
            name = "QQ邮箱",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== UC 浏览器（新增） =====
        SkipRule(
            id = "ucbrowser_splash",
            app = "com.UCMobile",
            name = "UC浏览器",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 大麦（新增） =====
        SkipRule(
            id = "damai_splash",
            app = "cn.damai",
            name = "大麦",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 华为浏览器（新增） =====
        SkipRule(
            id = "huaw_browser_splash",
            app = "com.huawei.browser",
            name = "华为浏览器",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 华为视频（新增） =====
        SkipRule(
            id = "huaw_video_splash",
            app = "com.huawei.himovie",
            name = "华为视频",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 华为天气（新增） =====
        SkipRule(
            id = "huaw_weather_splash",
            app = "com.huawei.android.totemweather",
            name = "华为天气",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 华为运动健康（新增） =====
        SkipRule(
            id = "huaw_health_splash",
            app = "com.huawei.health",
            name = "华为运动健康",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 华为音乐（新增） =====
        SkipRule(
            id = "huaw_music_splash",
            app = "com.huawei.music",
            name = "华为音乐",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 驾考宝典（新增） =====
        SkipRule(
            id = "jiakao_splash",
            app = "com.handsgo.jiakao.android",
            name = "驾考宝典",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== Soul（新增） =====
        SkipRule(
            id = "soul_splash",
            app = "cn.soulapp.android",
            name = "Soul",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== TapTap（新增） =====
        SkipRule(
            id = "taptap_splash",
            app = "com.taptap",
            name = "TapTap",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 网易邮箱（新增） =====
        SkipRule(
            id = "netease_mail_splash",
            app = "com.netease.mobimail",
            name = "网易邮箱",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 酷狗音乐（新增） =====
        SkipRule(
            id = "kugou_splash",
            app = "com.kugou.android",
            name = "酷狗音乐",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 山姆会员商店（新增） =====
        SkipRule(
            id = "samsclub_splash",
            app = "cn.samsclub.app",
            name = "山姆会员商店",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 123云盘（新增） =====
        SkipRule(
            id = "123pan_splash",
            app = "com.123cloud.www",
            name = "123云盘",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 蚂蚁财富（新增） =====
        SkipRule(
            id = "antfortune_splash",
            app = "com.antfortune.wealth",
            name = "蚂蚁财富",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 淘特（新增） =====
        SkipRule(
            id = "taote_splash",
            app = "com.taobao.litetao",
            name = "淘特",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 腾讯文档（新增） =====
        SkipRule(
            id = "tencent_docs_splash",
            app = "com.tencent.docs",
            name = "腾讯文档",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 腾讯微云（新增） =====
        SkipRule(
            id = "tencent_weiyun_splash",
            app = "com.qq.qcloud",
            name = "腾讯微云",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== ECOVACS HOME（新增） =====
        SkipRule(
            id = "ecovacs_splash",
            app = "com.eco.global.app",
            name = "ECOVACS HOME",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "ImageView[id=\"com.eco.global.app:id/adview_jump\"]",
                        "[text*=\"跳过\"]"
                    )
                ))
            ))
        ),

        // ===== 小蚕霸王餐（新增） =====
        SkipRule(
            id = "xiaocan_splash",
            app = "com.realtech.xiaocan",
            name = "小蚕霸王餐",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 亲邻开门（新增） =====
        SkipRule(
            id = "qinlinkm_splash",
            app = "com.qiekj.user",
            name = "亲邻开门",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 智谱（新增） =====
        SkipRule(
            id = "zhipu_splash",
            app = "com.crirp.zhipu",
            name = "智谱",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 网易邮箱大师（新增） =====
        SkipRule(
            id = "netease_mailmaster_splash",
            app = "com.netease.mail",
            name = "网易邮箱大师",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 网速管家（新增） =====
        SkipRule(
            id = "speedtest_splash",
            app = "cn.lezhi.speedtest",
            name = "网速管家",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 小恩爱（新增） =====
        SkipRule(
            id = "xiaoenai_splash",
            app = "com.xiaoenai.app",
            name = "小恩爱",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        )
    )
}
