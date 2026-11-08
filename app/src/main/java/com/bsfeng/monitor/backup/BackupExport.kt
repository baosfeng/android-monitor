package com.bsfeng.monitor.backup

/**
 * 「把某一份备份复制到公共 Downloads」的纯逻辑：目标路径 + 确认闸门。
 *
 * 口径（为什么这么设计）：
 * - 备份包**不加密**，落到公共目录就等于对同机任何文件管理器与网盘同步敞开，所以只能由
 *   用户在确认弹窗里明确同意后，一次一份地复制；**不自动、不定时、不移动原文件**。
 * - 私有目录仍是唯一存放点（卸载随 App 一起消失），导出是显式的「搬一份出去」。
 */
object BackupExport {

    /** 公共目录名：与历史版本保持一致，用户旧包也在这个目录里，导出后能在同一处找齐。 */
    const val PUBLIC_DIR = "行为记录备份"

    /** MediaStore 的下载根目录名（等于 `Environment.DIRECTORY_DOWNLOADS`）。 */
    private const val DOWNLOADS_ROOT = "Download"

    /** 展示给用户看的下载根目录名（`Download` 是系统目录名，`Downloads` 才是用户认得的叫法）。 */
    private const val DOWNLOADS_LABEL = "Downloads"

    /**
     * 导出后的文件名：与私有目录里那份同名，避免同一份备份出现两个名字。
     *
     * 只保留最后一段并清掉分隔符：导出目标必须是**单个文件**，任何 `../` 都会被 MediaStore
     * 当成路径片段（等于把用户其它目录写穿）。
     */
    fun targetName(localName: String): String {
        val base = localName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.trim().replace("/", "_").replace("\\", "_")
        val safe = cleaned.trimStart('.')
        return safe.ifEmpty { "backup.zip" }
    }

    /** 展示用路径：`Downloads/行为记录备份/<文件名>`（成功提示里直接给这句）。 */
    fun displayPath(localName: String): String =
        DOWNLOADS_LABEL + "/" + PUBLIC_DIR + "/" + targetName(localName)

    /**
     * MediaStore 的 `RELATIVE_PATH`：末尾必须带 `/`，否则包会落在下载根目录而不是子目录里。
     */
    fun relativePath(): String = DOWNLOADS_ROOT + "/" + PUBLIC_DIR + "/"
}

/**
 * 导出确认闸门：只有「请求了某一份 + 用户明确确认」之后，才交出可写盘的文件名。
 *
 * 为什么把确认做成必须经过的一步而不是散落在 UI 里：导出目标是公共目录、包又不加密，
 * 任何一次重构都可能让某条路径绕过确认弹窗。把闸门收成一个只认识「请求 / 确认 / 取消」的对象，
 * 就能用纯 JVM 用例钉住「没确认、取消了 → 一个字节都不写」。
 */
class BackupExportGate {

    private var pending: String? = null

    /** 待确认的文件名；null = 没有在等确认（确认弹窗的可见性就该用它）。 */
    val pendingName: String? get() = pending

    /** 用户点了某一份备份的「导出」：记下待确认项。空名字视为无效请求（不进入确认态）。 */
    fun request(localName: String): Boolean {
        if (localName.isBlank()) return false
        pending = BackupExport.targetName(localName)
        return true
    }

    /**
     * 用户确认导出。
     *
     * @return 要写盘的文件名；从未请求 / 已取消时返回 null —— 调用方据此**不写盘**
     */
    fun confirm(): String? {
        val name = pending ?: return null
        pending = null
        return name
    }

    /** 用户取消（或弹窗被返回键 / 点外部关掉）：清空待确认项。 */
    fun cancel() {
        pending = null
    }
}
