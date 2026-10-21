package com.bsfeng.monitor.transfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.domain.transfer.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 「传送」的服务宿主（省电模式见 [ServiceModePolicy]）。
 *
 * - **「后台接收」关闭（默认）**：只在 App 可见时运行——由 `MainActivity` 的前后台观察者
 *   `startService` / `stopSelf`；**不进前台、不挂通知**，退到后台即停止 HTTP 与 UDP 发现、
 *   从对端设备列表消失。
 * - **「后台接收」开启**：前台服务 + 常驻通知（[CHANNEL_ID_TRANSFER]：`IMPORTANCE_LOW`、静音、
 *   不震动、锁屏不显示敏感内容），通知一行文案「局域网互传 · N 台设备」，点击直达传送页。
 *
 * 通知快捷动作「暂停 5 分钟」：暂停期间 `POST /offer` 直接回 `denied`（不弹 PIN），到期自动恢复；
 * 暂停中通知显示剩余时间。传输进度复用**同一个**通知 id，传完自动回到常驻文案，不占第二条通知。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var engine: TransferEngine? = null

    /** 当前是否处于前台（挂了常驻通知）。 */
    @Volatile
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 功能关闭 = 真停活：连通道都不建、引擎不实例化、通知不出现
        if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) {
            Log.i(TAG, "传送功能已关闭：服务不启动")
            stopSelf()
            return
        }
        createChannel()
        engine = TransferEngine.getInstance(applicationContext).also { it.start() }
        scope.launch {
            TransferEngine.getInstance(applicationContext).devices.collect { refreshNotification() }
        }
        scope.launch {
            TransferEngine.getInstance(applicationContext).tasks.collect { refreshNotification() }
        }
        // 「自动关闭接收」到点：引擎发事件，这里在主线程收尾（停服务 → 常驻通知移除、磁贴回 INACTIVE）
        scope.launch {
            TransferEngine.getInstance(applicationContext).autoCloseEvents.collect {
                Log.i(TAG, "自动关闭接收：停止服务与常驻通知")
                shutdown()
            }
        }
        // 暂停窗口内每 5s 刷新一次剩余时间（不在暂停时几乎零开销）
        scope.launch {
            while (true) {
                delay(PAUSE_TICK_MS)
                if (settings().isOffersPaused()) refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关闭期间被磁贴 / 旧 PendingIntent 拉起：直接收摊，不绑定端口、不登发现
        if (!FeatureFlags.get().isEnabled(Feature.TRANSPORT)) {
            Log.i(TAG, "传送功能已关闭：忽略启动请求")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> engine?.pauseOffers()
            ACTION_RESUME -> engine?.resumeOffers()
            else -> Unit
        }
        applyMode()
        return START_STICKY
    }

    /** App 被划掉：仅前台模式随进程一起停（常驻模式保留服务）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!settings().backgroundReceive) shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.stop()
        engine = null
        foreground = false
        // 服务被系统回收后磁贴必须回到 INACTIVE
        TransferTileService.requestRefresh(this)
        super.onDestroy()
    }

    /** 按当前「后台接收」设置切换：常驻模式进前台挂通知，否则退前台、不挂通知。 */
    private fun applyMode() {
        val transferEngine = engine ?: return
        transferEngine.start()
        val background = settings().backgroundReceive
        if (ServiceModePolicy.shouldRunForeground(background)) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        } else if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        TransferTileService.requestRefresh(this)
        refreshNotification()
    }

    private fun shutdown() {
        engine?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        TransferTileService.requestRefresh(this)
        stopSelf()
    }

    private fun settings(): TransferSettings = TransferSettings(this)

    private fun activeTask(): TransferTask? =
        engine?.tasks?.value?.firstOrNull { it.isActive }

    /** 刷新通知：只有前台（常驻模式）时才有通知；传输中同一 id 显示进度。 */
    private fun refreshNotification() {
        if (!foreground) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val settings = settings()
        val paused = settings.isOffersPaused()
        val onlineDevices = engine?.devices?.value?.count { it.online } ?: 0
        val task = activeTask()
        val title = when {
            paused -> "局域网互传 · 已暂停 " + TransferFormat.windowRemaining(settings.pauseRemainingMs())
            task != null -> "局域网互传 · " + TransferFormat.progressPair(task.receivedBytes, task.file.size)
            else -> "局域网互传 · $onlineDevices 台设备"
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TRANSFER_TAB)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .addAction(0, if (paused) "恢复接收" else "暂停 5 分钟", actionIntent)
        if (task != null) {
            builder.setProgress(100, (task.fraction * 100).toInt().coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_TRANSFER,
            "局域网文件互传",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台接收已开启时的常驻状态与传输进度"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.bsfeng.monitor.transfer.START"
        const val ACTION_STOP = "com.bsfeng.monitor.transfer.STOP"
        const val ACTION_PAUSE = "com.bsfeng.monitor.transfer.PAUSE"
        const val ACTION_RESUME = "com.bsfeng.monitor.transfer.RESUME"
        const val ACTION_REFRESH = "com.bsfeng.monitor.transfer.REFRESH"
        const val CHANNEL_ID_TRANSFER = "lan_transfer_channel"

        private const val NOTIFICATION_ID = 5300
        private const val PAUSE_TICK_MS = 5_000L
        private const val TAG = "TransferService"

        /**
         * 启动服务（幂等）：常驻模式走 `startForegroundService`，仅前台模式走普通 `startService`
         * （此时 App 必须可见，由 `MainActivity` 的 ON_START 调用）。
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_START)
            runCatching {
                if (TransferSettings(context).backgroundReceive) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** 设置变化后重放一次模式（常驻 ↔ 仅前台）。 */
        fun refresh(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_REFRESH)
            runCatching {
                if (TransferSettings(context).backgroundReceive) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** 停止服务与引擎（退到后台 / 划掉任务时由 UI 层调用）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
