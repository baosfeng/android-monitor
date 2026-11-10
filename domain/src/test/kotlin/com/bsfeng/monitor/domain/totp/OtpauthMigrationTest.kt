package com.bsfeng.monitor.domain.totp

// Google 迁移二维码（otpauth-migration://offline?data=...）的解析口径。
//
// 样本全部在测试内用 protobuf wire format 手工构造（见文件末尾的 Builder），不引用来源不明的外部串；
// 一侧用十六进制断言回读 Builder 的输出，保证「注释里的字节布局」与真实字节一致。
//
// 字节布局（官方 MigrationPayload）：
//   消息体：tag1 (length-delimited) = OtpParameters 条目，可重复；tag2 (varint) = version
//   条目：tag1 = secret(bytes) / tag2 = name(bytes) / tag3 = issuer(bytes)
//        tag4 = algorithm(varint) / tag5 = digits(varint) / tag6 = type(varint) / tag7 = counter(varint)

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class OtpauthMigrationTest {

    // ───────────────────────── 正常路径 ─────────────────────────

    @Test
    fun `构造器产出的字节与文档里的布局一致`() {
        // 两条 TOTP 的完整 payload 十六进制（与解析结果一一对应）：
        //   0a 25 | 0a 0f "hello world 124" | 12 05 "Hi, A" | 1a 05 "Hi, A" | 20 01 | 28 01 | 30 02
        //   0a 2c | 0a 0c "keysfast let"     | 12 0a "Not So Bad" | 1a 0a "Not So Bad" | 20 02 | 28 02 | 30 02
        //   10 01（version = 1）
        val expectedHex =
            "0a250a0f68656c6c6f20776f726c6420313234120548692c20411a0548692c2041200128" +
                "0130020a2c0a0c6b65797366617374206c6574120a4e6f7420536f204261641a0a4e6f" +
                "7420536f204261642002280230021001"
        assertArrayEquals(toBytes(expectedHex), twoTotp)
        val data = Base64.getEncoder().encodeToString(twoTotp)
        assertEquals(
            "CiUKD2hlbGxvIHdvcmxkIDEyNBIFSGksIEEaBUhpLCBBIAEoATACCiwKDGtleXNmYXN0IGxl" +
                "dBIKTm90IFNvIEJhZBoKTm90IFNvIEJhZCACKAIwAhAB",
            data,
        )
    }

    @Test
    fun `解析两条 TOTP 账户`() {
        val result = parseStandard(twoTotp)
        assertEquals("应解出两条账户", 2, result.accounts.size)
        assertTrue("不应有跳过项：" + result.skipped, result.skipped.isEmpty())

        val first = result.accounts[0]
        assertEquals("Hi, A", first.issuer)
        assertEquals("Hi, A", first.accountName)
        assertEquals("NBSWY3DPEB3W64TMMQQDCMRU", first.secretBase32)
        assertEquals(TotpParams("SHA1", 6, 30, "TOTP"), first.params)
        assertEquals(TotpAccount.VARIANT_STANDARD, first.variant)

        val second = result.accounts[1]
        assertEquals("Not So Bad", second.accountName)
        assertEquals("NNSXS43GMFZXIIDMMV2A", second.secretBase32)
        assertEquals(TotpParams.ALGORITHM_SHA256, second.params.algorithm)
        assertEquals(8, second.params.digits)
    }

    @Test
    fun `HOTP 条目透传计数器`() {
        // 0a 2a | 0a 0f "hello world 124" | 12 06 "Legacy" | 1a 07 "OldCorp"
        //       | 20 01(算法 SHA1) | 28 01(6 位) | 30 01(类型 HOTP) | 38 07(计数器 7) | 10 01(version)
        val result = parseStandard(hotpCounter7)
        val account = result.accounts.single()
        assertEquals(TotpParams.TYPE_HOTP, account.params.type)
        assertEquals(7L, account.counter)
        assertEquals("OldCorp", account.issuer)
        assertEquals("Legacy", account.accountName)
    }

    @Test
    fun `TOTP 条目的计数器被清零`() {
        // 与上一条同形，但类型是 2(TOTP)：counter 字段即使写了 7 也不该带进账户
        val result = parseStandard(
            payloadOf(entry(secret = S1, name = "Time", issuer = "Time", algorithm = 1, digits = 1, type = 2, counter = 7)),
        )
        val account = result.accounts.single()
        assertEquals(TotpParams.TYPE_TOTP, account.params.type)
        assertEquals(0L, account.counter)
    }

    @Test
    fun `缺省枚举值按官方语义落到默认`() {
        // 条目只带 secret/name/issuer：algorithm=0 → SHA1、digits=0 → 6、type=0 → TOTP
        val account = parseStandard(payloadOf(entry(secret = S1, name = "Defaults", issuer = "Defaults")))
            .accounts.single()
        assertEquals(TotpParams(), account.params)
    }

    @Test
    fun `type 枚举 2 按 TOTP 处理`() {
        val account = parseStandard(payloadOf(entry(secret = S1, name = "T", issuer = "T", type = 2)))
            .accounts.single()
        assertEquals(TotpParams.TYPE_TOTP, account.params.type)
    }

    @Test
    fun `name 为空但 issuer 在时用 issuer 顶替`() {
        // 0a 25 | 0a 0f "hello world 124" | 12 00(空 name) | 1a 0a "IssuerOnly" | 20 01 | 28 01 | 30 02 | 10 01
        val account = parseStandard(nameEmptyIssuerOnly).accounts.single()
        assertEquals("IssuerOnly", account.issuer)
        assertEquals("IssuerOnly", account.accountName)
    }

    @Test
    fun `中文 name 与 issuer 按 UTF-8 解码`() {
        val account = parseStandard(payloadOf(entry(secret = S1, name = "张三", issuer = "腾讯云")))
            .accounts.single()
        assertEquals("腾讯云", account.issuer)
        assertEquals("张三", account.accountName)
    }

    @Test
    fun `未知字段必须跳过而不中断解析`() {
        // 顶层插 tag 99 varint（0x98 0x06，值 1234 = 0xd2 0x09）
        //     与 tag 98 length-delimited（0x92 0x06，长度 2，载荷 01 02）
        // 条目内再插 tag 99 varint（0x98 0x06 0x05）
        val result = parseStandard(unknownFields)
        assertEquals(1, result.accounts.size)
        assertTrue("未知字段不该导致跳过：" + result.skipped, result.skipped.isEmpty())
        assertEquals("Unk", result.accounts.single().accountName)
    }

    @Test
    fun `百分号编码的 padding 与字面 padding 等价`() {
        // 只把 data 值里的 '=' 编码成 %3D（不能动 "?data=" 里的那个等号）
        val percentPadded = "otpauth-migration://offline?data=" +
            Base64.getEncoder().encodeToString(hotpCounter7).replace("=", "%3D")
        assertEquals(
            parseStandard(hotpCounter7).accounts.single(),
            OtpauthMigration.parse(percentPadded).accounts.single(),
        )
    }

    @Test
    fun `无 padding 的 data 也能解析`() {
        val base = uriOf(hotpCounter7)
        assertEquals(
            parseStandard(hotpCounter7).accounts.single(),
            OtpauthMigration.parse(base.trimEnd('=')).accounts.single(),
        )
    }

    @Test
    fun `base64url 形态也能解析`() {
        // 这条 payload 的标准 Base64 带 '/'（…KB/v/v5…），URL-safe 用 '_' 替代
        val urlSafe = Base64.getUrlEncoder().encodeToString(plusSecretStandard)
        assertTrue("样本必须真的用到 url-safe 字母表", urlSafe.contains('_'))
        val account = OtpauthMigration.parse("otpauth-migration://offline?data=" + urlSafe).accounts.single()
        assertEquals("Plus", account.accountName)
        assertEquals("7P737H7H373Q", account.secretBase32)
        assertEquals("Plus", parseStandard(plusSecretStandard).accounts.single().accountName)
    }

    @Test
    fun `data 含字面加号仍能解析`() {
        // 这条 payload 的标准 Base64 里同时有 '+' 和 '/'：…KB/s+Pj4…（密钥 fb 3e 3e 3e 3e 3e 3e）
        // 防复发：URLDecoder 的默认语义会把字面 '+' 解成空格，空格又被 decodeData 的
        // filterNot 删掉，整串 base64 被静默改坏 —— 一个 '+' 就能让整张迁移图失败。
        val plusPayload = payloadOf(
            entry(secret = plusSecret, name = "X", issuer = "X", algorithm = 1, digits = 1, type = 2),
        )
        val plusData = Base64.getEncoder().encodeToString(plusPayload)
        assertTrue("样本必须真的含字面 '+'", plusData.contains('+'))
        assertTrue("样本必须真的含 '/'", plusData.contains('/'))

        val literal = OtpauthMigration.parse("otpauth-migration://offline?data=" + plusData)
        assertEquals("字面 '+' 不该被当成空格", 1, literal.accounts.size)
        assertTrue(literal.skipped.isEmpty())
        assertEquals("X", literal.accounts.single().accountName)

        // 同一 payload 的 %2B 编码形态与 URL-safe 形态同样可用
        val percent = OtpauthMigration.parse(
            "otpauth-migration://offline?data=" + plusData.replace("+", "%2B").replace("/", "%2F"),
        )
        assertEquals(literal.accounts.single(), percent.accounts.single())
        val urlSafe = OtpauthMigration.parse(
            "otpauth-migration://offline?data=" + Base64.getUrlEncoder().encodeToString(plusPayload),
        )
        assertEquals(literal.accounts.single(), urlSafe.accounts.single())
    }

    @Test
    fun `data 里夹带换行与空格仍能解析`() {
        // 手抄/换行粘贴的形态：base64 每 16 字符插一个真换行（query 里 %0A 编码）
        val data = Base64.getEncoder().encodeToString(hotpCounter7)
        val wrapped = data.chunked(16).joinToString("%0A")
        val result = OtpauthMigration.parse("otpauth-migration://offline?data=" + wrapped)
        assertEquals(1, result.accounts.size)
        assertEquals("Legacy", result.accounts.single().accountName)
    }

    @Test
    fun `scheme 大小写不敏感且前后空白被裁掉`() {
        val upper = "  " + uriOf(hotpCounter7).replace("otpauth-migration://", "OTPAUTH-MIGRATION://") + "  "
        assertEquals(1, OtpauthMigration.parse(upper).accounts.size)
    }

    // ───────────────────────── 边界 ─────────────────────────

    @Test
    fun `data 恰好等于上限仍可解析`() {
        val big = payloadWithSecretSize(OtpauthMigration.MAX_DATA_BYTES)
        assertEquals(OtpauthMigration.MAX_DATA_BYTES, big.size)
        assertEquals(1, parseStandard(big).accounts.size)
    }

    @Test
    fun `超过上限的 data 整批拒绝`() {
        val tooBig = payloadWithSecretSize(OtpauthMigration.MAX_DATA_BYTES + 1)
        assertEquals(OtpauthMigration.MAX_DATA_BYTES + 1, tooBig.size)
        val result = parseStandard(tooBig)
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `单条坏不影响其余 一好一坏得到一条一条跳过`() {
        // 条目2 的 algorithm 写 99（0x20 0x63）：未知枚举值按参数非法拒绝，不中断整批
        val result = parseStandard(mixedGoodAndBad)
        assertEquals(1, result.accounts.size)
        assertEquals(1, result.skipped.size)
        assertEquals("Good", result.accounts.single().accountName)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_ALGORITHM_UNSUPPORTED), result.skipped.single())
    }

    // ───────────────────────── 异常路径 ─────────────────────────

    @Test
    fun `version 不等于 1 时整批拒绝`() {
        val result = parseStandard(version2)
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `没有 version 字段时按 0 处理并拒绝`() {
        val noVersion = payloadOf(entry(secret = S1, name = "NoVer", issuer = "NoVer"), version = null)
        assertEquals(0, parseStandard(noVersion).accounts.size)
        assertEquals(
            listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)),
            parseStandard(noVersion).skipped,
        )
    }

    @Test
    fun `data 缺失时整批拒绝`() {
        val result = OtpauthMigration.parse("otpauth-migration://offline?batch_size=1")
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `没有 query 分隔符时整批拒绝`() {
        assertEquals(1, OtpauthMigration.parse("otpauth-migration://offline").skipped.size)
        assertEquals(1, OtpauthMigration.parse("otpauth-migration://").skipped.size)
    }

    @Test
    fun `scheme 不对时整批拒绝`() {
        assertEquals(1, OtpauthMigration.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP").skipped.size)
        assertEquals(1, OtpauthMigration.parse("https://example.com/?data=" + uriOf(hotpCounter7)).skipped.size)
    }

    @Test
    fun `data 解不出 Base64 时整批拒绝`() {
        // "bad" 补 padding 后是合法 Base64，但解出的字节不是合法 protobuf
        assertEquals(0, OtpauthMigration.parse("otpauth-migration://offline?data=bad").accounts.size)
        // 含 Base64 与 Base32 都不认的字符
        assertEquals(0, OtpauthMigration.parse("otpauth-migration://offline?data=%%%").accounts.size)
        // 空值与纯空白
        assertEquals(0, OtpauthMigration.parse("otpauth-migration://offline?data=").accounts.size)
        assertEquals(0, OtpauthMigration.parse("otpauth-migration://offline?data=%20%20").accounts.size)
    }

    @Test
    fun `payload 为空时整批拒绝`() {
        // "====" 解出 0 字节：空 payload 既没有 version 也没有条目
        val result = OtpauthMigration.parse("otpauth-migration://offline?data====")
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `顶层坏 wire type 时整批拒绝`() {
        // 0x10 0x01（tag2 varint version=1）+ 0x0E = (1<<3)|6：wire type 6 非法 → 整体损坏
        val result = OtpauthMigration.parse(
            "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(byteArrayOf(0x10, 0x01, 0x0E)),
        )
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `未知算法枚举值拒绝`() {
        val result = parseStandard(payloadOf(entry(secret = S1, name = "X", issuer = "X", algorithm = 99)))
        assertEquals(0, result.accounts.size)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_ALGORITHM_UNSUPPORTED), result.skipped.single())
    }

    @Test
    fun `未知位数枚举值拒绝`() {
        val result = parseStandard(payloadOf(entry(secret = S1, name = "X", issuer = "X", digits = 99)))
        assertEquals(0, result.accounts.size)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_DIGITS_UNSUPPORTED), result.skipped.single())
    }

    @Test
    fun `未知类型枚举值拒绝`() {
        val result = parseStandard(payloadOf(entry(secret = S1, name = "X", issuer = "X", type = 99)))
        assertEquals(0, result.accounts.size)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_BAD_TYPE), result.skipped.single())
    }

    @Test
    fun `缺密钥与空密钥分别给出不同原因`() {
        val missing = parseStandard(payloadOf(entry(name = "NoSec", issuer = "NoSec")))
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_SECRET_MISSING), missing.skipped.single())

        // 0a 16 0a 00 …：secret 字段存在但长度为 0
        val empty = parseStandard(emptySecret)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_SECRET_INVALID), empty.skipped.single())
    }

    @Test
    fun `name 与 issuer 都空时按缺账户名拒绝`() {
        val result = parseStandard(payloadOf(entry(secret = S1, name = "", issuer = "")))
        assertEquals(0, result.accounts.size)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_LABEL_MISSING), result.skipped.single())
    }

    @Test
    fun `顶层 tag1 不是 length-delimited 时按未知字段跳过`() {
        // 0x08 0x03：tag1 写成 varint —— 不是条目，跳过即可；version 仍然读得到
        val payload = byteArrayOf(0x08, 0x03) + varField(2, 1)
        val result = parseStandard(payload)
        assertEquals(0, result.accounts.size)
        assertTrue("只有坏条目、没有可落库账户", result.skipped.isEmpty())
    }

    @Test
    fun `条目里的未知 length-delimited 字段被跳过`() {
        // 条目内插 tag 9 length-delimited（0x4A = (9<<3)|2，长度 1，载荷 0x00）
        val entry = entry(secret = S1, name = "U", issuer = "U", algorithm = 1, digits = 1, type = 2) +
            byteArrayOf(0x4A, 0x01, 0x00)
        val result = parseStandard(payloadOf(entry))
        assertEquals(1, result.accounts.size)
        assertEquals("U", result.accounts.single().accountName)
    }

    @Test
    fun `条目 protobuf 截断时该条按迁移损坏跳过`() {
        // 条目以 0x10（varint 的 key）结尾却没有值：ProtoWire 读到 null → 该条整体损坏
        val broken = byteArrayOf(0x0A, 0x01, 0x58, 0x10)
        val result = parseStandard(payloadOf(broken))
        assertEquals(0, result.accounts.size)
        assertEquals(listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)), result.skipped)
    }

    @Test
    fun `query 里没有等号的键被忽略`() {
        // "batch_size" 没有 '=' → 跳过；后面的 data 仍要正常取到
        val wrapped = "otpauth-migration://offline?batch_size&data=" + Base64.getEncoder().encodeToString(hotpCounter7)
        assertEquals(1, OtpauthMigration.parse(wrapped).accounts.size)
    }

    @Test
    fun `data 参数名大小写不敏感且取第一个`() {
        val first = uriOf(hotpCounter7)
        assertEquals(1, OtpauthMigration.parse(first.replace("?data=", "?DATA=")).accounts.size)
        // 追加一个假的 data 不该覆盖第一个
        assertEquals(1, OtpauthMigration.parse(first + "&data=bad").accounts.size)
    }

    @Test
    fun `条目里 wire type 不符时该条按缺密钥跳过`() {
        // 条目只有 08 03（tag1 varint）：secret 期望 length-delimited，读到 varint 就不该被当成密钥
        val result = parseStandard(payloadOf(byteArrayOf(0x08, 0x03), version = 1))
        assertEquals(0, result.accounts.size)
        assertEquals(OtpauthUri.reasonMessage(OtpauthUri.REASON_SECRET_MISSING), result.skipped.single())
    }

    // ───────────────────────── 样本 ─────────────────────────

    /** 两条 TOTP：Hi, A（SHA1/6）+ Not So Bad（SHA256/8）。 */
    private val twoTotp: ByteArray = payloadOf(
        entry(secret = S1, name = "Hi, A", issuer = "Hi, A", algorithm = 1, digits = 1, type = 2),
        entry(secret = S2, name = "Not So Bad", issuer = "Not So Bad", algorithm = 2, digits = 2, type = 2),
    )

    /** 一条 HOTP：Legacy / OldCorp，SHA1/6，counter=7。 */
    private val hotpCounter7: ByteArray = payloadOf(
        entry(secret = S1, name = "Legacy", issuer = "OldCorp", algorithm = 1, digits = 1, type = 1, counter = 7),
    )

    /** 一好一坏：Good（SHA1/6）+ Bad Alg（algorithm=99）。 */
    private val mixedGoodAndBad: ByteArray = payloadOf(
        entry(secret = S1, name = "Good", issuer = "Good", algorithm = 1, digits = 1, type = 2),
        entry(secret = S2, name = "Bad Alg", issuer = "Bad Alg", algorithm = 99, digits = 1, type = 2),
    )

    /** 顶层与条目内都插了未知字段（tag 99 varint / tag 98 length-delimited）。 */
    private val unknownFields: ByteArray =
        payloadOfEntries(
            entries = listOf(
                entry(
                    secret = S1, name = "Unk", issuer = "Unk",
                    algorithm = 1, digits = 1, type = 2, extra = varField(99, 5),
                ),
            ),
            tail = varField(99, 1234) + lenField(98, byteArrayOf(0x01, 0x02)),
        )

    /** 标准 Base64 里带 '/' 的一条 TOTP（密钥 fb ff bf 9f e7 df f7）。 */
    private val plusSecretStandard: ByteArray = payloadOf(
        entry(secret = S3, name = "Plus", issuer = "Plus", algorithm = 1, digits = 1, type = 2),
    )

    /** name 为空、issuer 在：IssuerOnly。 */
    private val nameEmptyIssuerOnly: ByteArray = payloadOf(
        entry(secret = S1, name = "", issuer = "IssuerOnly", algorithm = 1, digits = 1, type = 2),
    )

    /** version = 2（不支持的版本）。 */
    private val version2: ByteArray = payloadOf(
        entry(secret = S1, name = "V2", issuer = "V2", algorithm = 1, digits = 1, type = 2),
        version = 2,
    )

    /** secret 字段存在但长度为 0。 */
    private val emptySecret: ByteArray = payloadOf(
        entry(secret = ByteArray(0), name = "Empty", issuer = "Empty", algorithm = 1, digits = 1, type = 2),
    )

    /** 造一条总长恰好是 [size] 字节的 payload：靠调整密钥字节数逼近（varint 长度会随规模变，故用循环收敛）。 */
    private fun payloadWithSecretSize(size: Int): ByteArray {
        var secretSize = size - 60
        while (true) {
            val candidate = payloadOf(
                entry(secret = ByteArray(secretSize) { 'A'.code.toByte() }, name = "Big", issuer = "Big"),
            )
            when {
                candidate.size == size -> return candidate
                candidate.size < size -> secretSize += size - candidate.size
                else -> secretSize -= 1
            }
        }
    }

    // ---------- 断言工具 ----------

    /** 用标准 Base64（带 padding）生成迁移链接，与 Google 导出的形态一致。 */
    private fun parseStandard(payload: ByteArray): OtpauthBatchResult = OtpauthMigration.parse(uriOf(payload))

    private fun uriOf(payload: ByteArray): String =
        "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(payload)

    // ---------- protobuf wire format 构造器 ----------

    /** LEB128 varint。 */
    private fun varint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v < 0x80) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    /** length-delimited 字段：key = tag<<3|2，body 前面挂长度。 */
    private fun lenField(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(((tag shl 3) or 2).toByte()) + varint(body.size.toLong()) + body

    /** varint 字段：key = tag<<3|0。 */
    private fun varField(tag: Int, value: Long): ByteArray = byteArrayOf((tag shl 3).toByte()) + varint(value)

    /** 一条 OtpParameters；参数写 null 表示该字段不出现（解析端按 0 兜默认）。 */
    private fun entry(
        secret: ByteArray? = null,
        name: String? = null,
        issuer: String? = null,
        algorithm: Long? = null,
        digits: Long? = null,
        type: Long? = null,
        counter: Long? = null,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        var out = ByteArray(0)
        secret?.let { out += lenField(1, it) }
        name?.let { out += lenField(2, it.toByteArray(StandardCharsets.UTF_8)) }
        issuer?.let { out += lenField(3, it.toByteArray(StandardCharsets.UTF_8)) }
        algorithm?.let { out += varField(4, it) }
        digits?.let { out += varField(5, it) }
        type?.let { out += varField(6, it) }
        counter?.let { out += varField(7, it) }
        return out + extra
    }

    /** 整条 MigrationPayload：tag1 条目（可重复）+ tag2 version（null 表示不写）。 */
    private fun payloadOf(vararg entries: ByteArray, version: Long? = 1, tail: ByteArray = ByteArray(0)): ByteArray {
        var out = ByteArray(0)
        entries.forEach { out += lenField(1, it) }
        version?.let { out += varField(2, it) }
        return out + tail
    }

    private fun payloadOfEntries(entries: List<ByteArray>, tail: ByteArray): ByteArray {
        var out = ByteArray(0)
        entries.forEach { out += lenField(1, it) }
        return out + varField(2, 1) + tail
    }

    private fun toBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** "hello world 124"；Base32 = NBSWY3DPEB3W64TMMQQDCMRU。 */
        val S1: ByteArray = "hello world 124".toByteArray(StandardCharsets.UTF_8)

        /** "keysfast let"；Base32 = NNSXS43GMFZXIIDMMV2A。 */
        val S2: ByteArray = "keysfast let".toByteArray(StandardCharsets.UTF_8)

        /** 7 字节密钥，标准 Base64 含 '+'；Base32 = 7M7D4PR6HY7A。 */
        val plusSecret: ByteArray = byteArrayOf(0xFB.toByte(), 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E)

        /** 7 字节密钥，标准 Base64 必然带 '/'；Base32 = 7P737H7H373Q。 */
        val S3: ByteArray = byteArrayOf(
            0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0x9F.toByte(),
            0xE7.toByte(), 0xDF.toByte(), 0xF7.toByte(),
        )
    }
}
