package com.bsfeng.monitor.skip

import java.io.File

/**
 * 页面快照的保留策略（纯 java.io，可纯 JVM 单测，无 Android 依赖）。
 *
 * 快照内容是**屏幕原文**（聊天、支付页、通知文案），落在 `files/snapshots/` 里就是一份可被
 * 任何读到应用私有目录的人复原的隐私副本。因此：
 *
 * - 只保留最近 [MAX_SNAPSHOTS] 份，超出按修改时间最旧优先删除；
 * - 为什么不留 20 份：排查一个选择器通常只看当下这一两份，20 份意味着一次取证就能攒下
 *   几十屏聊天 / 支付页面；10 份对「翻回去看上一版页面」仍够用，暴露面减半；
 * - 清理入口在跳过规则页（「清除页面快照」），用户随时可把整目录清空（SEC-03）。
 */
object SnapshotRetention {

    /** 保留份数上限。 */
    const val MAX_SNAPSHOTS: Int = 10

    /** 快照目录名（相对 filesDir）。 */
    const val DIR_NAME: String = "snapshots"

    /** 快照目录（不保证存在）。 */
    fun dir(filesDir: File): File = File(filesDir, DIR_NAME)

    /** 目录内快照文件，按修改时间**最新在前**；目录不存在 / 不可读时返回空表。 */
    fun listNewestFirst(dir: File): List<File> =
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    /** 已保存份数。 */
    fun count(dir: File): Int = listNewestFirst(dir).size

    /**
     * 超出 [keep] 份时需要删除的文件（最旧优先）。
     *
     * 导出前调用时 `keep = MAX_SNAPSHOTS - 1`：删完再写，写完总数才正好等于上限。
     */
    fun planRemoval(dir: File, keep: Int = MAX_SNAPSHOTS): List<File> {
        if (keep < 0) return listNewestFirst(dir)
        return listNewestFirst(dir).drop(keep)
    }

    /** 按 [keep] 份淘汰；返回实际删除份数（删不掉的计入失败，不重试）。 */
    fun prune(dir: File, keep: Int = MAX_SNAPSHOTS): Int =
        planRemoval(dir, keep).count { runCatching { it.delete() }.getOrDefault(false) }

    /** 清空整个目录的快照；返回删除份数（目录不存在返回 0）。 */
    fun clear(dir: File): Int = listNewestFirst(dir).count { runCatching { it.delete() }.getOrDefault(false) }
}