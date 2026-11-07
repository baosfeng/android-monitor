package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：收发会话的内部模型
// （接收会话 / 接收文件 / 发送会话 / 待发队列 / 发送源）。
// 只在引擎内部使用；公开的 PinPrompt 仍留在 TransferEngine 里。

import android.net.Uri
import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import java.io.File


internal class IncomingSession(
    val sessionId: String,
    val token: String,
    val senderDeviceId: String,
    val senderAlias: String,
    val files: MutableMap<String, IncomingFile>,
    var state: TransferStatus,
    val createdAtMs: Long,
) {
    var pin: String? = null
    var confirmDeadlineMs: Long = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS

    /**
     * PIN 输错次数（同 DLNA / Miracast 的口径：错了就作废会话，而不是让人继续猜）。
     *
     * 4 位 PIN 只有 10^4 种，局域网往返 <5ms，60 秒窗口里能被穷举完 —— 不限次数等于没有 PIN。
     * 计数按**会话**累积（同一会话重复 offer 不会清零），超过阈值直接拒收本次传输。
     */
    var pinFailures: Int = 0

    /** 最近一次字节进展时刻（「长时间无进展」判定）。 */
    var lastProgressAtMs: Long = createdAtMs

    /** 失败错误码 / 文案（供任务行展示）。 */
    var failureCode: Int? = null
    var failureMessage: String = ""
}

internal class IncomingFile(val file: TransferFile) {
    var received: Long = 0L
    var part: File? = null
}

/**
 * 一次发送会话（登记在 [outgoing]，键为**本端任务行**的 `sessionId`）。
 *
 * [remoteSessionId] 是对端 `/offer` 返回的会话 id，只用于 HTTP（upload / cancel）；
 * 两者分开是为了让进度回写、超时收敛与任务行始终是同一个键。
 */
internal class OutgoingSession(
    val peer: PeerDevice,
    val remoteSessionId: String,
    val token: String,
    val sources: List<SendSource>,
)

internal class PendingSend(
    val peer: PeerDevice,
    val sources: List<SendSource>,
    val sessionId: String,
)

internal class SendSource(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
)
