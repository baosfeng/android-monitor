package com.bsfeng.monitor.totp

import com.bsfeng.monitor.domain.totp.OtpauthParseResult
import com.bsfeng.monitor.domain.totp.OtpauthUri
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpParams
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 验证器备份与导入 —— 加密文本导出 + Aegis / 2FAS 明文导入。
 *
 * 导出是可直接存文件的纯文本三行：
 * AMTOTP/1 / pbkdf2-sha256:210000 / base64(salt16 || iv12 || ciphertext)。
 * 载荷 JSON：version / app / createdAt / accounts[]。未知字段导入时忽略（向后兼容），
 * 未知 version 直接拒绝（不假装读得懂未来格式）。口令经 PBKDF2-HMAC-SHA256 派生 256 位密钥，
 * 载荷用 AES-256-GCM 加密 —— 与 Keystore 密钥无关，跨设备/重装后仍可恢复。
 */
class TotpBackup {

    /** 加密导出；口令不足 8 位、条目为空或任一账户参数不被 domain 接受都抛 [TotpBackupException]。 */
    fun export(
        entries: List<TotpBackupEntry>,
        passphrase: String,
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw TotpBackupException("导出密码至少 " + MIN_PASSPHRASE_LENGTH + " 位")
        }
        if (entries.isEmpty()) throw TotpBackupException("没有可导出的账户")
        val normalized = entries.mapIndexed { index, entry ->
            val (ok, reason) = normalize(entry)
            if (ok == null) throw TotpBackupException("第 " + (index + 1) + " 个账户无法导出：" + reason)
            ok
        }
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("app", APP_ID)
            put("createdAt", now)
            put("accounts", JSONArray().apply { normalized.forEach { put(entryToJson(it)) } })
        }.toString().toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val blob = salt + cipher.iv + cipher.doFinal(payload)
        val text = HEADER + "\n" + KDF_LABEL + ":" + PBKDF2_ITERATIONS + "\n" +
            Base64.getEncoder().encodeToString(blob) + "\n"
        return text.toByteArray(Charsets.UTF_8)
    }

    /** 解密导入本应用导出的备份；口令错 / 格式坏 / 版本不支持抛 [TotpBackupException]。 */
    fun import(data: ByteArray, passphrase: String): List<TotpBackupEntry> {
        if (data.size > MAX_BACKUP_BYTES) throw TotpBackupException("备份文件过大")
        val lines = data.toString(Charsets.UTF_8).trim().lines().map { it.trim() }
        if (lines.size < 3 || lines[0] != HEADER) throw TotpBackupException("不是本应用的验证器备份")
        val kdf = lines[1].split(':', limit = 2)
        if (kdf.size != 2 || kdf[0] != KDF_LABEL) throw TotpBackupException("不支持的密钥派生方式")
        val iterations = kdf[1].toIntOrNull()
            ?.takeIf { it in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS }
            ?: throw TotpBackupException("备份的密钥派生参数不合法")
        val blob = runCatching { Base64.getDecoder().decode(lines[2]) }
            .getOrElse { throw TotpBackupException("备份内容损坏", it) }
        if (blob.size < SALT_SIZE + IV_SIZE + TAG_BITS / 8) throw TotpBackupException("备份内容损坏")
        val salt = blob.copyOfRange(0, SALT_SIZE)
        val iv = blob.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val body = blob.copyOfRange(SALT_SIZE + IV_SIZE, blob.size)
        val key = deriveKey(passphrase, salt, iterations)
        val json = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body).toString(Charsets.UTF_8)
        }.getOrElse { throw TotpBackupException("口令不正确或备份已损坏", it) }
        val root = runCatching { JSONObject(json) }.getOrElse { throw TotpBackupException("备份内容损坏", it) }
        val version = root.optInt("version", 0)
        if (version !in 1..VERSION) throw TotpBackupException("不支持的备份版本：" + version)
        val accounts = root.optJSONArray("accounts") ?: throw TotpBackupException("备份缺少账户列表")
        return (0 until accounts.length()).map { index ->
            val obj = accounts.optJSONObject(index)
                ?: throw TotpBackupException("第 " + (index + 1) + " 个账户格式损坏")
            val entry = TotpBackupEntry(
                issuer = obj.optString("issuer"),
                account = obj.optString("account"),
                secret = obj.optString("secret"),
                algorithm = obj.optString("algorithm", TotpParams.ALGORITHM_SHA1),
                digits = obj.optInt("digits", TotpParams.DEFAULT_DIGITS),
                period = obj.optInt("period", TotpParams.DEFAULT_PERIOD_SEC),
                type = obj.optString("type", "TOTP"),
            )
            val (ok, reason) = normalize(entry)
            ok ?: throw TotpBackupException("第 " + (index + 1) + " 个账户无法导入：" + reason)
        }
    }

    /**
     * 导入第三方明文备份（Aegis / 2FAS），按 JSON 顶层字段自动识别。
     *
     * 只支持明文导出：Aegis 加密导出用 scrypt 多槽位（标准库无 scrypt），故意不支持并明确报错，
     * 而不是用错算法静默解出垃圾。
     */
    fun importExternal(text: String): TotpImportResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            throw TotpBackupException("备份文件过大")
        }
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw TotpBackupException("不是有效的 JSON 备份", it) }
        return when {
            root.has("entries") -> parseAegis(root)
            root.has("services") -> parse2Fas(root)
            else -> throw TotpBackupException("无法识别的备份格式（支持 Aegis 明文 / 2FAS 导出）")
        }
    }

    // MARK: - 第三方格式

    private fun parseAegis(root: JSONObject): TotpImportResult {
        val entries = root.optJSONArray("entries") ?: throw TotpBackupException("Aegis 备份缺少 entries")
        val accepted = mutableListOf<TotpBackupEntry>()
        val skipped = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i)
            if (obj == null) {
                skipped += "第 " + (i + 1) + " 条格式损坏"
                continue
            }
            val info = obj.optJSONObject("info")
            val name = obj.optString("name").trim()
            val rawType = obj.optString("type", "totp")
            val result = normalize(
                TotpBackupEntry(
                    issuer = obj.optString("issuer").trim(),
                    account = name,
                    secret = info?.optString("secret").orEmpty(),
                    algorithm = info?.optString("algo", TotpParams.ALGORITHM_SHA1).orEmpty(),
                    digits = info?.optInt("digits", TotpParams.DEFAULT_DIGITS) ?: TotpParams.DEFAULT_DIGITS,
                    period = info?.optInt("period", TotpParams.DEFAULT_PERIOD_SEC) ?: TotpParams.DEFAULT_PERIOD_SEC,
                    type = rawType.uppercase(),
                ),
            )
            collect(result, name, accepted, skipped, i)
        }
        return TotpImportResult(accepted, skipped)
    }

    private fun parse2Fas(root: JSONObject): TotpImportResult {
        val services = root.optJSONArray("services") ?: throw TotpBackupException("2FAS 备份缺少 services")
        val accepted = mutableListOf<TotpBackupEntry>()
        val skipped = mutableListOf<String>()
        for (i in 0 until services.length()) {
            val obj = services.optJSONObject(i)
            if (obj == null) {
                skipped += "第 " + (i + 1) + " 条格式损坏"
                continue
            }
            val otp = obj.optJSONObject("otp")
            val name = obj.optString("name").trim()
            val tokenType = otp?.optString("tokenType", "TOTP").orEmpty().ifBlank { "TOTP" }
            val issuer = otp?.optString("issuer").orEmpty().ifBlank { name }
            val account = otp?.optString("account").orEmpty()
                .ifBlank { otp?.optString("label").orEmpty() }
                .ifBlank { name }
            val result = normalize(
                TotpBackupEntry(
                    issuer = issuer.trim(),
                    account = account.trim(),
                    secret = obj.optString("secret").ifBlank { otp?.optString("secret").orEmpty() },
                    algorithm = otp?.optString("algorithm", TotpParams.ALGORITHM_SHA1).orEmpty(),
                    digits = otp?.optInt("digits", TotpParams.DEFAULT_DIGITS) ?: TotpParams.DEFAULT_DIGITS,
                    period = otp?.optInt("period", TotpParams.DEFAULT_PERIOD_SEC)
                        ?: TotpParams.DEFAULT_PERIOD_SEC,
                    type = tokenType.uppercase(),
                ),
            )
            collect(result, name, accepted, skipped, i)
        }
        return TotpImportResult(accepted, skipped)
    }

    // MARK: - 归一化与校验

    /**
     * 单条归一化：先做结构检查（类型 / 账户名 / 密钥非空），
     * 再构造 TotpAccount 走 domain 的 OtpauthUri.build + parse 往返 —— 参数约束只有 domain 一份。
     */
    private fun normalize(entry: TotpBackupEntry): Pair<TotpBackupEntry?, String?> {
        if (!entry.type.equals("TOTP", ignoreCase = true)) return null to "不支持的验证器类型：" + entry.type
        val account = entry.account.trim()
        if (account.isEmpty()) return null to "缺少账户名"
        val secret = entry.secret.filterNot { it.isWhitespace() }.trimEnd('=').uppercase()
        if (secret.isEmpty()) return null to "缺少密钥"
        val candidate = TotpAccount(
            issuer = entry.issuer.trim(),
            accountName = account,
            secretBase32 = secret,
            params = TotpParams(
                algorithm = entry.algorithm.uppercase(),
                digits = entry.digits,
                periodSec = entry.period,
            ),
        )
        return when (val parsed = runCatching { OtpauthUri.parse(OtpauthUri.build(candidate)) }
            .getOrElse { OtpauthParseResult.Failure(OtpauthUri.REASON_SECRET_INVALID) }) {
            is OtpauthParseResult.Success -> TotpBackupEntry(
                issuer = parsed.account.issuer,
                account = parsed.account.accountName,
                secret = parsed.account.secretBase32,
                algorithm = parsed.account.params.algorithm,
                digits = parsed.account.params.digits,
                period = parsed.account.params.periodSec,
                type = "TOTP",
            ) to null
            is OtpauthParseResult.Failure -> null to "参数不被支持：" + parsed.reason
        }
    }

    private fun collect(
        result: Pair<TotpBackupEntry?, String?>,
        name: String,
        accepted: MutableList<TotpBackupEntry>,
        skipped: MutableList<String>,
        index: Int,
    ) {
        val (entry, reason) = result
        if (entry != null) {
            accepted += entry
        } else {
            val label = name.ifBlank { "第 " + (index + 1) + " 条" }
            skipped += label + "：" + (reason ?: "格式不支持")
        }
    }

    private fun entryToJson(entry: TotpBackupEntry): JSONObject = JSONObject().apply {
        put("issuer", entry.issuer)
        put("account", entry.account)
        put("secret", entry.secret)
        put("algorithm", entry.algorithm)
        put("digits", entry.digits)
        put("period", entry.period)
        put("type", entry.type)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val VERSION = 1
        const val APP_ID = "android-monitor"
        const val HEADER = "AMTOTP/1"
        const val KDF_LABEL = "pbkdf2-sha256"
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PBKDF2_ITERATIONS = 210_000
        const val MIN_PBKDF2_ITERATIONS = 100_000
        const val MAX_PBKDF2_ITERATIONS = 1_000_000
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val MIN_PASSPHRASE_LENGTH = 8
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}
