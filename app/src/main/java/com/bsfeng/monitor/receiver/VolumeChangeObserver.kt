package com.bsfeng.monitor.receiver

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class VolumeChangeObserver(
    private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastMusicVolume = -1
    private var lastRingVolume = -1
    private var lastNotifVolume = -1
    private var lastAlarmVolume = -1
    private var lastObserverChangeTime = 0L
    private val debounceMs = 500L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val now = System.currentTimeMillis()
            if (now - lastObserverChangeTime < debounceMs) return
            lastObserverChangeTime = now

            val music = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val ring = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val notif = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            val alarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

            checkAndEmit("music", music, lastMusicVolume, AudioManager.STREAM_MUSIC)
            checkAndEmit("ring", ring, lastRingVolume, AudioManager.STREAM_RING)
            checkAndEmit("notification", notif, lastNotifVolume, AudioManager.STREAM_NOTIFICATION)
            checkAndEmit("alarm", alarm, lastAlarmVolume, AudioManager.STREAM_ALARM)

            lastMusicVolume = music
            lastRingVolume = ring
            lastNotifVolume = notif
            lastAlarmVolume = alarm
        }
    }

    private fun checkAndEmit(
        streamType: String,
        current: Int,
        last: Int,
        streamId: Int
    ) {
        if (last >= 0 && current != last) {
            val max = audioManager.getStreamMaxVolume(streamId)
            EventCollector.emit(EventType.VOLUME_CHANGED, mapOf(
                "stream_type" to streamType,
                "previous_level" to last.toString(),
                "current_level" to current.toString(),
                "max_level" to max.toString(),
                "is_muted" to (current == 0).toString()
            ))
        }
    }

    fun register(contentResolver: ContentResolver) {
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("volume_music"),
            false, observer
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("volume_ring"),
            false, observer
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("volume_notification"),
            false, observer
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("volume_alarm"),
            false, observer
        )

        // 初始化
        lastMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        lastRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        lastNotifVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        lastAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    fun unregister(contentResolver: ContentResolver) {
        contentResolver.unregisterContentObserver(observer)
    }
}
