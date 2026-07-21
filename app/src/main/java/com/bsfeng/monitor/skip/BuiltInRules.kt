package com.bsfeng.monitor.skip

import org.json.JSONArray

/**
 * 内置开屏跳过规则库。
 *
 * 覆盖常用 App 的开屏广告跳过规则。
 * 规则基于文本匹配（如"跳过"、"关闭"、"广告"等关键词），兼容性强。
 *
 * ## 添加新规则
 * 每个 App 的规则结构：
 * ```
 * {
 *   id: "唯一标识",
 *   app: "包名",
 *   name: "App名称",
 *   enabled: true,
 *   groups: [{
 *     name: "开屏广告",
 *     activityIds: ["限定的Activity（可选）"],
 *     rules: [{
 *       matches: ["选择器表达式"],
 *       action: "click"
 *     }]
 *   }]
 * }
 * ```
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
                    matches = listOf(
                        "TextView[id=\"com.zhihu.android:id/btn_skip\"]",
                        "[text*=\"跳过\"]",
                        "[text*=\"广告\"] + [text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭广告\"]",
                        "[text*=\"跳过广告\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]",
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
                    matches = listOf(
                        "TextView[id=\"com.sina.weibo:id/splash_skip_btn\"]",
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"广告\"]"
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
                    matches = listOf(
                        "TextView[id=\"tv.danmaku.bili:id/btn_skip\"]",
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"跳过广告\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]"
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
                    matches = listOf(
                        "[text*=\"跳过\"]",
                        "[text*=\"关闭\"]"
                    )
                ))
            ))
        )
    )
}
