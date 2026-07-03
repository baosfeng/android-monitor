package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class CallStateReceiver : BroadcastReceiver() {
    companion object {
        private var wasOffhook = false
        private var callStartTimeMs = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.PHONE_STATE") return

        val state = intent.getStringExtra("state")
        val now = System.currentTimeMillis()

        when (state) {
            "offhook" -> {
                if (!wasOffhook) {
                    wasOffhook = true
                    callStartTimeMs = now
                    EventCollector.emit(EventType.VOICE_CALL_STARTED, summary = "开始语音通话（普通电话）")
                }
            }
            "idle" -> {
                if (wasOffhook) {
                    wasOffhook = false
                    val duration = now - callStartTimeMs
                    val durationText = when {
                        duration >= 60_000 -> "${duration / 60000}分钟${(duration % 60000) / 1000}秒"
                        else -> "${duration / 1000}秒"
                    }
                    EventCollector.emit(EventType.VOICE_CALL_ENDED, mapOf(
                        "duration_ms" to duration.toString(),
                        "duration_text" to durationText
                    ), summary = "普通电话通话结束（$durationText）")
                }
            }
        }

        EventCollector.emit(EventType.CALL_STATE_CHANGED, mapOf(
            "state" to (state ?: "unknown")
        ))
    }
}
