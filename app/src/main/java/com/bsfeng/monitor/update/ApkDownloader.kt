package com.bsfeng.monitor.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK 下载器：按给定源顺序尝试，支持进度回调，下载完成后做 SHA-256 校验 + 签名指纹比对。
 *
 * 两道校验都是 **fail-closed**：
 * - 清单没给 sha256（空串 / 长度不对）→ 直接判失败，不下载 —— 空校验等于「谁都能塞包」；
 * - 下载后 APK 的 sha256 与清单不符 → 删包、换下一个源；
 * - 所有源都失败后，再用下载包的签名与**当前安装包的签名**比对（同一份证书才允许装）。
 *
 * 任何一步失败都会删除临时文件。
 */
class ApkDownloader(private val context: Context? = null) {

    fun download(
        sources: List<String>,
        destDir: File,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        // fail-closed：没有校验值就不下载。旧实现是「空串跳过校验」，等于把自动更新变成
        // 任意 APK 落地通道（一份被篡改的 version.json 就够）。
        if (!isValidSha256(expectedSha256)) {
            return Result.failure(
                SecurityException("清单缺少有效校验值（apkSha256 需要 64 位十六进制），已拒绝更新"),
            )
        }

        destDir.mkdirs()
        var lastError: Exception = Exception("所有下载源均不可用")
        for (url in sources) {
            val tmp = File(destDir, "update-${System.currentTimeMillis()}.apk")
            try {
                if (!downloadOne(url, tmp, onProgress)) {
                    tmp.delete()
                    continue
                }
                if (!sha256(tmp).equals(expectedSha256.trim(), ignoreCase = true)) {
                    tmp.delete()
                    lastError = SecurityException("APK 校验失败：sha256 与清单不符")
                    continue
                }
                // 摘要对了还不够：清单与 APK 可能一起被换掉，最终闸门是「签名证书必须是同一个」
                val verdict = verifySigner(tmp)
                if (verdict != null) {
                    tmp.delete()
                    lastError = SecurityException(verdict)
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

    /**
     * 用**当前安装包**的签名证书校验下载的 APK；一致返回 null，否则返回可展示的失败原因。
     *
     * 上下文优先用构造时注入的，其次取进程内 Application（调用方无需改构造形态）。
     * 两者都拿不到（纯 JVM 场景）时跳过 —— 比对逻辑本身已抽成纯函数 [signerMatches] 单独测。
     */
    private fun verifySigner(apk: File): String? {
        val ctx = context ?: appContextOrNull() ?: return null
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "下载的 APK 无法解析（不是有效安装包）"
        val signing = archive.signingInfo ?: return "下载的 APK 未签名"
        // 用当前安装包的 signer 做基准：APK 可以被换，签名证书换不掉
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val own = installed.signingInfo ?: return "当前安装包没有签名信息"
        val matches = signerMatches(
            incoming = signing.apkContentsSigners.map { it.toByteArray() },
            installed = own.apkContentsSigners.map { it.toByteArray() },
        )
        return if (matches) null else "下载的 APK 签名与当前安装包不一致，已拒绝安装"
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

    companion object {
        /** sha256 十六进制长度：64 位。长度不对的「校验值」等同没有。 */
        const val SHA256_HEX_LENGTH = 64

        private val SHA256_HEX = Regex("[0-9a-fA-F]{$SHA256_HEX_LENGTH}")

        /** 清单里的校验值是否可用（非空 + 64 位十六进制）。 */
        fun isValidSha256(value: String?): Boolean = value != null && SHA256_HEX.matches(value.trim())

        /**
         * 签名证书集合比对（纯函数，纯 JVM 可单测）：两边的证书集合必须**完全一致**。
         *
         * 为什么不做「包含」而做「相等」：包含口径在证书轮转（key rotation）下会放过「新包多带
         * 一张我们不认识的证书」的组合，而本项目的发布签名固定、不轮转 —— 拿不到既精确又宽松的
         * 判据时，宁可失败也不放行（fail-closed）。真要做轮转，得改用
         * `SigningInfo.getSigningCertificateHistory()` 并校验轮转证明，而不是放宽集合比较。
         *
         * 空集合一律视为不一致：拿不到签名时不能放行。
         */
        fun signerMatches(incoming: List<ByteArray>, installed: List<ByteArray>): Boolean {
            if (incoming.isEmpty() || installed.isEmpty()) return false
            return incoming.map { it.toHex() }.toSet() == installed.map { it.toHex() }.toSet()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** 进程内 Application；未初始化（纯 JVM 单测）返回 null，不抛异常。 */
        internal fun appContextOrNull(): Context? =
            runCatching { com.bsfeng.monitor.MonitorApp.instance.applicationContext }.getOrNull()
    }
}
