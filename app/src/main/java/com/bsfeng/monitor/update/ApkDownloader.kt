package com.bsfeng.monitor.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK 下载器：按给定源顺序尝试，支持进度回调，下载完成后做 SHA-256 校验。
 * 任何一步失败都会删除临时文件并切换到下一个源。
 */
class ApkDownloader {
    fun download(
        sources: List<String>,
        destDir: File,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        destDir.mkdirs()
        var lastError: Exception = Exception("所有下载源均不可用")
        for (url in sources) {
            val tmp = File(destDir, "update-${System.currentTimeMillis()}.apk")
            try {
                if (!downloadOne(url, tmp, onProgress)) {
                    tmp.delete()
                    continue
                }
                if (expectedSha256.isNotBlank() && !sha256(tmp).equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    lastError = SecurityException("APK 校验失败")
                    continue
                }
                return Result.success(tmp)
            } catch (e: Exception) {
                tmp.delete()
                lastError = e
            }
        }
        return Result.failure(lastError)
    }

    private fun downloadOne(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "android-monitor-updater")
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
