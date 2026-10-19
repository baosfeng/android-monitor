package com.bsfeng.monitor

object AppConfig {
    /** 服务器地址：由 local.properties 的 serverBaseUrl 注入，仓库内只保留占位 */
    val serverBaseUrl: String get() = BuildConfig.SERVER_BASE_URL
    val uploadIntervalMs: Int get() = BuildConfig.UPLOAD_INTERVAL_MS
    val batchMaxSize: Int get() = BuildConfig.BATCH_MAX_SIZE
    val cacheMaxDays: Int get() = BuildConfig.CACHE_MAX_DAYS
    val retryMaxAttempts: Int get() = BuildConfig.RETRY_MAX_ATTEMPTS
    val notificationContentEnabled: Boolean get() = BuildConfig.NOTIFICATION_CONTENT_ENABLED
}
