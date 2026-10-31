package com.bsfeng.monitor.ui

// 定位采集健康状态的文案生成（纯函数，供设置页展示与单测）。
//
// 为什么要这一层：用户说「过一会就掉线」，但界面上原本只有「已授权」三个字 ——
// 授权正常不等于采得到点。这里把 LocationStatus 翻成三句话：上次成功多久以前、
// 订阅在不在、上一次失败到底是什么原因。判定与措辞都是纯函数，可在 JVM 单测里钉死。
//
// 口径（待机 ≠ 断档）：功耗门禁 SLEEPING 时静止不采点是设计内行为，只按 30 分钟兜底采一点。
// 所以「上一点在 30 分钟前」不代表掉线，判档必须先看门禁态 —— 见 [levelOf] 的 gate 参数。
// 时间线（过去 24 小时的分段与断档列表）由 record 层的 CoverageTimeline 负责，本文件只管徽标与副标题。
//
// 权限口径（[LocationPermissionStatus]）也在这里翻译成可执行文案：只给「大致位置」时
// 系统只派基站级点、全被精度闸丢掉，界面必须说清原因与下一步动作，不能报「已就绪」。

import com.bsfeng.monitor.record.CoverageTimeline
import com.bsfeng.monitor.record.LocationFailureReason
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.record.LocationStatus
import com.bsfeng.monitor.record.MotionGate
import com.bsfeng.monitor.ui.components.BadgeTone

/** 采集健康度的五档；档位只影响徽标颜色，文案仍逐条说明。 */
enum class LocationHealthLevel { GOOD, DORMANT, WAITING, STALE, OFF }

object LocationHealthText {

    /** 「新鲜」上限：静止节奏是 3 分钟一次，给它留一倍余量再算告警。 */
    const val FRESH_WINDOW_MS = 6 * 60 * 1000L

    /** 「刚启动、还没到第一次采样」的容忍窗口。 */
    const val WARMUP_WINDOW_MS = 5 * 60 * 1000L

    /**
     * 采集节奏的一句话说明（设置页功能索引行与位置采集细则页共用同一句）。
     *
     * 曾经写「移动时约 10 秒、静止时约 3 分钟」——功耗门禁上线后这两个数都不对了：
     * 移动 30 秒、静止根本不采（30 分钟兜底一次）。两处写死同一句，避免又各改一遍走岔。
     */
    const val PACE_SUMMARY = "移动时约 30 秒记录一次，静止时省电降频"

    /**
     * 省电待机的容忍上限：与 record 层同源（30 分钟兜底 + 5 分钟调度余量）。
     *
     * 门禁 SLEEPING 时静止不采点，最后一点常停在 30 分钟前；拿 [FRESH_WINDOW_MS] 判
     * 就会把「照常在待机」读成「已断档」，徽标常年红色。
     */
    const val DORMANT_WINDOW_MS = CoverageTimeline.DORMANT_CEILING_MS

    /** 待机副标题：说清「为什么不采点」与「兜底节奏」，不出现失败原因。 */
    private const val DORMANT_TEXT = "省电待机 · 静止时不采点，30 分钟兜底"

    /**
     * 档位判定：没有状态（未开启/读取失败）→ OFF；从未成功且还在预热 → WAITING；
     * 门禁睡着且 35 分钟内有点 → DORMANT；超过新鲜窗口 → STALE。
     *
     * @param gate 当前功耗门禁态（[com.bsfeng.monitor.record.LocationGateState.state]）；
     *   null = 未知/没在跑，按旧口径（只看新鲜窗口）判，老调用点与单测行为不变。
     */
    fun levelOf(status: LocationStatus?, gate: MotionGate.State? = null): LocationHealthLevel {
        if (status == null) return LocationHealthLevel.OFF
        val silent = status.msSinceLastSuccess
        val hasLast = status.lastSuccessAtMs != null
        if (!hasLast) {
            // 从没成功过：订阅建起来了就是「等第一个点」，否则算没跑起来
            return if (status.subscriptionActive) LocationHealthLevel.WAITING else LocationHealthLevel.OFF
        }
        val elapsed = silent ?: 0L
        // 睡着时「有点」和「正在采」是两件事：待机窗口内一律算待机，超过才是断档
        if (gate == MotionGate.State.SLEEPING && elapsed <= DORMANT_WINDOW_MS) {
            return LocationHealthLevel.DORMANT
        }
        return when {
            elapsed <= FRESH_WINDOW_MS -> LocationHealthLevel.GOOD
            elapsed <= FRESH_WINDOW_MS + WARMUP_WINDOW_MS -> LocationHealthLevel.WAITING
            else -> LocationHealthLevel.STALE
        }
    }

