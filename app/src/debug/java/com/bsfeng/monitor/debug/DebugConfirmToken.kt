package com.bsfeng.monitor.debug

import java.security.SecureRandom

/**
 * 调试命令的**一次性 / 限时确认令牌**（只在 debug 变体编译，纯 JVM 可单测）。
 *
 * ## 为什么需要它（安全审计 S-01）
 *
 * `DebugCommandReceiver` 是 `exported` 的：任何同机 App 发一条显式广播就能驱动这个通道。
 * 广播本身不携带发送方 uid（`Binder.getCallingUid()` 在 receiver 里拿到的是本 App 自己），
 * 所以「谁发的」判不了；签名级自定义权限也不行 —— `adb shell am broadcast` 以 shell uid 发送，
 * 拿不到应用签名权限，加了就等于把 scripts/debug-cli.sh 一起废掉。
 *
 * 能判的只有「**调用方有没有读过本次 unlock 的回执**」：令牌用 [SecureRandom] 生成、
 * 只在 RESULT 行里出现，读取它要能读 logcat（`adb logcat`）—— 这正好是 shell / 本机调试者的
 * 能力，而普通三方 App 在 Android 4.1+ 读不到别的进程日志。
 *
 * ## 两种消耗口径
 *
 * - **限时复用**（[isValid]）：读数据与驱动界面的命令（`db` / `dump` / `ui`）用这条 ——
 *   取证一次键盘操作几十条命令，每条都重新 unlock 不现实。令牌在 [TTL_MS] 内可反复用。
 * - **消费即废**（[consume]）：不可撤销的写库命令（`clear` / `seed`）用这条 ——
 *   清表这种操作连点第二下必须失败，且成功后立刻换发新令牌（旧值作废、新值随回执返回），
 *   所以正常用法里不会出现「清完表后续命令全被拒」。
 *
 * ## 剩余攻击面（写在这里供后续审计复核）
 *
 * - **窗口期抢占**：unlock 之后 [TTL_MS] 内，同机恶意 App 若已经读到了令牌（它读不到 logcat，
 *   只能靠猜或从别的渠道拿到），仍可读一次 `db query` / 驱动一次界面。
 * - **为什么不能再收紧**：广播不带发送方 uid，这是平台约束；改成不 exported 的通道
 *   （`adb shell run-as` / 本地 socket / 每次 adb 建一个临时权限）都会改掉 debug-cli 的调用形态，
 *   需要另立方案。
 * - 令牌**不落盘**（只在进程内存），进程重启后必须重新 unlock —— 这条同时限制了它的寿命。
 * - release 包里没有这个类（debug sourceSet），也进不了 APK。
 */
internal object DebugConfirmToken {

    /**
     * 令牌寿命。
     *
     * 取 10 分钟：一次取证会话（unlock → 几十条 ui click / dump → 收尾）通常几分钟内跑完；
     * 再长就等于把「刚 unlock」这个事实的时效拖到半天，窗口期抢占的风险随之放大。
     */
    const val TTL_MS = 10 * 60 * 1000L

    private const val TOKEN_BYTES = 12

    private val random = SecureRandom()

    @Volatile
    private var current: String? = null

    @Volatile
    private var issuedAtMs: Long = 0L

    /** 签发新令牌（旧的立刻作废）；返回新值，调用方负责写进本次回执的 `confirm` 字段。 */
    @Synchronized
    fun issue(nowMs: Long): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val token = "cf-" + bytes.joinToString("") { "%02x".format(it) }
        current = token
        issuedAtMs = nowMs
        return token
    }

    /**
     * 只读校验（**不消费**）：TTL 内可反复通过，给 `db` / `dump` / `ui` 这类
     * 「可复用、但产生副作用或泄露数据」的命令用。
     */
    @Synchronized
    fun isValid(token: String?, nowMs: Long): Boolean {
        val expected = current ?: return false
        if (issuedAtMs <= 0L) return false
        if (token.isNullOrBlank() || token != expected) return false
        return nowMs - issuedAtMs <= TTL_MS
    }

    /**
     * 校验并**消费**令牌：命中即失效（一次有效），给 `clear` / `seed` 这类不可撤销的命令用。
     *
     * 过期令牌同样会被消费掉（不留「过期后再试一次」的空间），调用方随后应 [issue] 新令牌。
     *
     * @return true 表示令牌有效且已用掉
     */
    @Synchronized
    fun consume(token: String?, nowMs: Long): Boolean {
        val expected = current ?: return false
        if (issuedAtMs <= 0L) return false
        if (token.isNullOrBlank() || token != expected) return false
        val issuedAt = issuedAtMs
        current = null
        issuedAtMs = 0L
        return nowMs - issuedAt <= TTL_MS
    }

    /** 清空（测试用 / 进程内复核）。 */
    @Synchronized
    fun reset() {
        current = null
        issuedAtMs = 0L
    }
}
