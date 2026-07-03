package com.bsfeng.monitor.receiver

import android.os.FileObserver
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import java.io.File

/**
 * 通过 FileObserver 监听截屏目录中的新建文件，采集截屏事件。
 *
 * 监听多个常见截屏目录（兼容不同厂商），使用去重防止同一截图触发多次。
 */
class ScreenshotObserver {

    private val observers = mutableListOf<FileObserver>()
    private var lastScreenshotTime = 0L
    private var lastScreenshotPath = ""
    private val debounceMs = 3000L

    /** 常见截屏目录列表 */
    private val screenshotDirs = listOf(
        "/sdcard/DCIM/Screenshots",
        "/sdcard/Pictures/Screenshots",
        "/storage/emulated/0/DCIM/Screenshots",
        "/storage/emulated/0/Pictures/Screenshots",
        "/sdcard/Download",
        "/storage/emulated/0/Download"
    )

    fun start() {
        val validDirs = screenshotDirs.filter { File(it).isDirectory }
        validDirs.forEach { dirPath ->
            val observer = createObserver(dirPath, dirPath)
            observer.startWatching()
            observers.add(observer)
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun createObserver(path: String, displayDir: String): FileObserver {
        return object : FileObserver(File(path), CREATE or CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, fileName: String?) {
                if (fileName == null) return
                // 只关注图片文件
                if (!fileName.endsWith(".png", ignoreCase = true) &&
                    !fileName.endsWith(".jpg", ignoreCase = true) &&
                    !fileName.endsWith(".jpeg", ignoreCase = true)
                ) return

                // 防抖动：3 秒内同一路径不重复触发
                val now = System.currentTimeMillis()
                if (fileName == lastScreenshotPath && now - lastScreenshotTime < debounceMs) return
                lastScreenshotTime = now
                lastScreenshotPath = fileName

                EventCollector.emit(EventType.SCREENSHOT_TAKEN, mapOf(
                    "file_name" to fileName,
                    "directory" to displayDir
                ))
            }
        }
    }
}
