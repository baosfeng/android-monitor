package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：定位采样的纯数据类型（无 Android 依赖，可在纯 JVM 单测里造）。

/**
 * 一次位置采样（纯数据）。
 *
 * 不直接用 `android.location.Location` 是为了让过滤规则能在纯 JVM 单测里跑
 * （Robolectric 沙箱能造 Location，但纯数据类更快、更稳）。
 */
data class LocationSample(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMs: Long,
)

/**
 * 主动定位的预算档位（射频开启时长的上限分摊到各 provider）。
 *
 * - [FULL]：满预算（network 5 秒 / gps 15 秒）。**必须真的拿到「现在在哪」**的路径用它：
 *   MOVING 静默补采、断档自愈、首点兜底、门禁未知时的心跳；
 * - [PROBE]：覆盖级预算（network 4 秒 / gps 8 秒）。只给**门禁探测**用（PROBING 低频探测 +
 *   SLEEPING 30 分钟兜底）：室内两个 provider 常常双双超时，满预算等于每轮白烧 20 秒射频。
 *
 * 为什么按档位而不是统一缩短：GPS 冷启动要 10–30 秒才出首点，满预算的那几条路径缩短就会
 * 重新变成「掉线」（第一版把 2500ms 均分给 GPS 就是这么坏的）。
 */
enum class FixBudget {
    FULL,
    PROBE,
}

/** 定位权限四态（设置页自检展示用）。 */
enum class LocationPermissionStatus {
    /** 前台与后台定位都已授权，后台也能采到点。 */
    GRANTED,

    /** 只有前台定位授权：息屏/切后台后取不到点。 */
    MISSING_BACKGROUND,

    /**
     * 只授予了「大致位置」（ACCESS_COARSE_LOCATION），没有精确位置。
     *
     * Android 12+ 用户可以在权限弹窗里只选「大致位置」，系统因此只派基站级点
     * （network 常见 500~3000m）。这些点过不了 [LocationQuality] 的精度闸，
     * 会被整批丢弃 —— 所以这一态**绝不能**显示「已就绪」，必须引导用户改成精确位置。
     */
    COARSE_ONLY,

    /** 完全未授权。 */
    NOT_GRANTED,
}
