package com.bsfeng.monitor.autobook

/**
 * 一条去重指纹。
 *
 * 一笔账单会同时产**多条**指纹（精确的 + 兜底的），任何一条命中都算「刚处理过」。
 * 这样做是因为页面在渲染中间态下字段会抖动：真机实测支付宝首页两次识别同一笔，
 * 商户名一次是「松开刷新」、一次是「上海 搜索框 搜索服务或小程序 搜索 更多操作」——
 * 只要指纹里掺了商户名，同一笔就会算出两个键，去重直接失效、浮窗连弹两次。
 *
 * @param value 指纹值，形如 `order:alipay:CFP000000000000000000` /
 *              `time:alipay:0:3.0:2026-10-15 23:14:43` / `sum:alipay:0:3.0`
 * @param ttlMs 这道指纹自身的安静期上限；最终安静期取它与「结局 TTL」的较小值
 */
data class DedupKey(val value: String, val ttlMs: Long)

/**
 * 短期去重表：**同一笔账单在短时间内只弹一次确认浮窗**。
 *
 * 解决真机上最烦人的两类体验问题：
 * 1. 用户点完「记一笔」或「忽略」后，支付结果页仍会继续抛无障碍事件，浮窗又弹一次；
 * 2. 页面渲染中间态导致字段抖动，同一笔算出不同的键，去重形同虚设。
 *
 * 三种结局都记进表里，只是安静期不同：
 *
 * | 结局 | 何时写入 | 安静期 | 理由 |
 * |------|---------|--------|------|
 * | [Outcome.CONFIRMED] | 用户点「记一笔」 | [confirmedTtlMs] | 已经落库，绝不能再弹（也不该重复记账） |
 * | [Outcome.DISMISSED] | 用户点「忽略」 | [dismissedTtlMs] | 用户明确表示这笔不用记，给他一段安静期 |
 * | [Outcome.SHOWN] | 浮窗弹出后未操作就被收起 | [shownTtlMs] | 只是短暂防抖，避免浮窗刚消失就复弹 |
 *
 * 设计要点：
 * - **纯逻辑**：不碰 Android API，可直接用 JVM 单测覆盖（见 `AutoBookParserTest`）；
 * - **键必须稳定**：指纹里不能掺「当前时间」，也不能只依赖商户名（见 [AutoBookParser.buildDedupKeys]）；
 * - **容量有限**：最多记 [capacity] 条，超出剔除最旧的一条，不会随时间无界增长；
 * - **线程安全**：事件在主线程、落库在 IO 协程，两边都会打标，方法内部加锁。
 */
class AutoBookDedup(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val confirmedTtlMs: Long = CONFIRMED_TTL_MS,
    private val dismissedTtlMs: Long = DISMISSED_TTL_MS,
    private val shownTtlMs: Long = SHOWN_TTL_MS
) {

    /** 一笔账单的处理结局 */
    enum class Outcome(val label: String) {
        /** 浮窗已弹出，用户尚未操作 */
        SHOWN("该笔浮窗刚弹过"),

        /** 用户点了「记一笔」，已落库 */
        CONFIRMED("该笔已记账"),

        /** 用户点了「忽略」 */
        DISMISSED("该笔已忽略")
    }

    private data class Entry(val outcome: Outcome, val atMs: Long, val ttlMs: Long)

    /** 按插入顺序淘汰最旧的一条，容量恒定 */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    /** 这笔账单现在能不能弹浮窗 */
    fun shouldShow(keys: List<DedupKey>, nowMs: Long): Boolean = blockedReason(keys, nowMs) == null

    /**
     * 不能弹的原因。
     *
     * 排查「为什么没弹」时要能一眼区分**是去重挡的**还是**识别失败**，所以这里返回中文原因而不是布尔值。
     *
     * @return null 表示可以弹
     */
    @Synchronized
    fun blockedReason(keys: List<DedupKey>, nowMs: Long): String? {
        for (key in keys) {
            val entry = entries[key.value] ?: continue
            val ageMs = nowMs - entry.atMs
            if (ageMs >= entry.ttlMs) {
                // 安静期已过：顺手清掉，让这笔下次出现时能重新弹
                entries.remove(key.value)
                continue
            }
            val remainSec = (entry.ttlMs - ageMs) / 1000
            return "${entry.outcome.label}（key=${key.value} 剩余 ${remainSec}s）"
        }
        return null
    }

    /** 标记「浮窗已弹出」 */
    fun markShown(keys: List<DedupKey>, nowMs: Long) = mark(keys, Outcome.SHOWN, nowMs)

    /** 标记「用户已确认记账」 */
    fun markConfirmed(keys: List<DedupKey>, nowMs: Long) = mark(keys, Outcome.CONFIRMED, nowMs)

    /** 标记「用户已忽略」 */
    fun markDismissed(keys: List<DedupKey>, nowMs: Long) = mark(keys, Outcome.DISMISSED, nowMs)

    /** 记忆条数（诊断与单测用） */
    @Synchronized
    fun size(): Int = entries.size

    /** 清空（单测与服务销毁时用） */
    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    private fun mark(keys: List<DedupKey>, outcome: Outcome, nowMs: Long) {
        prune(nowMs)
        val outcomeTtl = ttlOf(outcome)
        for (key in keys) {
            entries[key.value] = Entry(outcome, nowMs, minOf(outcomeTtl, key.ttlMs))
        }
    }

    /** 惰性清理：把已过安静期的条目删掉，避免长跑后表里全是陈旧数据 */
    private fun prune(nowMs: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (nowMs - entry.atMs >= entry.ttlMs) iterator.remove()
        }
    }

    private fun ttlOf(outcome: Outcome): Long = when (outcome) {
        Outcome.CONFIRMED -> confirmedTtlMs
        Outcome.DISMISSED -> dismissedTtlMs
        Outcome.SHOWN -> shownTtlMs
    }

    companion object {
        /** 记住最近多少笔：远超「同一笔反复触发」的并发数，又不至于常驻内存 */
        const val DEFAULT_CAPACITY = 32

        /** 已记账：10 分钟内同一笔不再弹（页面反复抛事件、用户来回切页都在这个窗口内） */
        const val CONFIRMED_TTL_MS = 10 * 60 * 1000L

        /** 已忽略：与改造前保持一致，10 分钟安静期 */
        const val DISMISSED_TTL_MS = 10 * 60 * 1000L

        /** 弹过但用户没操作（浮窗被收起）：只做短暂防抖 */
        const val SHOWN_TTL_MS = 30 * 1000L
    }
}
