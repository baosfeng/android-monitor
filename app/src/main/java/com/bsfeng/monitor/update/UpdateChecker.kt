package com.bsfeng.monitor.update

import com.bsfeng.monitor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateCheckResult {
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult
    data class UpToDate(val currentVersionName: String) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 更新检测：按「自建源 → jsDelivr → GitHub 镜像 → GitHub 直连」顺序尝试清单，
 * 任意一个源解析成功即返回，避免单点被墙导致完全不可用。
 */
class UpdateChecker(
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val fetch: (String) -> String? = ::httpGet,
) {
    fun sources(): List<String> = buildList {
        val server = UpdateConfig.serverBaseUrl.trimEnd('/')
        if (server.isNotEmpty()) add("$server/version.json")
        add("https://cdn.jsdelivr.net/gh/${UpdateConfig.repo}@main/updates/version.json")
        UpdateConfig.mirrorPrefixes.forEach { prefix ->
            add(prefix + "https://raw.githubusercontent.com/${UpdateConfig.repo}/main/updates/version.json")
        }
        add("https://raw.githubusercontent.com/${UpdateConfig.repo}/main/updates/version.json")
    }

    fun check(): UpdateCheckResult {
        for (url in sources()) {
            val body = fetch(url) ?: continue
            val manifest = UpdateManifest.parse(body) ?: continue
            return if (manifest.versionCode > currentVersionCode) {
                UpdateCheckResult.Available(manifest)
            } else {
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        }
        return UpdateCheckResult.Failed("所有更新源均不可用")
    }

    fun downloadSources(manifest: UpdateManifest): List<String> = buildList {
        val server = UpdateConfig.serverBaseUrl.trimEnd('/')
        if (server.isNotEmpty()) add("$server/app-release.apk")
        if (manifest.apkUrl.startsWith("https://github.com/")) {
            UpdateConfig.mirrorPrefixes.forEach { prefix -> add(prefix + manifest.apkUrl) }
        }
        add(manifest.apkUrl)
    }

    private companion object {
        fun httpGet(url: String): String? = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 10_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "android-monitor-updater")
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