    /** 徽标文案（≤4 字，与设置页其它徽标等宽）。 */
    fun badge(status: LocationStatus?, gate: MotionGate.State? = null): String =
        when (levelOf(status, gate)) {
            LocationHealthLevel.GOOD -> "采集中"
            LocationHealthLevel.DORMANT -> "省电待机"
            LocationHealthLevel.WAITING -> "等待中"
            LocationHealthLevel.STALE -> "已断档"
            LocationHealthLevel.OFF -> "未运行"
        }

    /** 徽标色调：正常用成功色，断档用警告色，待机与未运行保持中性（不是故障）。 */
    fun toneOf(status: LocationStatus?, gate: MotionGate.State? = null): BadgeTone =
        when (levelOf(status, gate)) {
            LocationHealthLevel.GOOD -> BadgeTone.Success
            LocationHealthLevel.DORMANT -> BadgeTone.Neutral
            LocationHealthLevel.WAITING -> BadgeTone.Warn
            LocationHealthLevel.STALE -> BadgeTone.Danger
            LocationHealthLevel.OFF -> BadgeTone.Neutral
        }

    /**
     * 副标题：一句话说清「上次成功多久以前 + 订阅状态」，断档时补失败原因。
     *
     * 待机档只解释「静止不采点、30 分钟兜底」：待机不是故障，写失败原因会让用户去修一个没坏的东西。
     */
    fun describe(status: LocationStatus?, gate: MotionGate.State? = null): String {
        if (status == null) return "读取采集状态失败"
        val level = levelOf(status, gate)
        if (level == LocationHealthLevel.DORMANT) return DORMANT_TEXT
        val parts = ArrayList<String>(3)
        parts.add(lastSuccessText(status))
        parts.add(if (status.subscriptionActive) "持续订阅在跑" else "持续订阅未建立")
        if (level != LocationHealthLevel.GOOD) parts.add(reasonText(status.lastFailure))
        return parts.joinToString(" · ")
    }

    /** 「最后成功」的人话版本。 */
    private fun lastSuccessText(status: LocationStatus): String {
        val elapsed = status.msSinceLastSuccess
        if (status.lastSuccessAtMs == null || elapsed == null) return "还没有成功定位"
        val seconds = elapsed / 1000
        val human = when {
            seconds < 60 -> seconds.toString() + " 秒前"
            seconds < 3600 -> (seconds / 60).toString() + " 分钟前"
            else -> (seconds / 3600).toString() + " 小时前"
        }
        val accuracy = status.lastAccuracyMeters?.let { "(±" + it.toInt() + "m)" } ?: ""
        return "上次成功 " + human + accuracy
    }

    // ===== 权限口径 =====

    /**
     * 引导卡的单态文案：徽标 / 摘要 / 自检 / 已完成步数 / 三步清单头部与第 2 步。
     *
     * 放这里而不是留在 Composable 里，是为了让「只给大致位置时怎么显示」成为可单测的纯逻辑：
     * 文案错一次（比如 COARSE_ONLY 仍显示「已就绪」）用户就会一直看到绿标却查不到行程。
     * 第 2 步随状态换语义：只给大致位置时先把精度要回来，其余态让给后台授权。
     * 与原型 .open-design/41-location-health.html「位置采集 · 仅大致位置」逐字一致。
     */
    data class LocationCopy(
        val badge: String,
        val tone: BadgeTone,
        val summary: String,
        val check: String,
        val doneSteps: Int,
        val stepsHint: String,
        val step2Title: String,
        val step2Desc: String,
        val step3Title: String,
        val step3Desc: String,
    )

    /** 三步清单头部（精确位置已具备时）：顺序就是用户该做的事。 */
    private const val STEPS_HINT = "三步就绪：开启定位 → 选「始终允许」→ 关掉省电限制"

    private const val STEP2_TITLE = "选「始终允许」"

