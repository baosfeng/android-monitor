package com.bsfeng.monitor.debug

/**
 * 调试命令的**门槛分档**（纯逻辑，纯 JVM 可单测）。
 *
 * ## 三档（安全审计 S-01，2026-11 修订）
 *
 * | 档 | 命令 | 门槛 |
 * |---|---|---|
 * | [Level.FREE] | `unlock` / `state` / `heartbeat` / `open` / `sample_location` | 无（unlock 本身就是开门） |
 * | [Level.CONFIRM_REUSABLE] | `dump` / `db` / `ui`（**含** click / longclick / scroll / swipe / back / home / recents） | 已 unlock + TTL 内的 confirm 令牌 |
 * | [Level.CONFIRM_ONCE] | `clear` / `seed` | 已 unlock + confirm 令牌（消费即废） |
 *
 * ## 为什么 `ui` 不能停在「已 unlock 就行」
 *
 * [DebugUiBridge] 用无障碍 `performAction` / `dispatchGesture` **真执行**点击、长按、滑动、
 * 返回、主页、任务键 —— 一旦放行就是「远程驱动整台手机的触摸与导航」，再加上 dump 能读到
 * 屏幕上的全部文本（账目、聊天、通知）。它的副作用比 `db query` 重一档，所以和读数据一起
 * 归到「要令牌」。
 *
 * ## 为什么令牌是「TTL 内复用」而不是「每条命令一个」
 *
 * `ui` 是 agent 操作手机界面的唯一通道：一次取证 / 一次点击验证要连发几十条命令。
 * 每条都要重新 unlock 会让 debug-cli 实际不可用（用法硬要求：子命令形态不许变）。
 * 复用窗口的口径是「刚 unlock 过」这件事本身 —— 广播不传发送方 uid，能判的只有这一点。
 */
internal object DebugCommandGates {

    /** 命令需要的门槛。 */
    enum class Level {
        /** 免鉴权：只影响本 App 自己的界面 / 记录，不泄露、不驱动、不清库。 */
        FREE,

        /** 要令牌、TTL 内可复用：读敏感数据（dump / db）与驱动界面（ui）。 */
        CONFIRM_REUSABLE,

        /** 要令牌且消费即废：不可撤销的写库（clear / seed）。 */
        CONFIRM_ONCE,
    }

    /**
     * 物理分档表。
     *
     * 用 `when` + 穷举而不是映射表：新增命令时编译器会强制在这里表态，
     * 不会出现「新命令默认落进 FREE」这种静默降级。
     */
    fun forCommand(command: DebugCommand): Level = when (command) {
        // ── 免鉴权 ──
        DebugCommand.Unlock,
        DebugCommand.State,
        DebugCommand.Heartbeat,
        is DebugCommand.Open,
        is DebugCommand.SampleLocation,
        -> Level.FREE

        // ── 读敏感数据 + 驱动界面：副作用真实存在，令牌 TTL 内可复用 ──
        is DebugCommand.Dump,
        is DebugCommand.Db,
        is DebugCommand.Ui,
        -> Level.CONFIRM_REUSABLE

        // ── 不可撤销的写库：消费即废 ──
        is DebugCommand.Clear,
        is DebugCommand.Seed,
        -> Level.CONFIRM_ONCE
    }

}
