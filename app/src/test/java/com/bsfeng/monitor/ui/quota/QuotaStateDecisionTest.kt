package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s18 主面板 / 用量区的状态判定（第 4 批走查 P1-3、P1-5）与 s19 保存行处置（P1-8）。
 *
 * 这三条都是「状态说错话」类缺陷：用量卡让人去重填一个配好的令牌、错误态吞掉刚输入的 Key、
 * 保存失败还收起行。判据抽成纯函数后用单测钉死，避免以后再加状态时又散成一堆 if。
 */
class QuotaStateDecisionTest {

    // ── P1-3 用量区落点 ──

    @Test
    fun `有用量数据时渲染用量卡`() {
        assertEquals(
            QuotaUsageSlot.DATA,
            quotaUsageSlot(hasUsage = true, supportsUsage = true, usageConfigured = true, hasError = true),
        )
    }

    @Test
    fun `不提供用量接口的家不出用量区`() {
        assertEquals(
            QuotaUsageSlot.NONE,
            quotaUsageSlot(hasUsage = false, supportsUsage = false, usageConfigured = false, hasError = false),
        )
    }

    @Test
    fun `没用配置用量凭据时才引导去设置填`() {
        assertEquals(
            QuotaUsageSlot.MISSING_CREDENTIAL,
            quotaUsageSlot(hasUsage = false, supportsUsage = true, usageConfigured = false, hasError = false),
        )
    }

    @Test
    fun `凭据已配置但请求失败时显示失败态而不是引导重填`() {
        // 走查原缺陷：只看 usage == null 就报「再填一条用量凭据」，把用户送去重填一个没问题的令牌
        assertEquals(
            QuotaUsageSlot.FAILED,
            quotaUsageSlot(hasUsage = false, supportsUsage = true, usageConfigured = true, hasError = true),
        )
    }

    @Test
    fun `凭据已配置还没数据且无错误时算查询中`() {
        assertEquals(
            QuotaUsageSlot.LOADING,
            quotaUsageSlot(hasUsage = false, supportsUsage = true, usageConfigured = true, hasError = false),
        )
    }

    // ── P1-5 主面板状态 ──

    @Test
    fun `provider 不存在时是加载态`() {
        assertEquals(
            QuotaBodyMode.NO_PROVIDER,
            quotaBodyMode(
                hasProvider = false, loading = false, hasData = false, editingKey = false,
                credentialsConfigured = false, hasError = false,
            ),
        )
    }

    @Test
    fun `首次取数无缓存时切骨架`() {
        assertEquals(
            QuotaBodyMode.LOADING,
            quotaBodyMode(
                hasProvider = true, loading = true, hasData = false, editingKey = false,
                credentialsConfigured = true, hasError = false,
            ),
        )
    }

    @Test
    fun `有旧数据时刷新不切骨架`() {
        assertEquals(
            QuotaBodyMode.DATA,
            quotaBodyMode(
                hasProvider = true, loading = true, hasData = true, editingKey = false,
                credentialsConfigured = true, hasError = true,
            ),
        )
    }

    @Test
    fun `凭据未配置出配置卡`() {
        assertEquals(
            QuotaBodyMode.CONFIG,
            quotaBodyMode(
                hasProvider = true, loading = false, hasData = false, editingKey = false,
                credentialsConfigured = false, hasError = false,
            ),
        )
    }

    @Test
    fun `无数据查询失败出错误态`() {
        assertEquals(
            QuotaBodyMode.ERROR,
            quotaBodyMode(
                hasProvider = true, loading = false, hasData = false, editingKey = false,
                credentialsConfigured = true, hasError = true,
            ),
        )
    }

    @Test
    fun `点过修改 Key 后错误态让位给配置卡`() {
        // 二选一：错误块与配置卡不并列；进编辑后错误原因就地提示、重试在提示行里
        assertEquals(
            QuotaBodyMode.EDIT_KEY,
            quotaBodyMode(
                hasProvider = true, loading = false, hasData = false, editingKey = true,
                credentialsConfigured = true, hasError = true,
            ),
        )
    }

    @Test
    fun `保存后查询中先出骨架再决定去错误态还是配置卡`() {
        assertEquals(
            QuotaBodyMode.LOADING,
            quotaBodyMode(
                hasProvider = true, loading = true, hasData = false, editingKey = true,
                credentialsConfigured = true, hasError = true,
            ),
        )
    }

    // ── P1-8 保存行处置 ──

    @Test
    fun `保存与查询在途时行不收也不留`() {
        assertEquals(
            QuotaSaveRowState.WAIT,
            saveRowState(savingProviderId = "ollama", providerId = "ollama", saveError = null),
        )
    }

    @Test
    fun `保存失败时留展开并就地提示`() {
        assertEquals(
            QuotaSaveRowState.KEEP_OPEN,
            saveRowState(savingProviderId = null, providerId = "ollama", saveError = "凭据无效或已过期"),
        )
    }

    @Test
    fun `保存成功后收起本行`() {
        assertEquals(
            QuotaSaveRowState.COLLAPSE,
            saveRowState(savingProviderId = null, providerId = "ollama", saveError = null),
        )
    }

    @Test
    fun `点已选中的刷新间隔不算改动`() {
        assertFalse(intervalSelectionChanged(minutes = 5, selected = 5))
        assertTrue(intervalSelectionChanged(minutes = 10, selected = 5))
    }
}
