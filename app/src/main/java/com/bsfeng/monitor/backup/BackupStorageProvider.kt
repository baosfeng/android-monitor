package com.bsfeng.monitor.backup

import java.io.File

/**
 * 备份落点抽象：list / put / get / delete / exists。
 *
 * 实现不感知 StorageMode 字符串；调用方按模式选 provider。
 */
interface BackupStorageProvider {
    fun list(): List<BackupFileInfo>

    fun put(name: String, source: File): Boolean

    fun get(name: String): File?

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean
}
