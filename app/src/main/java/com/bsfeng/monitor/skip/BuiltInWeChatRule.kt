package com.bsfeng.monitor.skip

// 来源：原 skip/BuiltInRules.kt 的微信小程序开屏规则；职责：微信小程序开屏广告的 Activity 圈定与顺序匹配规则。

import com.bsfeng.monitor.domain.model.SkipRule

/**
 * 微信小程序宿主的 Activity（前缀匹配，`.` 开头由引擎自动补包名）。
 *
 * 微信主界面、聊天页的 Activity 都不在此列——这就是小程序规则「不进 GLOBAL_EXCLUDED_PACKAGES
 * 也不会误点主界面跳过」的依据：误触范围由界面圈定，而不是靠整包排除（整包排除会让小程序也不生效）。
 */
private val WECHAT_WXA_ACTIVITY_IDS = listOf(
    /** 小程序标准宿主，开屏广告的常规落点 */
    ".plugin.appbrand.ui.AppBrandUI",
    /** 冷启动代理页：小程序进程未起时先经过它，广告在这一页就已开始渲染 */
    ".plugin.appbrand.launching.AppBrandLaunchProxyUI",
    /** 插件化小程序宿主（与标准宿主共用同一套广告 SDK） */
    ".plugin.appbrand.ui.AppBrandPluginUI"
)

/** 小程序开屏广告标识（简体/繁体）：命中它说明本页确实是广告页，不是普通弹窗 */
private const val WECHAT_WXA_AD_MARK = "[text=\"广告\"||text=\"廣告\"][visibleToUser=true]"

/** 小程序开屏跳过按钮（简体/繁体） */
private const val WECHAT_WXA_SKIP_BTN = "[text=\"跳过\"||text=\"跳過\"][visibleToUser=true]"

/**
 * 兜底：任意 TextView 里的短「跳过」文本。
 *
 * `height<150`（px）来自 GKD 现网规则：广告角标按钮在竖屏下高不过百来像素，
 * 用高度上限把整行文本 / 大按钮挡在外面。
 */
private const val WECHAT_WXA_SKIP_FALLBACK = "@TextView[text*=\"跳过\"][text.length<10][height<150]"

/**
 * 顺序匹配的确认延迟：广告标识先渲染、跳过按钮晚几百毫秒才可点。
 * 先等 800ms 再复核命中才点，避免点到广告容器或尚未就绪的占位节点。
 */
private const val WECHAT_WXA_ACTION_DELAY_MS = 800L

/** 兜底规则的重复点击冷却（比组默认 1000ms 短：广告页可能分多帧才把按钮画完） */
private const val WECHAT_WXA_FALLBACK_CD_MS = 500L

/**
 * 微信小程序开屏广告（GKD 订阅 Lin-arm/GKD_subscription v614 同参）。
 *
 * 与其它 App 规则的差别：微信**不加**进 [BuiltInRules.GLOBAL_EXCLUDED_PACKAGES]——那会让小程序规则一起失效；
 * 误触范围改由 [WECHAT_WXA_ACTIVITY_IDS] 圈定，只在小程序宿主里生效，主界面/聊天页不参与匹配。
 */
internal fun wechatWxaRule(matchTimeMs: Long): SkipRule = appRule(
    id = "wechat_wxa_splash",
    app = "com.tencent.mm",
    name = "微信",
    groups = listOf(
        splashGroup(
            matchTimeMs = matchTimeMs,
            activityIds = WECHAT_WXA_ACTIVITY_IDS,
            rules = listOf(
                // 广告标识 + 跳过按钮都出现才点：单凭"跳过"两字区分不出小程序自身的按钮。
                rule(
                    0, "广告标识后的跳过按钮",
                    WECHAT_WXA_AD_MARK,
                    WECHAT_WXA_SKIP_BTN,
                    actionDelayMs = WECHAT_WXA_ACTION_DELAY_MS
                ),
                // 兜底：广告标识没渲染出来（部分 SDK 只有角标）时的短文本跳过按钮。
                rule(
                    1, "兜底短文本跳过",
                    WECHAT_WXA_SKIP_FALLBACK,
                    actionCdMs = WECHAT_WXA_FALLBACK_CD_MS
                )
            )
        )
    )
)
