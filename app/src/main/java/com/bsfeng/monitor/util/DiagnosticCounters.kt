package com.bsfeng.monitor.util

/**
 * 应用内诊断计数器 — 用于在无 adb/logcat 环境下排查数据流问题
 * 所有字段使用 @Volatile 确保跨线程可见性
 */
object DiagnosticCounters {
    // ========== 环节 1: 无障碍服务 ==========
    @Volatile
    var accessibilityEventCount: Int = 0          // onAccessibilityEvent 被调用次数
    @Volatile
    var lastAccessibilityEventType: String = ""   // 最后一次事件类型
    @Volatile
    var lastAccessibilityPackage: String = ""     // 最后一次事件包名
    @Volatile
    var accessibilityFilteredCount: Int = 0       // 被 isSystemUi 过滤的次数

    // ========== 环节 2: EventCollector ==========
    @Volatile
    var emitCalledCount: Int = 0                  // emit 被调用次数
    @Volatile
    var lastEmittedEventType: String = ""          // 最后一次发射的事件类型
    @Volatile
    var emitSuccessCount: Int = 0                  // 协程内 insert 成功的次数
    @Volatile
    var emitFailCount: Int = 0                     // 协程内 insert 失败的次数
    @Volatile
    var lastErrorMessage: String = ""              // 最后一次异常信息

    // ========== 环节 3: DatabaseHelper ==========
    @Volatile
    var dbInsertCalledCount: Int = 0               // insert 被调用次数
    @Volatile
    var dbInsertSuccessCount: Int = 0              // insert 成功(rowId >= 0)次数
    @Volatile
    var dbInsertConflictCount: Int = 0             // 主键冲突(rowId == -1)次数
    @Volatile
    var dbInsertFailCount: Int = 0                 // insert 抛异常次数
    @Volatile
    var dbLastException: String = ""               // 最后一次异常信息

    // ========== 环节 4: 上报 ==========
    @Volatile
    var uploadAttemptCount: Int = 0                // uploadBatch 被执行次数
    @Volatile
    var uploadSuccessCount: Int = 0                // 上传成功次数
    @Volatile
    var uploadFailCount: Int = 0                   // 上传失败次数
    @Volatile
    var lastUploadResult: String = ""              // 上次上传结果描述
    @Volatile
    var lastUploadError: String = ""               // 上次上传异常信息
    @Volatile
    var isTokenAvailable: Boolean = false          // 是否有 Token
    @Volatile
    var uploadEventCount: Int = 0                  // 上次上传的事件数量

    // ========== 总览 ==========
    @Volatile
    var lastRefreshTimestamp: Long = 0L            // 最后一次刷新时间

    fun reset() {
        accessibilityEventCount = 0
        lastAccessibilityEventType = ""
        lastAccessibilityPackage = ""
        accessibilityFilteredCount = 0
        emitCalledCount = 0
        lastEmittedEventType = ""
        emitSuccessCount = 0
        emitFailCount = 0
        lastErrorMessage = ""
        dbInsertCalledCount = 0
        dbInsertSuccessCount = 0
        dbInsertConflictCount = 0
        dbInsertFailCount = 0
        dbLastException = ""
        uploadAttemptCount = 0
        uploadSuccessCount = 0
        uploadFailCount = 0
        lastUploadResult = ""
        lastUploadError = ""
        isTokenAvailable = false
        uploadEventCount = 0
    }

    fun snapshot(): String = buildString {
        appendLine("=== 诊断报告 ===")
        appendLine("")
        appendLine("【无障碍服务】")
        appendLine("  onAccessibilityEvent 调用: $accessibilityEventCount 次")
        appendLine("  最后一次事件类型: $lastAccessibilityEventType")
        appendLine("  最后一次包名: $lastAccessibilityPackage")
        appendLine("  被系统UI过滤: $accessibilityFilteredCount 次")
        appendLine("")
        appendLine("【EventCollector】")
        appendLine("  emit 被调用: $emitCalledCount 次")
        appendLine("  最后一次事件类型: $lastEmittedEventType")
        appendLine("  写入成功: $emitSuccessCount 次")
        appendLine("  写入失败: $emitFailCount 次")
        if (lastErrorMessage.isNotBlank()) {
            appendLine("  最近异常: $lastErrorMessage")
        }
        appendLine("")
        appendLine("【DatabaseHelper】")
        appendLine("  insert 被调用: $dbInsertCalledCount 次")
        appendLine("  写入成功: $dbInsertSuccessCount 次")
        appendLine("  主键冲突: $dbInsertConflictCount 次")
        appendLine("  写入失败: $dbInsertFailCount 次")
        if (dbLastException.isNotBlank()) {
            appendLine("  最近异常: $dbLastException")
        }
        appendLine("")
        appendLine("【上传到服务器】")
        appendLine("  上传尝试: $uploadAttemptCount 次")
        appendLine("  成功: $uploadSuccessCount  失败: $uploadFailCount")
        if (uploadSuccessCount > 0 || uploadEventCount > 0) {
            appendLine("  上次上传事件数: $uploadEventCount")
        }
        if (lastUploadResult.isNotBlank()) {
            appendLine("  上次结果: $lastUploadResult")
        }
        if (lastUploadError.isNotBlank()) {
            appendLine("  上次错误: $lastUploadError")
        }
        appendLine("  有 Token: $isTokenAvailable")
    }

    override fun toString(): String = snapshot()
}
