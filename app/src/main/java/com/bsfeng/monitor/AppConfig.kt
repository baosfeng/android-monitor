package com.bsfeng.monitor

object AppConfig {
    /** 服务器地址写死为固定 IP */
    val serverBaseUrl: String get() = "http://127.0.0.1"
    val uploadIntervalMs: Int get() = BuildConfig.UPLOAD_INTERVAL_MS
    val batchMaxSize: Int get() = BuildConfig.BATCH_MAX_SIZE
    val cacheMaxDays: Int get() = BuildConfig.CACHE_MAX_DAYS
    val retryMaxAttempts: Int get() = BuildConfig.RETRY_MAX_ATTEMPTS
    val notificationContentEnabled: Boolean get() = BuildConfig.NOTIFICATION_CONTENT_ENABLED
}
