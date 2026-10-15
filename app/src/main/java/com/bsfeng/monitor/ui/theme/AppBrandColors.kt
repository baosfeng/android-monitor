package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 应用品牌色（与原型 .open-design/08-prototype.html 里行内 style="--app:#xxxxxx" 的取值一致）。
 *
 * 这是**数据**而不是设计 token：原型把每个应用的品牌色直接写在行内 style 上，
 * 不参与 :root / [data-theme] 的 token 生成，因此集中定义在此处（theme 包是
 * checkUiStyle 允许写色值的唯一位置），业务代码只按下标取用。
 *
 * 用途：应用图标取不到真实图标时的兜底底色 —— 对应原型 .app-icon 的
 * background:var(--app,var(--fg-2))，表外应用由调用方回落中性色。
 */
internal object AppBrandColors {

    /** 包名 → 品牌色（原型 s02 / s03 / s10 / s11 中出现的 11 个应用）。 */
    private val BY_PACKAGE: Map<String, Color> = mapOf(
        "com.tencent.mm" to Color(0xFF07C160),              // 微信
        "tv.danmaku.bili" to Color(0xFFFB7299),             // 哔哩哔哩
        "com.taobao.taobao" to Color(0xFFFF5000),           // 淘宝
        "com.autonavi.minimap" to Color(0xFF00A6FB),        // 高德地图
        "com.zhihu.android" to Color(0xFF0084FF),           // 知乎
        "com.netease.cloudmusic" to Color(0xFFC20C0C),      // 网易云音乐
        "com.eg.android.AlipayGphone" to Color(0xFF1677FF), // 支付宝
        "com.jingdong.app.mall" to Color(0xFFE1251B),       // 京东
        "com.sina.weibo" to Color(0xFFE6162D),              // 微博
        "com.xingin.xhs" to Color(0xFFFF2442),              // 小红书
        "com.ss.android.ugc.aweme" to Color(0xFF161823),    // 抖音
    )

    /** 品牌色；未登记的包名返回 null，由调用方回落中性色（对应 prototype 的 var(--app) 兜底）。 */
    fun colorOf(packageName: String?): Color? = packageName?.let { BY_PACKAGE[it] }
}