    private const val STEP2_DESC = "应用权限 › 位置信息 › 始终允许"

    private const val STEP3_TITLE = "关掉省电限制"

    private const val STEP3_DESC = "电池 › 应用省电策略 › 不限制"

    /** 只给大致位置时：先把精度要回来，再谈后台授权。 */
    private const val COARSE_STEPS_HINT = "三步就绪：开启定位 → 改成精确位置 → 选「始终允许」"

    private const val COARSE_STEP2_TITLE = "改成「精确位置」"

    private const val COARSE_STEP2_DESC = "应用权限 › 位置信息 › 使用精确位置"

    /** 大致位置态原型把「始终允许」排在第三步（精度没解决前谈后台没有意义）。 */
    private const val COARSE_STEP3_TITLE = "选「始终允许」"

    private const val COARSE_STEP3_DESC = "应用权限 › 位置信息 › 始终允许"

    /** 权限态 → 引导卡文案。只有 GRANTED 会显示「已就绪」。 */
    fun locationCopyOf(status: LocationPermissionStatus): LocationCopy = when (status) {
        LocationPermissionStatus.NOT_GRANTED -> LocationCopy(
            badge = "未授权",
            tone = BadgeTone.Neutral,
            summary = "开启后可用 · 显示地址与距离",
            check = "未授权 · 后台定位未开启",
            doneSteps = 0,
            stepsHint = STEPS_HINT,
            step2Title = STEP2_TITLE,
            step2Desc = STEP2_DESC,
            step3Title = STEP3_TITLE,
            step3Desc = STEP3_DESC,
        )
        // 只给大致位置：系统只派基站级点（500m+），过不了精度闸会被全丢。
        // 这里绝不能出现「已就绪」——否则用户看到绿标却行程空白（P0 缺陷的界面表现）。
        LocationPermissionStatus.COARSE_ONLY -> LocationCopy(
            badge = "大致位置",
            tone = BadgeTone.Warn,
            summary = "仅大致位置 · 误差可达数百米",
            check = "仅大致位置 · 点数会因精度不足被丢弃",
            doneSteps = 1,
            stepsHint = COARSE_STEPS_HINT,
            step2Title = COARSE_STEP2_TITLE,
            step2Desc = COARSE_STEP2_DESC,
            step3Title = COARSE_STEP3_TITLE,
            step3Desc = COARSE_STEP3_DESC,
        )
        LocationPermissionStatus.MISSING_BACKGROUND -> LocationCopy(
            badge = "缺后台定位",
            tone = BadgeTone.Warn,
            summary = "已授权前台定位 · 后台仍被限制",
            check = "缺后台定位 · 息屏后无法采样",
            doneSteps = 1,
            stepsHint = STEPS_HINT,
            step2Title = STEP2_TITLE,
            step2Desc = STEP2_DESC,
            step3Title = STEP3_TITLE,
            step3Desc = STEP3_DESC,
        )
        LocationPermissionStatus.GRANTED -> LocationCopy(
            badge = "已就绪",
            tone = BadgeTone.Success,
            summary = "定位已就绪 · 移动时约 30 秒采样",
            check = "已就绪 · 后台定位可用",
            doneSteps = 3,
            stepsHint = STEPS_HINT,
            step2Title = STEP2_TITLE,
            step2Desc = STEP2_DESC,
            step3Title = STEP3_TITLE,
            step3Desc = STEP3_DESC,
        )
    }


    /** 失败原因 → 可执行的提示（不是程序报错原文，而是「你该做什么」）。 */
    fun reasonText(reason: LocationFailureReason): String = when (reason) {
        LocationFailureReason.NONE -> "无失败记录"
        LocationFailureReason.PERMISSION -> "缺定位权限，去系统设置开「始终允许」"
        LocationFailureReason.THROTTLED -> "节流中，还没到下一次采样"
        LocationFailureReason.ACTIVE_FIX_TIMEOUT -> "取点超时，多半是省电策略或室内无信号"
        LocationFailureReason.LOW_CONFIDENCE -> "定位精度太差，换个位置再试"
        LocationFailureReason.SOURCE_UNAVAILABLE -> "系统没提供定位服务"
        LocationFailureReason.EXCEPTION -> "定位调用异常，已自动重试"
    }
}
