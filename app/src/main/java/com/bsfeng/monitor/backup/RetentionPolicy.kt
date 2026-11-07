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

    /**
     * 返回「该删的」列表（最旧的在前）；份数不超限时返回空列表。
     *
     * @param prefix 只统计这个名字前缀的条目。回滚包（`monitor-prerestore-`）与普通备份
     *   （`monitor-backup-`）各算各的：混在一起时几次普通备份就能把 marker 指名的回滚包淘汰掉。
     */
    fun expired(
        backups: List<BackupFileInfo>,
        keep: Int = MAX_BACKUPS,
        prefix: String? = null,
    ): List<BackupFileInfo> {
        val scoped = if (prefix == null) backups else backups.filter { it.name.startsWith(prefix) }
        val limit = keep.coerceAtLeast(1)
        if (scoped.size <= limit) return emptyList()
        val newestFirst = scoped.sortedWith(
            compareByDescending<BackupFileInfo> { it.modifiedAt }.thenByDescending { it.name }
        )
        return newestFirst.drop(limit).reversed()
    }
}
