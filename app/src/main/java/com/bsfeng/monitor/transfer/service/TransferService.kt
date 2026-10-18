package com.bsfeng.monitor.transfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bsfeng.monitor.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务：让「传送」的 HTTP 端口与 UDP 发现常驻（协议 §2：端口常驻 + 实际端口随公告上报）。
 *
 * - `onCreate` 里启动 [TransferEngine]（幂等），无论耗时都想在通知里展示「端口 · 设备数」；
 * - 常驻通知带「停止」动作（`ACTION_STOP`）→ 停引擎、移通知、停服务；
 * - 服务类型 `dataSync`（targetSdk 34 要求显式声明，见 AndroidManifest）。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var engine: TransferEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val transferEngine = TransferEngine.getInstance(applicationContext)
        engine = transferEngine
        // 先挂前台通知（startForegroundService 有 5s 期限），端口在 IO 线程绑定
        startForeground(NOTIFICATION_ID, buildNotification(transferEngine.localPort, 0))
        scope.launch(Dispatchers.IO) {
            transferEngine.start()
            withContext(Dispatchers.Main.immediate) {
                notifyDevices(transferEngine.localPort, 0)
            }
        }
        // 设备数变化时刷新常驻通知
        scope.launch {
            transferEngine.devices.collect { devices ->
                notifyDevices(transferEngine.localPort, devices.count { it.online })
            }
        }
    }

    private fun notifyDevices(port: Int, onlineDevices: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(port, onlineDevices)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(engine?.localPort ?: -1, 0))
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun buildNotification(port: Int, onlineDevices: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val portText = if (port > 0) "端口 $port" else "端口未就绪"
        return NotificationCompat.Builder(this, CHANNEL_ID_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("局域网互传运行中")
            .setContentText("$portText · 在线设备 $onlineDevices 台")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_TRANSFER,
            "局域网文件互传",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持传送端口与发现服务常驻"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.bsfeng.monitor.transfer.START"
        const val ACTION_STOP = "com.bsfeng.monitor.transfer.STOP"
        const val CHANNEL_ID_TRANSFER = "lan_transfer_channel"
        private const val NOTIFICATION_ID = 5300

        /** 启动（幂等）常驻服务。 */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_START)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** 停止服务与引擎。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
