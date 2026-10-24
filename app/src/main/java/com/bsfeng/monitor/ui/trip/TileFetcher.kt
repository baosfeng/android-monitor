package com.bsfeng.monitor.ui.trip

// 免费瓦片加载：内存 LRU → 磁盘缓存 → HTTP，任何环节失败都返回 null（调用方回落到无底图）。
//
// 为什么自己写而不用现成库：项目约束是「零第三方业务依赖」，网络与图片解码都走系统 API。
//
// 遵守图源使用条款的三件事：
// - 每个请求带 USER_AGENT（可识别、能联系到仓库），OSM 明确要求；
// - 同一块瓦片只下一次网，之后走磁盘缓存，缓存上限 DISK_BYTES；
// - 并发由调用方限流（见 TileOverlay.MAX_PARALLEL），不对同一域名打满。

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 瓦片读取器（进程内单例）。 */
class TileFetcher private constructor(context: Context) {

    private val root = File(context.applicationContext.cacheDir, MAP_CACHE_DIR)

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var writesSincePrune = 0

    /** 连不上的入口 → 恢复时间戳（进程内，不落盘）。 */
    private val deadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 取一张瓦片：内存 → 磁盘 → 网络，全失败返回 null。 */
    fun load(source: TileSource, ref: TileRef): Bitmap? {
        val key = TileMath.tileKey(source.id, ref)
        memory.get(key)?.let { return it }

        val file = fileOf(source.id, ref)
        readDisk(file)?.let { bitmap ->
            memory.put(key, bitmap)
            return bitmap
        }

        val fetched = fetch(source, ref) ?: return null
        memory.put(key, fetched)
        writeDisk(file, fetched)
        return fetched
    }

    /**
     * 取一张瓦片：按 [TileSource.templates] 顺序尝试，失败过的入口熔断一会儿。
     * 全都在熔断期时仍硬试第一个入口 —— 宁可慢一点，也别整屏空白。
     */
    private fun fetch(source: TileSource, ref: TileRef): Bitmap? {
        val candidates = source.templates.filterNot { isCoolingDown(it) }
            .ifEmpty { listOf(source.templates.first()) }
        for (template in candidates) {
            val url = source.url(template, ref.z, ref.x, ref.y)
            val bitmap = fetchOne(url, ref)
            if (bitmap != null) return bitmap
            coolDown(template)
        }
        return null
    }

    /** 单个入口的 HTTP 拉取 + 解码。 */
    private fun fetchOne(url: String, ref: TileRef): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/png,image/*;q=0.8")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "瓦片 HTTP " + connection.responseCode + "：" + url)
                return null
            }
            val bytes = connection.inputStream.use { input -> readLimited(input) } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: IOException) {
            Log.d(TAG, "瓦片拉取失败 " + ref.z + "/" + ref.x + "/" + ref.y + "：" + e.message)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "瓦片解码内存不足，跳过", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 该入口是否还在熔断期。 */
    private fun isCoolingDown(template: String): Boolean {
        val until = deadUntil[template] ?: return false
        if (System.currentTimeMillis() < until) return true
        deadUntil.remove(template)
        return false
    }

    private fun coolDown(template: String) {
        deadUntil[template] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
    }

    /** 读响应体，超过 MAX_BYTES 就放弃（防止异常响应把内存打爆）。 */
    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return buffer.toByteArray()
            total += read
            if (total > MAX_BYTES) return null
            buffer.write(chunk, 0, read)
        }
    }

    private fun readDisk(file: File): Bitmap? {
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                // 触碰时间戳：最近用到的瓦片在修剪时排到后面
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Throwable) {
            file.delete()
            null
        }
    }

    @Synchronized
    private fun writeDisk(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        try {
            tmp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: IOException) {
            tmp.delete()
            return
        }
        if (++writesSincePrune >= PRUNE_EVERY) {
            writesSincePrune = 0
            prune()
        }
    }

    /** 按最后访问时间淘汰，直到磁盘占用回到上限内。 */
    @Synchronized
    private fun prune() {
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.toList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= DISK_BYTES) return
            total -= file.length()
            file.delete()
        }
    }

    private fun fileOf(sourceId: String, ref: TileRef): File =
        File(File(File(File(root, sourceId), ref.z.toString()), ref.x.toString()), ref.y.toString() + ".png")

    companion object {
        private const val TAG = "TileFetcher"

        /** 图源能识别的 UA（OSM 使用条款要求带可识别的应用标识）。 */
        const val USER_AGENT = "android-monitor/1.0 (+https://github.com/baosfeng/android-monitor)"

        /** 同一时刻最多几张瓦片在路上：小屏一屏 3~8 张，4 路足够。 */
        const val MAX_PARALLEL = 4

        private const val MAP_CACHE_DIR = "map-tiles"
        private const val MEMORY_BYTES = 6 * 1024 * 1024
        private const val DISK_BYTES = 16L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 6000
        private const val MAX_BYTES = 1024 * 1024
        private const val PRUNE_EVERY = 32

        /** 入口失败后冷却 5 分钟：期间不再白等它的连接超时。 */
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: TileFetcher? = null

        fun get(context: Context): TileFetcher =
            instance ?: synchronized(this) {
                instance ?: TileFetcher(context.applicationContext).also { instance = it }
            }

        /** 清空已缓存瓦片（内存 + 磁盘）。 */
        fun clearCache(context: Context) {
            val fetcher = get(context)
            fetcher.memory.evictAll()
            fetcher.root.deleteRecursively()
            fetcher.writesSincePrune = 0
        }
    }
}
