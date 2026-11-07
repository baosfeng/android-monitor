package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的尾部；职责：导入请求的类型、扩展名分流、MIME 白名单与结果同构映射。
// 与屏幕装配分开，是因为这些是「格式契约」而非版式：改一样文件类型不需要读 700 行 UI。

import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.finance.importer.BillImportResult

/** 导入文件的来源类型：决定走哪个 importer。 */
internal enum class ImportKind {
    /** 熊猫记账导出的数据库（.db） */
    DATABASE,

    /** 支付宝 / 微信账单（.csv / .xlsx / .zip） */
    BILL,

    /** 不支持的扩展名；选中时直接给 readError，不进导入链路 */
    UNKNOWN,
    ;

    companion object {
        fun of(fileName: String): ImportKind =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "db" -> DATABASE
                "csv", "xlsx", "zip" -> BILL
                else -> UNKNOWN
            }
    }
}

/**
 * 一次导入请求：文件身份 + 载荷。
 *
 * 为什么由 UI 组装：扩展名分流发生在选文件那一刻（见 ImportScreen 的 launcher），
 * 调用方（MainTabContent）只负责把请求转给对应的 ViewModel 方法，不需要懂格式。
 */
internal sealed interface ImportRequest {
    val fileName: String

    /** 熊猫记账 .db：传落盘后的缓存路径（PandaImporter 自己去打开） */
    data class Database(override val fileName: String, val path: String) : ImportRequest

    /** 支付宝 / 微信账单：传原始字节（重试要能再读一次同一份内容） */
    data class Bill(override val fileName: String, val bytes: ByteArray) : ImportRequest {
        // ByteArray 是数组：data class 的默认 equals/hashCode 按引用比较，这里显式按内容比较，
        // 免得「同一份文件重选一次」被 Compose 当成不同状态反复重组。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bill) return false
            return fileName == other.fileName && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }
}

/** 文件选择器放行的 MIME：csv（两种写法）/ xlsx / zip / 兜底二进制（.db 与部分厂商的 csv）。 */
internal val IMPORT_MIME_TYPES: Array<String> = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/zip",
    "application/octet-stream",
)

/**
 * 把账单导入结果映射成 [PandaImporter.ImportResult] 的同构形状。
 *
 * 为什么复用而不是另起一个结果卡：两个来源要落在**同一张**结果卡、同一套五态与焦点迁移上；
 * 语义也一一对应（总行数 / 写入成功 / 跳过 / 错误明细），差异只有「更新」一格与「自动分类」一段 ——
 * 它们以单独参数往下传，为 0 时不渲染。
 */
internal fun BillImportResult.asPandaResult(): PandaImporter.ImportResult =
    PandaImporter.ImportResult(
        totalTransactions = totalRows,
        importedTransactions = inserted,
        skippedTransactions = skipped + unchanged,
        // 账单侧没有「新增分类 / 账户 / 预算」这三类副产物，保持 0 而不是伪造数字
        importedCategories = 0,
        importedAccounts = 0,
        importedBudgets = 0,
        errors = errors,
    )
