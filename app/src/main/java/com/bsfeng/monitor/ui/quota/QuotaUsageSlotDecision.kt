package com.bsfeng.monitor.ui.quota

internal enum class QuotaUsageSlot { DATA, LOADING, FAILED, MISSING_CREDENTIAL, NONE }

internal fun quotaUsageSlot(
    hasUsage: Boolean,
    supportsUsage: Boolean,
    usageConfigured: Boolean,
    hasError: Boolean,
): QuotaUsageSlot = when {
    hasUsage -> QuotaUsageSlot.DATA
    !supportsUsage -> QuotaUsageSlot.NONE
    !usageConfigured -> QuotaUsageSlot.MISSING_CREDENTIAL
    hasError -> QuotaUsageSlot.FAILED
    else -> QuotaUsageSlot.LOADING
}
