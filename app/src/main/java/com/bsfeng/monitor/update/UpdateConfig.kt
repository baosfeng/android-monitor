package com.bsfeng.monitor.update

import com.bsfeng.monitor.BuildConfig

/**
 * 更新检测配置。
 *
 * - 私有构建（local.properties 注入 updateBaseUrl）优先走自建服务器；
 * - 公开构建不含任何服务器地址，只走 jsDelivr 与 GitHub 镜像，避免泄露源站 IP。
 */
object UpdateConfig {
    val serverBaseUrl: String = BuildConfig.UPDATE_BASE_URL.trim()
    val repo: String = BuildConfig.UPDATE_REPO

    /** GitHub 无法直连时按顺序尝试的镜像前缀。 */
    val mirrorPrefixes: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )
}
