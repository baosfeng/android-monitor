package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.NO_PROGRESS_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.RETRY_DELAYS_MS
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskDeletion
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import com.bsfeng.monitor.transfer.discovery.DeviceRegistry
import com.bsfeng.monitor.transfer.discovery.UdpDiscoveryService
import com.bsfeng.monitor.transfer.policy.ProgressSpeed
import com.bsfeng.monitor.transfer.policy.ProgressThrottle
import com.bsfeng.monitor.transfer.policy.SendStallReason
import com.bsfeng.monitor.transfer.policy.SendTaskTimeoutPolicy
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import com.bsfeng.monitor.transfer.transport.MiniHttpServer
import com.bsfeng.monitor.transfer.transport.StreamCopier
import com.bsfeng.monitor.transfer.transport.TransferRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// 引擎内部常量（拆出伴生对象以免主文件过长；仅本包内使用）。
private const val DEVICE_TICK_MS = 5_000L
private const val AUTO_CLOSE_TICK_MS = 1_000L
private const val DELETED_EVENT_BUFFER = 16
private const val AUTO_CLOSE_EVENT_BUFFER = 1

/**
 * 「传送」中枢：UDP 发现 + HTTP 服务 + 收发会话（协议 §3 / §4 / §5 / §6 的落地）。
 *
 * - 进程内单例（[getInstance]），由 [TransferService] 以前台服务承载；
 * - 对外只暴露 [StateFlow]（[devices] / [tasks] / [pinPrompt] / [running]），UI 不直接碰 socket；
 * - 收：实现 [TransferRoutes]，落到 `Downloads/局域网互传/`；
 * - 发：`MiniHttpClient` 逐文件续传，断网按 1s/2s/4s 退避重试 3 次。
 *
 * 线程模型：所有网络 / 磁盘操作跑在 [scope]（IO）；公开方法可在任意线程调用。
 * [confirmTimeoutMs] 默认取协议 §4.1 的 60s；测试通过 [createForTesting] 注入短超时验证 4007。
 */
