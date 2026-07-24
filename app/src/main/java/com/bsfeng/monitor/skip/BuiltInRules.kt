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

    /**
     * 内置规则版本号。
     * 每次增删改内置规则时 +1，SkipRuleManager 据此判断是否需要同步。
     */
    const val BUILT_IN_VERSION = 6

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
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
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
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
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
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
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
                    matchDelay = 300, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
                        "[desc*=\"关闭\"]",
                        "[text*=\"知道了\"]",
                        "[clickable=true][desc*=\"关闭\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[id*=\"skip\"]"
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
        ),

        // ===== 微信 =====
        SkipRule(
            id = "wechat_splash",
            app = "com.tencent.mm",
            name = "微信",
            groups = listOf(
                RuleGroup(
                    name = "开屏广告",
                    activityIds = listOf("LauncherUI"),
                    rules = listOf(MatchRule(
                        matchDelay = 300, actionCd = 60000, actionMaximum = 1,
                        matches = listOf(
                            "[id$=\"tt_splash_skip_btn\"]",
                            "[text*=\"跳过\"]",
                            "[vid*=\"skip\"]"
                        )
                    ))
                ),
                RuleGroup(
                    name = "小程序广告",
                    activityIds = listOf("appbrand"),
                    rules = listOf(
                        MatchRule(
                            matchDelay = 2000, actionCd = 60000, actionMaximum = 3,
                            matches = listOf(
                                "[desc*=\"关闭\"]",
                                "[text*=\"跳过\"]",
                                "[clickable=true][text*=\"跳过\"]",
                                "[id$=\"tt_video_ad_close\"]",
                                "[text*=\"关闭广告\"]",
                                "[vid*=\"close\"]"
                            )
                        )
                    )
                )
            )
        ),

        // ===== 抖音 =====
        SkipRule(
            id = "douyin_splash",
            app = "com.ss.android.ugc.aweme",
            name = "抖音",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 支付宝 =====
        SkipRule(
            id = "alipay_splash",
            app = "com.eg.android.AlipayGphone",
            name = "支付宝",
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

        // ===== QQ =====
        SkipRule(
            id = "qq_splash",
            app = "com.tencent.mobileqq",
            name = "QQ",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[text*=\"跳过\"]"
                    )
                ))
            ))
        ),

        // ===== 今日头条 =====
        SkipRule(
            id = "toutiao_splash",
            app = "com.ss.android.article.news",
            name = "今日头条",
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

        // ===== 百度地图 =====
        SkipRule(
            id = "baidumap_splash",
            app = "com.baidu.BaiduMap",
            name = "百度地图",
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

        // ===== 芒果TV =====
        SkipRule(
            id = "mgtv_splash",
            app = "com.hunantv.imgo.activity",
            name = "芒果TV",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]",
                        "[vid*=\"skip\"]"
                    )
                ))
            ))
        ),

        // ===== 钉钉 =====
        SkipRule(
            id = "dingtalk_splash",
            app = "com.alibaba.android.rimet",
            name = "钉钉",
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

        // ===== 企业微信 =====
        SkipRule(
            id = "wecom_splash",
            app = "com.tencent.wework",
            name = "企业微信",
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

        // ===== 腾讯会议 =====
        SkipRule(
            id = "tencentmeeting_splash",
            app = "com.tencent.wemeet.app",
            name = "腾讯会议",
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

        // ===== 携程旅行 =====
        SkipRule(
            id = "ctrip_splash",
            app = "com.ctrip.android.view",
            name = "携程旅行",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 夸克 =====
        SkipRule(
            id = "quark_splash",
            app = "com.quark.browser",
            name = "夸克",
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

        // ===== 百度网盘 =====
        SkipRule(
            id = "baidupan_splash",
            app = "com.baidu.netdisk",
            name = "百度网盘",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== WPS Office =====
        SkipRule(
            id = "wps_splash",
            app = "cn.wps.moffice_eng",
            name = "WPS Office",
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

        // ===== 美团外卖 =====
        SkipRule(
            id = "meituanwaimai_splash",
            app = "com.sankuai.meituan.takeoutnew",
            name = "美团外卖",
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

        // ===== 盒马 =====
        SkipRule(
            id = "hima_splash",
            app = "com.wudaokou.hippo",
            name = "盒马",
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

        // ===== 闲鱼 =====
        SkipRule(
            id = "xianyu_splash",
            app = "com.taobao.idlefish",
            name = "闲鱼",
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

        // ===== 云闪付 =====
        SkipRule(
            id = "unionpay_splash",
            app = "com.unionpay",
            name = "云闪付",
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

        // ===== 扫描全能王 =====
        SkipRule(
            id = "camscanner_splash",
            app = "com.intsig.camscanner",
            name = "扫描全能王",
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

        // ===== 菜鸟 =====
        SkipRule(
            id = "cainiao_splash",
            app = "com.cainiao.wireless",
            name = "菜鸟",
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

        // ===== 唯品会 =====
        SkipRule(
            id = "vipshop_splash",
            app = "com.achievo.vipshop",
            name = "唯品会",
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

        // ===== 微信读书 =====
        SkipRule(
            id = "weread_splash",
            app = "com.tencent.weread",
            name = "微信读书",
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

        // ===== Keep =====
        SkipRule(
            id = "keep_splash",
            app = "com.gotokeep.keep",
            name = "Keep",
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

        // ===== 喜马拉雅 =====
        SkipRule(
            id = "ximalaya_splash",
            app = "com.ximalaya.ting.android",
            name = "喜马拉雅",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 虎牙直播 =====
        SkipRule(
            id = "huya_splash",
            app = "com.duowan.kiwi",
            name = "虎牙直播",
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

        // ===== 斗鱼 =====
        SkipRule(
            id = "douyu_splash",
            app = "air.tv.douyu.android",
            name = "斗鱼",
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

        // ===== 剪映 =====
        SkipRule(
            id = "jianying_splash",
            app = "com.lemon.lv",
            name = "剪映",
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

        // ===== 全民K歌 =====
        SkipRule(
            id = "quanminkge_splash",
            app = "com.tencent.karaoke",
            name = "全民K歌",
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

        // ===== 铁路12306 =====
        SkipRule(
            id = "12306_splash",
            app = "com.MobileTicket",
            name = "铁路12306",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 500, actionCd = 60000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 番茄免费小说 =====
        SkipRule(
            id = "fanqie_splash",
            app = "com.dragon.read",
            name = "番茄免费小说",
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

        // ===== 七猫免费小说 =====
        SkipRule(
            id = "qimao_splash",
            app = "com.kmxs.reader",
            name = "七猫免费小说",
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

        // ===== 起点读书 =====
        SkipRule(
            id = "qidian_splash",
            app = "com.qidian.QDReader",
            name = "起点读书",
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

        // ===== 美图秀秀 =====
        SkipRule(
            id = "meitu_splash",
            app = "com.mt.mtxx.mtxx",
            name = "美图秀秀",
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

        // ===== 飞猪 =====
        SkipRule(
            id = "feizhu_splash",
            app = "com.taobao.trip",
            name = "飞猪",
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

        // ===== 腾讯地图 =====
        SkipRule(
            id = "tencentmap_splash",
            app = "com.tencent.map",
            name = "腾讯地图",
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

        // ===== 汽水音乐 =====
        SkipRule(
            id = "qishuiyinyue_splash",
            app = "com.luna.music",
            name = "汽水音乐",
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

        // ===== 网易新闻 =====
        SkipRule(
            id = "wangyixinwen_splash",
            app = "com.netease.newsreader.activity",
            name = "网易新闻",
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

        // ===== 腾讯新闻 =====
        SkipRule(
            id = "tencentnews_splash",
            app = "com.tencent.news",
            name = "腾讯新闻",
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

        // ===== 掌上生活 =====
        SkipRule(
            id = "cmbchina_splash",
            app = "cmbchina.ccd.pluto.cmbActivity",
            name = "掌上生活",
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

        // ===== 哈啰 =====
        SkipRule(
            id = "hellobike_splash",
            app = "com.jingyao.easybike",
            name = "哈啰",
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

        // ===== 中国移动 =====
        SkipRule(
            id = "chinamobile_splash",
            app = "com.greenpoint.android.mc10086.activity",
            name = "中国移动",
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

        // ===== 中国电信 =====
        SkipRule(
            id = "chinatelecom_splash",
            app = "com.ct.client",
            name = "中国电信",
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

        // ===== 网上国网 =====
        SkipRule(
            id = "sgcc_splash",
            app = "com.sgcc.wsgw.cn",
            name = "网上国网",
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

        // ===== 有道词典 =====
        SkipRule(
            id = "youdao_splash",
            app = "com.youdao.dict",
            name = "有道词典",
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

        // ===== 平安好车主 =====
        SkipRule(
            id = "pingan_splash",
            app = "com.pingan.carowner",
            name = "平安好车主",
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

        // ===== 豆包 =====
        SkipRule(
            id = "doubao_splash",
            app = "com.larus.nova",
            name = "豆包",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 300, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== Kimi智能助手 =====
        SkipRule(
            id = "kimi_splash",
            app = "com.moonshot.kimichat",
            name = "Kimi智能助手",
            groups = listOf(RuleGroup(
                name = "开屏广告",
                rules = listOf(MatchRule(
                    matchDelay = 300, actionCd = 30000, actionMaximum = 1,
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[id$=\"tt_splash_skip_btn\"]"
                    )
                ))
            ))
        ),

        // ===== 陌陌 =====
        SkipRule(
            id = "momo_splash",
            app = "com.immomo.momo",
            name = "陌陌",
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

        // ===== 番茄畅听 =====
        SkipRule(
            id = "fanqiechangting_splash",
            app = "com.xs.fm",
            name = "番茄畅听",
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
