package com.bsfeng.monitor.skip

import android.util.Log
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.skip.json.Json5Parser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * GKD 订阅规则拉取与更新（零第三方依赖：HttpURLConnection + 自研 JSON5 解析）。
 *
 * ## 更新流程
 * 1. 拉取用户填写的订阅 URL 文本
 * 2. 若文本里没有 `apps`/`globalGroups`（只是 `{id, version, updateUrl}` 探测文件），
 *    则解析 `updateUrl`/`checkUpdateUrl`（支持相对路径）后拉取真正的规则文件
 * 3. 与本地订阅版本比对：版本未变则跳过；否则解析并落库（见 [SkipRuleManager.saveSubscription]）
 *
 * ## 安全约束
 * - 仅允许 https（避免明文规则被篡改）
 * - 响应体上限 [MAX_BYTES]，规则条数上限 [MAX_RULES]
 * - 解析失败/字段缺失一律视为失败，静默保留旧规则（不清空）
 *
 * 关联文档：→ `docs/开屏跳过/业界调研与改进方案.md` §4 P1-1
 */
object GkdSubscription {

    private const val TAG = "GkdSubscription"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_RULES = 3_000

    /** 更新结果 */
    sealed interface Outcome {
        /** 已更新 */
        data class Updated(val version: Long, val ruleCount: Int) : Outcome

        /** 本地已是最新 */
        data object UpToDate : Outcome

        /** 失败（保留旧规则） */
        data class Failed(val reason: String) : Outcome
    }

    /** 自动检查周期（毫秒，默认 1 天） */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 单个订阅源的自动检查结果 */
    data class CheckResult(val url: String, val outcome: Outcome)

    /**
     * 定时检查并更新**全部**订阅源（App 启动时调用）。
     *
     * 未添加任何订阅、或距上次检查不足 [CHECK_INTERVAL_MS] 时返回空列表（不做任何网络请求）。
     *
     * @param manager 规则管理器
     * @param now 当前时间（毫秒，便于测试）
     */
    fun autoCheck(manager: SkipRuleManager, now: Long = System.currentTimeMillis()): List<CheckResult> {
        val metas = manager.subscriptionMetas()
        if (metas.isEmpty()) return emptyList()
        if (now - manager.lastSubscriptionCheckAt() < CHECK_INTERVAL_MS) return emptyList()
        manager.markSubscriptionChecked(now)
        val results = metas.map { CheckResult(it.url, update(manager, it.url)) }
        Log.i(TAG, "订阅自动检查(" + results.size + " 个订阅源): " +
            results.joinToString(", ") { it.url + " → " + it.outcome })
        return results
    }

    /**
     * 拉取并更新**指定**订阅源（多订阅源互不影响）。
     *
     * @param manager 规则管理器（负责落库与版本比对）
     * @param url 订阅地址（必须为 https）
     */
    fun update(manager: SkipRuleManager, url: String): Outcome {
        if (!url.startsWith("https://")) return Outcome.Failed("仅支持 https 订阅地址")
        val text = fetchRulesText(url) ?: return Outcome.Failed("下载失败或订阅格式不支持")
        val version = readVersion(text) ?: 0L
        val existing = manager.subscriptionMeta(url)
        if (existing != null && version in 1..existing.version) {
            return Outcome.UpToDate
        }
        val rules = parseRules(text)
        if (rules.isEmpty()) return Outcome.Failed("订阅里没有可解析的规则")
        if (rules.size > MAX_RULES) return Outcome.Failed("规则条数超过上限 " + MAX_RULES)
        manager.saveSubscription(url, version, rules)
        return Outcome.Updated(version, rules.size)
    }

    /**
     * 拉取规则文件文本：必要时跟随 `updateUrl`/`checkUpdateUrl` 一跳。
     *
     * @return 规则文件文本；失败返回 null
     */
    fun fetchRulesText(url: String): String? {
        val first = httpGet(url)
        if (first == null) {
            Log.w(TAG, "订阅下载失败: " + url)
            return null
        }
        val root = tryParse(first) ?: return null
        if (containsRules(root)) return first
        val next = resolveUpdateUrl(root, url) ?: run {
            Log.w(TAG, "订阅既没有规则也没有 updateUrl: " + url)
            return null
        }
        val second = httpGet(next)
        if (second == null) {
            Log.w(TAG, "订阅规则文件下载失败: " + next)
            return null
        }
        return if (containsRules(tryParse(second))) second else null
    }

    /** 解析订阅文本为规则列表；解析失败返回空列表 */
    fun parseRules(text: String): List<SkipRule> {
        val root = tryParse(text) ?: return emptyList()
        return try {
            parseGkdSubscription(root)
        } catch (e: Exception) {
            Log.w(TAG, "订阅解析失败", e)
            emptyList()
        }
    }

    /** 读取订阅版本号 */
    fun readVersion(text: String): Long? {
        val root = tryParse(text) ?: return null
        return try {
            readGkdSubscriptionVersion(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryParse(text: String): Any? = try {
        Json5Parser.parse(text)
    } catch (e: Exception) {
        Log.w(TAG, "JSON5 解析失败: " + e.message)
        null
    }

    /** 订阅根节点里是否直接包含规则（`apps`/`globalGroups`）；供快速判定与单测使用 */
    internal fun containsRules(root: Any?): Boolean {
        val map = root as? Map<*, *> ?: return false
        return map["apps"] != null || map["globalGroups"] != null
    }

    /** 解析 updateUrl/checkUpdateUrl（相对路径按订阅地址解析；非 https 结果一律拒绝） */
    internal fun resolveUpdateUrl(root: Any?, baseUrl: String): String? {
        val map = root as? Map<*, *> ?: return null
        val raw = (map["updateUrl"] ?: map["checkUpdateUrl"]) as? String ?: return null
        if (raw.isBlank()) return null
        if (raw.startsWith("https://")) return raw
        return try {
            URI(baseUrl).resolve(raw).toString().takeIf { it.startsWith("https://") }
        } catch (_: Exception) {
            null
        }
    }

    /** 简单 GET（10 秒超时，响应体上限 [MAX_BYTES]） */
    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "android-monitor")
            }
            if (connection.responseCode != 200) {
                Log.w(TAG, "订阅返回 " + connection.responseCode + ": " + url)
                return null
            }
            stream = connection.inputStream
            readLimited(stream)
        } catch (e: Exception) {
            Log.w(TAG, "订阅请求异常: " + e.message)
            null
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {
                // 关闭失败忽略
            }
            connection?.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_BYTES) {
                Log.w(TAG, "订阅响应体超过上限 " + MAX_BYTES + " 字节")
                return null
            }
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }
}
