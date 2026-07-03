package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScreenStateReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                EventCollector.emit(EventType.SCREEN_ON)
            }
            Intent.ACTION_SCREEN_OFF -> {
                EventCollector.emit(EventType.SCREEN_OFF)
            }
            Intent.ACTION_USER_PRESENT -> {
                EventCollector.emit(EventType.UNLOCK_SUCCESS)
            }
        }
    }
}
