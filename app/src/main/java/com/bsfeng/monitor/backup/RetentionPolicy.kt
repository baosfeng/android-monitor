package com.bsfeng.monitor.backup

/** 一份已落盘的备份（本地快速列表目录或公共 Downloads 里的同一条记录）。 */
data class BackupFileInfo(
    val name: String,
    val modifiedAt: Long,
    val sizeBytes: Long = 0L,
)

/**
 * 保留策略：最多保留 [MAX_BACKUPS] 份，超额删最旧。
 *
 * 纯函数（只挑出该删的，不碰文件系统），排序口径 = 修改时间倒序，时间相同按文件名倒序
 * （文件名内含 yyyyMMdd-HHmmss，倒序即新的在前），保证结果稳定、可测试。
 */
object RetentionPolicy {

    const val MAX_BACKUPS = 5

    /** 返回「该删的」列表（最旧的在前）；份数不超限时返回空列表。 */
    fun expired(backups: List<BackupFileInfo>, keep: Int = MAX_BACKUPS): List<BackupFileInfo> {
        val limit = keep.coerceAtLeast(1)
        if (backups.size <= limit) return emptyList()
        val newestFirst = backups.sortedWith(
            compareByDescending<BackupFileInfo> { it.modifiedAt }.thenByDescending { it.name }
        )
        return newestFirst.drop(limit).reversed()
    }
}
