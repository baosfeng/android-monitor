package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.PHONE_STATE") return

        val state = intent.getStringExtra("state")
        val stateStr = when (state) {
            "idle" -> "idle"
            "ringing" -> "ringing"
            "offhook" -> "offhook"
            else -> return
        }

        // 不上报电话号码
        EventCollector.emit(EventType.CALL_STATE_CHANGED, mapOf(
            "state" to stateStr
        ))
    }
}
