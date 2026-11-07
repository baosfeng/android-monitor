package com.bsfeng.monitor.finance.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * zip 容器解析（零第三方：系统 [java.util.zip]）。
 *
 * 支付宝的 .zip 导出里就是一份 csv（GBK），所以这里只做「把条目取出来」，
 * 解码交给 [BillFileParser]；按条目名而不是条目序号挑，包内多一个说明文件不会读错。
 */
object BillZip {

    /** zip 魔数：PK\x03\x04（本地文件头） */
    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 单个条目解压上限，防解压炸弹（csv 几十 MB 已经远超任何真实账单） */
    const val ENTRY_LIMIT_BYTES: Int = 64 * 1024 * 1024

    /** 字节是否以 zip 魔数开头（xlsx 也是 zip，谁都挡不住这里，由调用方区分包内路径） */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * 读出包内全部条目（名字 → 字节）。
     *
     * 用 [ZipInputStream] 流式读：xlsx 与账单 zip 都只有几个条目，一次性读完比随机访问简单，
     * 也不必关心 [java.util.zip.ZipFile] 需要 File 而调用方只给 ByteArray 的问题。
     */
    fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = zip.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > ENTRY_LIMIT_BYTES) {
                        throw IllegalArgumentException("压缩包条目过大：${entry.name}")
                    }
                    buffer.write(chunk, 0, read)
                }
                result[entry.name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return result
    }

    /** 条目名是否像 bill 的 csv（跳过 `__MACOSX/` 之类的附属文件） */
    fun isCsvEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("__macosx/") || lower.endsWith("/")) return false
        return lower.endsWith(".csv")
    }

    /**
     * 第一个像账单 csv 的条目。
     *
     * 优先取文件名带渠道关键字的条目（支付宝导出常有 `alipay_record_2026.csv`），
     * 都没有才对任意 csv 兜底 —— 包内混了说明 csv 时也不会读错。
     */
    fun firstCsv(bytes: ByteArray): Pair<String, ByteArray>? = firstCsvEntry(entries(bytes))

    /** 同 [firstCsv]，但复用已解开的条目（解析入口只解一次包） */
    fun firstCsvEntry(all: Map<String, ByteArray>): Pair<String, ByteArray>? {
        val csvs = all.filterKeys(::isCsvEntry)
        if (csvs.isEmpty()) return null
        val hinted = csvs.keys.firstOrNull { name ->
            val lower = name.lowercase()
            BillChannel.entries.any { channel -> channel.fileHint.any(lower::contains) }
        }
        val chosen = hinted ?: csvs.keys.first()
        return chosen to csvs.getValue(chosen)
    }
}
