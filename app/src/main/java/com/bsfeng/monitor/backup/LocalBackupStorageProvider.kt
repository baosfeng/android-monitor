package com.bsfeng.monitor.backup

import android.content.Context
import java.io.File

class LocalBackupStorageProvider private constructor(
    private val directory: File,
) : BackupStorageProvider {

    constructor(context: Context) : this(localBackupDir(context))

    constructor(root: File, name: String = "backups") : this(File(root, name))

    val backupDir: File get() = directory

    val dir: File get() = directory

    val localDir: File get() = directory

    override fun list(): List<BackupFileInfo> =
        directory.listFiles()
            ?.filter { it.isFile }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?.sortedWith(compareByDescending<BackupFileInfo> { it.modifiedAt }.thenByDescending { it.name })
            ?: emptyList()

    override fun put(name: String, source: File): Boolean {
        return try {
            if (!source.isFile) return false
            directory.mkdirs()
            val target = File(directory, name)
            source.copyTo(target, overwrite = true)
            prune()
            prunePreRestore()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun get(name: String): File? {
        val file = File(directory, name)
        return file.takeIf { it.isFile }
    }

    override fun delete(name: String): Boolean =
        File(directory, name).takeIf { it.exists() }?.delete() ?: false

    override fun exists(name: String): Boolean =
        File(directory, name).isFile

    fun prune() {
        val files = list()
        RetentionPolicy.expired(files, keep = RetentionPolicy.MAX_BACKUPS)
            .forEach { File(directory, it.name).delete() }
    }

    fun pruneLocal() = prune()

    fun prunePreRestore() {
        val preFiles = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BackupManager.PRE_RESTORE_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?: return
        RetentionPolicy.expired(preFiles, keep = 2, prefix = BackupManager.PRE_RESTORE_PREFIX)
            .forEach { File(directory, it.name).delete() }
    }

    companion object {
        fun fromContext(context: Context): LocalBackupStorageProvider =
            LocalBackupStorageProvider(localBackupDir(context))

        fun fromDirectory(directory: File): LocalBackupStorageProvider =
            LocalBackupStorageProvider(directory)

        private fun localBackupDir(context: Context): File {
            val app = context.applicationContext
            val base = app.getExternalFilesDir(null) ?: app.filesDir
            return File(base, BackupManager.LOCAL_DIR).apply { mkdirs() }
        }
    }
}
