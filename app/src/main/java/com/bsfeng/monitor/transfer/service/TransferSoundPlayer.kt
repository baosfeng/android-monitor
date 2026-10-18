package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 音效与触觉（协议 §7：零音频资源）。
 *
 * | 事件 | 实现 | 默认 |
 * |------|------|------|
 * | 传输完成 | `RingtoneManager` 系统通知音 + `CONFIRM` 震动 | 开 |
 * | 传输失败 | `ToneGenerator.TONE_PROP_NACK` + `REJECT` 震动 | 关 |
 * | 新设备出现 | 仅 `CONFIRM` 震动 | 关 |
 *
 * 同一事件 [DEDUP_WINDOW_MS] 内去重；[View] 为空时只放音不震动（引擎可能在没有 Compose 界面时触发）。
 *
 * `CONFIRM` / `REJECT` 是 API 30 起的常量（minSdk 29），按 [Build.VERSION.SDK_INT] 守卫并在
 * API 29 回落 `VIRTUAL_KEY` / `LONG_PRESS`：语义相近、且不会在旧系统上触发未知常量。
 */
object TransferSoundPlayer {

    /** 音效事件。 */
    enum class Event { COMPLETE, FAILURE, NEW_DEVICE }

    private const val TAG = "TransferSound"
    private const val DEDUP_WINDOW_MS = 500L
    private const val TONE_DURATION_MS = 200

    private val handler = Handler(Looper.getMainLooper())
    private val lastPlayedAt = HashMap<Event, Long>()
    private var toneGenerator: ToneGenerator? = null

    /**
     * 按事件与设置播放。
     *
     * @param hapticTarget 用于触觉反馈的 View（UI 侧传 `LocalView.current`）。
     */
    fun play(
        context: Context,
        event: Event,
        settings: TransferSettings,
        hapticTarget: View? = null,
    ) {
        val now = System.currentTimeMillis()
        val last = lastPlayedAt[event]
        if (last != null && now - last < DEDUP_WINDOW_MS) return
        lastPlayedAt[event] = now

        when (event) {
            Event.COMPLETE -> {
                if (settings.soundOnComplete) playNotificationRingtone(context)
                haptic(hapticTarget, HapticAction.CONFIRM)
            }

            Event.FAILURE -> {
                if (settings.soundOnFailure) playNegativeTone()
                haptic(hapticTarget, HapticAction.REJECT)
            }

            Event.NEW_DEVICE -> {
                if (settings.soundOnNewDevice) haptic(hapticTarget, HapticAction.CONFIRM)
            }
        }
    }

    /** 清空去重表（测试 / 停止传输时用）。 */
    fun reset() {
        lastPlayedAt.clear()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }

    private fun playNotificationRingtone(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return@runCatching
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        }.onFailure { Log.w(TAG, "系统通知音播放失败: ${it.message}") }
    }

    private fun playNegativeTone() {
        runCatching {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME).also {
                toneGenerator = it
            }
            generator.startTone(ToneGenerator.TONE_PROP_NACK, TONE_DURATION_MS)
            // 播完释放原生音轨（下次失败再懒创建），避免长期占用 AudioTrack
            handler.postDelayed({
                runCatching { generator.release() }
                if (toneGenerator === generator) toneGenerator = null
            }, TONE_DURATION_MS + RELEASE_DELAY_MS)
        }.onFailure { Log.w(TAG, "提示音播放失败: ${it.message}") }
    }

    private fun haptic(target: View?, action: HapticAction) {
        if (target == null) return
        val constant = action.resolve()
        // 引擎在 IO 线程触发，触觉反馈回主线程执行
        handler.post {
            runCatching { target.performHapticFeedback(constant) }
                .onFailure { Log.w(TAG, "触觉反馈失败: ${it.message}") }
        }
    }

    /** 触觉动作 → 系统常量（含 API 30 守卫）。 */
    private enum class HapticAction {
        CONFIRM,
        REJECT,
        ;

        /** 解析为本机可用的 [HapticFeedbackConstants] 常量。 */
        fun resolve(): Int {
            val atLeastR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            return when (this) {
                CONFIRM -> if (atLeastR) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }

                REJECT -> if (atLeastR) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
        }
    }

    private const val VOLUME = 70
    private const val RELEASE_DELAY_MS = 100L
}
