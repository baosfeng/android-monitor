package com.bsfeng.monitor.domain.transfer

/**
 * 局域网文件互传：纯 Kotlin 模型与协议常量（无 Android 依赖，可纯 JVM 单测）。
 *
 * 协议唯一真相：`docs/网络通信/局域网互传协议.md`。
 * 本文件里的端口 / 节奏 / 错误码与协议文档 §2 / §3 / §4.1 / §6 逐项对应，改动先改文档。
 */

/** 协议版本（发现消息 `v` 字段）。 */
const val TRANSFER_PROTOCOL_VERSION: Int = 1

// ───────────── §2 网络约定 ─────────────

/** UDP 发现端口。 */
const val DISCOVERY_PORT: Int = 53318

/** HTTP 传输端口起点，被占用时顺延到 [HTTP_PORT_MAX]。 */
const val HTTP_PORT_BASE: Int = 53319

/** HTTP 传输端口顺延上界（含）。 */
const val HTTP_PORT_MAX: Int = 53328

/** 发现组播组。 */
const val MULTICAST_GROUP: String = "239.255.77.77"

/** 兜底广播地址。 */
const val BROADCAST_ADDRESS: String = "255.255.255.255"

/** 统一路由前缀。 */
const val API_PREFIX: String = "/api/v1/"

// ───────────── §3 发现节奏 ─────────────

/** 心跳间隔。 */
const val ANNOUNCE_INTERVAL_MS: Long = 5_000L

/** 启动连发次数。 */
const val ANNOUNCE_BURST_COUNT: Int = 3

/** 启动连发间隔。 */
const val ANNOUNCE_BURST_INTERVAL_MS: Long = 500L

/** 15s 未收到公告 → 标记离线。 */
const val OFFLINE_AFTER_MS: Long = 15_000L

/** 60s 未收到公告 → 从列表移除。 */
const val REMOVE_AFTER_MS: Long = 60_000L

/** 同一 IP 对 `discover` 的应答窗口（2s 内只回一次）。 */
const val OFFER_REPLY_WINDOW_MS: Long = 2_000L

/** `/24` 逐 IP 单播的单个 IP 超时。 */
const val DISCOVERY_UNICAST_TIMEOUT_MS: Int = 300

/** `/24` 逐 IP 单播并发数。 */
const val DISCOVERY_UNICAST_CONCURRENCY: Int = 128

// ───────────── §4 传输 ─────────────

/** 流式落盘缓冲（64 KiB）。 */
const val CHUNK_SIZE: Int = 64 * 1024

/** 未配对时 PIN 确认超时。 */
const val CONFIRM_TIMEOUT_MS: Long = 30_000L

/** 60s 无字节进展判超时。 */
const val NO_PROGRESS_TIMEOUT_MS: Long = 60_000L

/** 断网重试退避（1s / 2s / 4s）。 */
val RETRY_DELAYS_MS: LongArray = longArrayOf(1_000L, 2_000L, 4_000L)

/** HTTP 连接超时。 */
const val HTTP_CONNECT_TIMEOUT_MS: Int = 3_000

/** HTTP 读超时（大于 [NO_PROGRESS_TIMEOUT_MS] 的一半，留给大块读取）。 */
const val HTTP_READ_TIMEOUT_MS: Int = 30_000

// ───────────── §6 落盘 ─────────────

/** 默认下载目录（Downloads 下的相对路径）。 */
const val DOWNLOAD_RELATIVE_DIR: String = "局域网互传"

/** 传输中的临时后缀。 */
const val PART_SUFFIX: String = ".part"

/** 错误码（§4.1）。 */
object TransferErrorCode {
    const val SUCCESS: Int = 0
    const val TOKEN_MISMATCH: Int = 4001
    const val SESSION_EXPIRED: Int = 4002
    const val OFFSET_MISMATCH: Int = 4003
    const val DISK_FULL: Int = 4004
    const val SHA256_MISMATCH: Int = 4005
    const val USER_DENIED: Int = 4006
    const val CONFIRM_TIMEOUT: Int = 4007

