package com.bsfeng.monitor.update

import org.json.JSONObject

/** 版本清单（updates/version.json），由发布流程生成。 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val changelog: String,
    val publishedAt: String,
) {
    companion object {
        /** 解析清单；字段缺失或非法时返回 null。 */
        fun parse(json: String): UpdateManifest? = runCatching {
            val obj = JSONObject(json)
            UpdateManifest(
                versionCode = obj.optInt("versionCode", 0),
                versionName = obj.optString("versionName"),
                apkUrl = obj.optString("apkUrl"),
                apkSha256 = obj.optString("apkSha256"),
                changelog = obj.optString("changelog"),
                publishedAt = obj.optString("publishedAt"),
            )
        }.getOrNull()?.takeIf { it.versionCode > 0 && it.versionName.isNotBlank() && it.apkUrl.isNotBlank() }
    }
}
