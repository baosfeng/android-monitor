package com.bsfeng.monitor.backup

import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.util.AppUtils
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApiServiceBackup {
    fun listBackups(): String? = null
    fun uploadBackup(name: String, source: File): Boolean = false
    fun downloadBackup(name: String, target: File): Boolean = false
    fun deleteBackup(name: String): Boolean = false
}
