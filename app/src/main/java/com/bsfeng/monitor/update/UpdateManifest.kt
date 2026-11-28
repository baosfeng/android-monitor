package com.bsfeng.monitor.update

import org.json.JSONObject

/**
 * 单个历史版本的发布记录（清单里的 `releases[]`）。
 *
 * 为什么需要：用户可能跨好几个版本才更新（1.2.0 → 1.5.0），只显示最新一版的记录，
 * 中间版本的改动就丢了。发布流程把「上一 tag → 该 tag」的提交按版本打进来，
 * 客户端按 `versionCode > 当前` 过滤后分段展示。
 */
data class UpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val publishedAt: String,
)

/** 版本清单（updates/version.json），由发布流程生成。 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val changelog: String,
    val publishedAt: String,
    /** 最近若干个历史版本（新版在前）；旧清单没有该字段时为空，客户端回落到单版本。 */
    val releases: List<UpdateRelease> = emptyList(),
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
                releases = parseReleases(obj),
            )
        }.getOrNull()?.takeIf { it.versionCode > 0 && it.versionName.isNotBlank() && it.apkUrl.isNotBlank() }

        /** `releases[]` 里字段不全的条目直接跳过：宁可少一段，也不要一段空白记录。 */
        private fun parseReleases(obj: JSONObject): List<UpdateRelease> {
            val array = obj.optJSONArray("releases") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val code = item.optInt("versionCode", 0)
                val name = item.optString("versionName")
                if (code <= 0 || name.isBlank()) return@mapNotNull null
                UpdateRelease(
                    versionCode = code,
                    versionName = name,
                    changelog = item.optString("changelog"),
                    publishedAt = item.optString("publishedAt"),
                )
            }
        }
    }
}