    /** 错误码 → 中文提示（未知码回落通用文案）。 */
    fun message(code: Int): String = when (code) {
        SUCCESS -> "成功"
        TOKEN_MISMATCH -> "配对令牌不匹配"
        SESSION_EXPIRED -> "会话不存在或已过期"
        OFFSET_MISMATCH -> "续传偏移不匹配"
        DISK_FULL -> "接收端磁盘空间不足"
        SHA256_MISMATCH -> "文件校验失败"
        USER_DENIED -> "对方拒绝接收"
        CONFIRM_TIMEOUT -> "确认超时"
        else -> "传输失败（$code）"
    }
}

/** 会话状态（§4 `data.state`）。 */
enum class TransferStatus {
    /** 初始态。 */
    IDLE,

    /** 已提交清单。 */
    OFFERED,

    /** 未配对：接收端已弹 PIN，等待发送端输入。 */
    AWAITING_CONFIRM,

    /** 已接受，可上传字节。 */
    TRANSFERRING,

    /** 完成（sha256 校验通过）。 */
    COMPLETED,

    /** 失败（带错误码）。 */
    FAILED,

    /** 本端取消。 */
    CANCELLED,

    /** 对方拒绝。 */
    DENIED,
    ;

    /** 终态（只能 Reset 回到 IDLE）。 */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == DENIED

    /** 协议线值（`POST /offer` 响应里的 `state`）。 */
    val wire: String
        get() = when (this) {
            IDLE -> "idle"
            OFFERED -> "offered"
            AWAITING_CONFIRM -> "pending"
            TRANSFERRING -> "approved"
            COMPLETED -> "completed"
            FAILED -> "failed"
            CANCELLED -> "cancelled"
            DENIED -> "denied"
        }

    companion object {
        /** 线值 → 状态；未知线值返回 null（调用方按协议异常处理）。 */
        fun fromWire(value: String?): TransferStatus? = when (value) {
            "idle" -> IDLE
            "offered" -> OFFERED
            "pending" -> AWAITING_CONFIRM
            "approved" -> TRANSFERRING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            "denied" -> DENIED
            else -> null
        }
    }
}

/** 传输方向。 */
enum class TransferDirection { SEND, RECV }

/** 设备形态（发现消息 `deviceType`）。 */
object DeviceTypes {
    const val ANDROID: String = "android"
    const val MAC: String = "macos"
    const val UNKNOWN: String = "unknown"
}

/** 单个文件（`POST /offer` 的 `files[]` 元素）。 */
data class TransferFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mime: String,
) {
    init {
        require(fileId.isNotBlank()) { "fileId 不能为空" }
        require(name.isNotBlank()) { "文件名不能为空" }
        require(size >= 0) { "文件大小不能为负：$size" }
    }

    /** `accepted[]` 元素：本端可从此偏移续传。 */
    fun acceptedJson(offset: Long): String = "{\"fileId\":\"$fileId\",\"offset\":$offset}"
}

/**
 * 在线设备（发现层聚合结果）。
 *
 * [hostAddress] 是 UDP 包的源地址，属于本端运行期信息，不参与协议字段；
 * [online] 由 [DeviceRegistry] 按 [lastSeenMs] 推导。
 */
data class PeerDevice(
    val deviceId: String,
    val alias: String,
    val deviceType: String,
    val hostAddress: String,
    val port: Int,
    val proto: String = "http",
    val caps: List<String> = listOf("send", "recv"),
    val appVersion: String = "",
    val lastSeenMs: Long = 0L,
    val online: Boolean = true,
) {
    /** 传输根地址（`http://<ip>:<port>`）。 */
    val baseUrl: String get() = "http://$hostAddress:$port"

    /** 能力校验：本端要发送时对方须声明 `recv`。 */
    fun canReceive(): Boolean = caps.isEmpty() || caps.contains("recv")
}

/** 一条传输任务（UI 列表项）。 */
data class TransferTask(
    val sessionId: String,
    val peer: PeerDevice,
    val direction: TransferDirection,
    val file: TransferFile,
    val status: TransferStatus,
    val receivedBytes: Long = 0L,
    val errorCode: Int? = null,
    val message: String = "",
    val updatedAtMs: Long = 0L,
) {
    /** 0f..1f；总量未知（0）时返回 0。 */
    val fraction: Float
        get() = if (file.size <= 0L) 0f else (receivedBytes.toFloat() / file.size).coerceIn(0f, 1f)

    /** 是否活跃（进度条需要动）。 */
    val isActive: Boolean
        get() = status == TransferStatus.TRANSFERRING || status == TransferStatus.OFFERED ||
            status == TransferStatus.AWAITING_CONFIRM
}