class TransferEngine internal constructor(
    internal val appContext: Context,
    internal val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    internal val noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS,
    internal val tickMs: Long = DEVICE_TICK_MS,
) : TransferRoutes {

    internal val settings = TransferSettings(appContext)
    internal val registry = DeviceRegistry()
    internal val downloads = DownloadsSink(appContext)
    /** HTTP 客户端（测试可换成只记录请求的替身，见 [createForTesting]）。 */
    internal var client: MiniHttpClient = MiniHttpClient()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 确认超时调度器：独立线程，避免协程 IO 池拥塞时超时任务迟迟不执行（影响 UI 与测试确定性）。 */
    internal val confirmTimeoutScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "transfer-confirm-timeout").apply { isDaemon = true }
    }
    /**
     * 任务历史存储：**每次访问都重新解析** helper，不在引擎实例上永久绑死一个。
     *
     * 测试里上一用例的引擎（或进程级单例）会活到下一个用例；缓存住旧 helper 时，排队中的
     * 落库会写进旧用例的临时库，而当前用例用新 helper 读不到（表现为 count=0 / 源 Uri 缺失）。
     * 生产里 application 唯一，解析结果恒为同一个 helper，没有额外开销。
     */
    internal val taskStore: TransferTaskStore
        get() = TransferTaskStore(DatabaseHelper.getInstance(appContext))
    internal val started = AtomicBoolean(false)
    /**
     * 后台 ticker 的句柄（[TransferServiceLifecycle.tickerLoop]）。
     *
     * 必须随引擎持有：stopImpl 要 cancel 它。循环条件用 [started] 而不是 [scope].isActive——
     * 引擎 scope 是进程级、永不取消，靠它判活会让每次 start 都多一条永不退出的 ticker。
     */
    @Volatile
    internal var tickerJob: Job? = null

    /** 在途落库任务：测试用它做确定性收尾，不靠 sleep 赌调度。 */
    private val pendingPersists: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 登记一条在途落库（完成后自动移出）。 */
    internal fun trackPersist(job: Job) {
        pendingPersists += job
        job.invokeOnCompletion { pendingPersists.remove(job) }
    }

    /** 仅供测试：等当前所有在途落库结束（有上限，避免异常时挂住用例）。 */
    internal suspend fun awaitPendingPersistsForTesting(timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        for (job in pendingPersists.toList()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) return
            runCatching { withTimeout(left) { job.join() } }
        }
    }
    /** ticker 实际跑过的拍数（生产自增是真实行为，测试用它断言「没有叠加多条 ticker」）。 */
    internal val tickerTicks = AtomicInteger(0)
    /** 历史是否已从库中恢复（首次进入前台 / 首次启动服务时加载一次）。 */
    internal val historyLoaded = AtomicBoolean(false)
    @Volatile
    internal var server: MiniHttpServer? = null
    @Volatile
    internal var discovery: UdpDiscoveryService? = null
    @Volatile
    internal var hapticTarget: WeakReference<View>? = null
    /** App 是否在前台可见（「后台接收」关闭时据此启停服务与发现线程）。 */
    @Volatile
    internal var appVisible: Boolean = false

    internal val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    internal val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    internal val _pinPrompt = MutableStateFlow<PinPrompt?>(null)
    internal val _running = MutableStateFlow(false)
    internal val _discoverable = MutableStateFlow(false)
    internal val _deletedTasks = MutableSharedFlow<DeletedTask>(extraBufferCapacity = DELETED_EVENT_BUFFER)
    private val _autoCloseEvents = MutableSharedFlow<Unit>(extraBufferCapacity = AUTO_CLOSE_EVENT_BUFFER)

    // 各职责的委托实现：引擎只保留状态与转发，具体逻辑见对应文件
    private val sendCoordinator: TransferSendCoordinator by lazy { TransferSendCoordinator(this) }
    internal val deletion: TransferDeletionCoordinator by lazy { TransferDeletionCoordinator(this) }
    private val actions: TransferSessionActions by lazy { TransferSessionActions(this) }
    internal val discoveryControl: TransferDiscoveryCoordinator by lazy { TransferDiscoveryCoordinator(this) }
    internal val timeouts: TransferTimeoutConvergence by lazy { TransferTimeoutConvergence(this) }
    private val receiveRoutes: TransferReceiveRoutes by lazy { TransferReceiveRoutes(this) }
    internal val receiveSupport: TransferReceiveSupport by lazy { TransferReceiveSupport(this) }
    internal val sessionTasks: TransferSessionTasks by lazy { TransferSessionTasks(this) }
    private val ops: TransferTaskOps by lazy { TransferTaskOps(this) }
    internal val fileFinisher: TransferFileFinisher by lazy { TransferFileFinisher(this) }

    /** 「自动关闭接收」倒计时（状态与循环见 ReceivingAutoCloseController）。 */
    internal val autoClose = ReceivingAutoCloseController(
        scope = scope,
        settings = settings,
        events = _autoCloseEvents,
        onExpired = { stop() },
    )

    /**
     * 删除事件（UI 用它开 5s 撤销窗口）。
     *
     * 任务列表是进程内内存态（协议未要求任务持久化）：删除只保证进程内一致，
     * 进程被回收后记录与撤销窗口一起消失。见 [TransferTaskDeletion]。
     */
    val deletedTasks: SharedFlow<DeletedTask> = _deletedTasks.asSharedFlow()

    /** 在线设备（在线优先、最近出现在前）。 */
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()
    /** 收发任务列表（新任务在前）。 */
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()
    /** 当前 PIN 交互（接收端展示 / 发送端输入）。 */
    val pinPrompt: StateFlow<PinPrompt?> = _pinPrompt.asStateFlow()
    /** 端口是否已常驻。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()
    /** 是否参与发现（「可被发现」开关）。 */
    val discoverable: StateFlow<Boolean> = _discoverable.asStateFlow()
    /** 「自动关闭接收」到期时刻（epoch ms；0 = 未计时；纯内存，进程重启归零）。 */
    val autoCloseDeadline: StateFlow<Long> = autoClose.deadline.asStateFlow()
    /** 倒计时到点事件：宿主 [TransferService] 收到后停服务；引擎计时与自身停止，服务收尾。 */
    val autoCloseEvents: SharedFlow<Unit> = _autoCloseEvents.asSharedFlow()

    /** 本端设置（UI 读写提示音开关）。 */
    fun settings(): TransferSettings = settings

    // ───────── 服务生命周期（实现见 TransferServiceLifecycle） ─────────
    /** 启动发现与服务（幂等）；返回实际 HTTP 端口（失败 / 功能关闭 -1）。 */
    fun start(): Int = startImpl()
    /** 停止发现与服务，保留 `.part` 供下次续传。 */
    fun stop() = stopImpl()

    /**
     * 仅供测试复位：停掉 ticker 并取消进程级 [scope]，让在途落库 / 网络协程立刻结束。
     *
     * 生产不要调用：scope 要跨 start/stop 复用。测试里若只 stop 不取消，上一用例排队中的
     * [persistTask] 会在下一用例里落进同一个 `DatabaseHelper`（Robolectric 跨类共享静态），
     * 把共享表写出多余行——实测会把 count 写成 6 行，导致任务历史类用例假红。
     */
    internal fun disposeForTesting() {
        tickerJob?.cancel()
        tickerJob = null
        scope.cancel()
        confirmTimeoutScheduler.shutdownNow()
    }
    /** 「自动关闭接收」设置变化后重放计时。 */
    fun refreshAutoCloseSchedule() = refreshAutoCloseScheduleImpl()
    /** 当前自动关闭剩余毫秒（未计时返回 0）。 */
    fun autoCloseRemainingMs(nowMs: Long = System.currentTimeMillis()): Long = autoClose.remainingMs(nowMs)

    // ───────── 发现与别名（实现见 TransferDiscoveryCoordinator） ─────────
    fun setDiscoverable(enabled: Boolean) = discoveryControl.setDiscoverable(enabled)
    fun setAlias(value: String) = discoveryControl.setAlias(value)
    fun setAppVisible(visible: Boolean) = discoveryControl.setAppVisible(visible)
    internal fun ensureDiscovery() = discoveryControl.ensureDiscovery()

    // ───────── 通知快捷动作：暂停 / 恢复接收 ─────────
    fun pauseOffers(minutes: Int = PAUSE_MINUTES) {
        settings.pauseOffers(minutes); _pinPrompt.value = null
    }
    fun resumeOffers() = settings.resumeOffers()
    fun isOffersPaused(nowMs: Long = System.currentTimeMillis()): Boolean = settings.isOffersPaused(nowMs)

    /** 本端设备 id / 别名（UI 展示）。 */
    val deviceId: String get() = settings.deviceId
    val alias: String get() = settings.alias

    /** 实际 HTTP 端口；未启动为 -1。 */
    val localPort: Int get() = server?.port ?: -1

    /** 设置触觉反馈目标（Compose 层的 `LocalView.current`）。 */
    fun setHapticTarget(view: View?) { hapticTarget = view?.let { WeakReference(it) } }

    // ───────── 接收（实现见 TransferReceiveRoutes） ─────────

    override suspend fun info(): ApiResponse = receiveRoutes.info()
    override suspend fun offer(body: JSONObject, pairKey: String?, source: String): ApiResponse =
        receiveRoutes.offer(body, pairKey, source)
    override suspend fun offerStatus(sessionId: String, token: String?): ApiResponse =
        receiveRoutes.offerStatus(sessionId, token)

    override suspend fun upload(
        sessionId: String,
        fileId: String,
        offset: Long,
        token: String?,
        body: InputStream,
        contentLength: Long,
    ): ApiResponse = receiveRoutes.upload(sessionId, fileId, offset, token, body, contentLength)
    override suspend fun cancel(sessionId: String, token: String?, senderDeviceId: String?): ApiResponse =
        receiveRoutes.cancel(sessionId, token, senderDeviceId)

    // ───────── 发送 ─────────
    /** 把选中的 URI 发给 [peer]（后台执行，进度写回 tasks）。 */
    fun sendFiles(peer: PeerDevice, uris: List<Uri>) = sendCoordinator.sendFiles(peer, uris)
    /** 发送端输入 PIN 后继续（协议 §5）。 */
    fun submitPin(pin: String) = sendCoordinator.submitPin(pin)

    // ───────── 用户操作（实现见 TransferSessionActions） ─────────
    /** 接收端拒绝一次待确认的传输。 */
    fun denyIncoming(sessionId: String) = actions.denyIncoming(sessionId)
    /** 关闭 PIN 弹层（发送端取消本次发送）。 */
    fun dismissPin() = actions.dismissPin()
    /** 取消一条任务（收：删 `.part`；发：尽力通知对端 `/cancel`）。 */
    fun cancelTask(sessionId: String) = actions.cancelTask(sessionId)
    /** 移除配对（设置里的「移除配对」）。 */
    fun removePairing(deviceId: String) = settings.removePairingKey(deviceId)
    /** 已配对设备 id 列表（设置区展示）。 */
    fun pairedDeviceIds(): Set<String> = settings.pairingKeys().keys

    // ───────── 删除 / 撤销（实现见 TransferDeletionTasks） ─────────
    /**
     * C-17 / C-08：按原文件与原目标重发一条终态发送任务（旧记录保留在「最近完成」）。
     *
     * 实现在 [TransferResend]：源文件已不在本机 / 对方不在线时给逐字口径的失败，
     * 不会静默什么都不做。
     */
    fun resendTask(task: TransferTask, onResult: (Result<Unit>) -> Unit = {}) =
        resendTaskImpl(task, onResult)

    /** 某条任务登记过的源文件（发送方向才有；[resendTask] 与 UI 可用性判定共用）。 */
    fun sourceUriOf(task: TransferTask): Uri? =
        sourceFiles[taskKey(task.sessionId, task.file.fileId)]

    /** 删除一条已终结任务，完成后经 [deletedTasks] 通知 UI 展示撤销窗口。 */
    fun deleteTask(sessionId: String, fileId: String) = deleteTaskImpl(sessionId, fileId)
    /** 删除的同步实现（IO 线程；测试可直接调用不走 Flow）。 */
    internal fun deleteTaskNow(sessionId: String, fileId: String): DeletedTask? =
        deletion.deleteTaskNow(sessionId, fileId)
    /** 撤销删除：把任务按原下标放回列表。 */
    fun undoDelete(deletionId: String): DeletedTask? = undoDeleteImpl(deletionId)
    /** 撤销窗口结束：丢弃快照，记录不再恢复。 */
    fun commitDelete(deletionId: String) = commitDeleteImpl(deletionId)

    /** 接收目录占用。实现在 TransferEngineTools。 */
    fun receivedStats(): DownloadsSink.ReceivedStats = receivedStatsImpl()
    /** 清理接收目录并清空对应历史；返回删除的文件条数。实现在 TransferEngineTools。 */
    fun clearReceivedHistory(): Int = clearReceivedHistoryImpl()

    // ───────── 内部类型与可变状态（扩展文件按包内可见） ─────────

    /**
     * PIN 交互（接收端展示 / 发送端输入）。
     *
     * [pin] 接收端为待核对的 4 位码、发送端为 null；[expiresAtMs] 接收端为确认截止时刻、发送端为 null。
     */
    data class PinPrompt(
        val sessionId: String,
        val peerAlias: String,
        val direction: TransferDirection,
        val pin: String?,
        val fileName: String,
        val message: String = "",
        val expiresAtMs: Long? = null,
    )

    internal val deletedSnapshots = ConcurrentHashMap<String, DeletedTask>()
    internal val sessions = ConcurrentHashMap<String, IncomingSession>()
    /** 每来源 IP 的 offer 冷却窗（SEC-04；纯逻辑见 [com.bsfeng.monitor.transfer.policy.OfferRateLimiter]）。 */
    internal val offerLimiter = com.bsfeng.monitor.transfer.policy.OfferRateLimiter()
    internal val outgoing = ConcurrentHashMap<String, OutgoingSession>()
    internal val publishedFiles = ConcurrentHashMap<String, Uri>()
    /**
     * 发送任务的**源文件** Uri（键 = [taskKey]，与 [publishedFiles] 对称：那个记接收落盘产物）。
     *
     * 为什么要记：C-17「重新发送」与 C-08「重试失败项」都要按原文件重发，而 Uri 此前只活在
     * sendFiles 的协程里，一次用完就没了 —— 于是「重新发送」只能恒不渲染。现在随任务落库
     * （见 [com.bsfeng.monitor.transfer.data.TransferTaskStore]），重启后也能按方向回填。
     */
    internal val sourceFiles = ConcurrentHashMap<String, Uri>()
    @Volatile
    internal var pendingSend: PendingSend? = null

    companion object {
        internal const val TAG = "TransferEngine"
        internal const val TokenHeader = "X-Transfer-Token"
        internal const val PairKeyHeader = "X-Pair-Key"
        internal const val SESSION_KEEP_MS = 5 * 60_000L
        internal const val DISK_MARGIN_BYTES = 16L * 1024 * 1024

        /** 删除后的撤销窗口（UI Snackbar 计时，与引擎快照寿命一致）。 */
        const val UNDO_WINDOW_MS: Long = 5_000L
        /** 通知「暂停 5 分钟」的默认时长。 */
        const val PAUSE_MINUTES = 5

        /** 进程内单例。 */
        fun getInstance(context: Context): TransferEngine = TransferEngineHolder.get(context)

        /** 仅供测试：注入更短的确认超时验证 4007（默认走协议 60s）。 */
        internal fun createForTesting(
            context: Context,
            confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
            noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS,
            tickMs: Long = DEVICE_TICK_MS,
            client: MiniHttpClient = MiniHttpClient(),
        ): TransferEngine =
            TransferEngine(context.applicationContext, confirmTimeoutMs, noProgressTimeoutMs, tickMs)
                .also { it.client = client }

        /** 单例复位（仅供测试，见 [TransferEngineHolder]）。 */
        fun resetForTesting() = TransferEngineHolder.reset()
    }
}