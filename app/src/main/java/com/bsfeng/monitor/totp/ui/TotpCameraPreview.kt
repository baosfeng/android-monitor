package com.bsfeng.monitor.totp.ui

// 扫码页的真实相机预览 —— 系统 Camera2，不引 CameraX / 任何新依赖。
//
// 链路：TextureView 预览 → ImageReader(YUV_420_888) 取帧 → 300ms 节流 → 拷 Y 平面 →
// 解码线程喂 TotpQrDecoder.decodeLuminance → 结果去重 → 主线程回调上抛（由宿主走 addFromUri）。
//
// 线程：相机开放 / 会话 / 取帧回调在 cameraThread；zxing 在 decodeThread（主线程零解码）。
// 生命周期：stop() 释放会话与设备（切后台 / 离开扫码页），release() 再退出线程。
// 错误一律吞掉并停在「无预览」状态：相机被占用 / 无权限时不能把 App 带崩。

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bsfeng.monitor.totp.TotpQrDecoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

/**
 * 取景框预览：解出二维码文本就通过 [onDecoded] 回调上抛（不可信文本，校验在数据层）。
 *
 * @param active false 时释放相机（页面不可见 / 已识别成功）
 */
@Composable
fun TotpCameraPreview(
    active: Boolean,
    modifier: Modifier = Modifier,
    onDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) { TotpCameraController(context) }
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    // SharedFlow → 回调（已经切回主线程，落库 / 改路由都在主线程）
    LaunchedEffect(controller) {
        controller.decoded.collect { text -> currentOnDecoded(text) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextureView(ctx) },
        update = { view -> controller.attach(view) },
    )
    DisposableEffect(controller, active) {
        if (active) controller.start() else controller.stop()
        onDispose { if (active) controller.stop() }
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
}

/**
 * Camera2 会话与解码循环。
 *
 * 一个实例对应一次扫码页进入；页面销毁时 [release] 必须被调用（线程与设备句柄都不留）。
 */
internal class TotpCameraController(context: Context) {

    private val appContext = context.applicationContext
    private val cameraThread = HandlerThread("totp-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val decodeThread = HandlerThread("totp-decode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private val decoder = TotpQrDecoder()

    private val frameGate = TotpFrameGate()
    private val resultGate = TotpResultGate()
    // Channel 而不是 SharedFlow：解码可能早于界面开始收集（首帧很快），
    // SharedFlow 无订阅者时会丢事件，而结果去重会让同一个码不再上抛 —— 一次丢失就是「扫不出来」。
    private val decodedChannel = Channel<String>(Channel.BUFFERED)

    /** 解出的二维码原文（去重后，未被消费就缓冲）。 */
    val decoded: Flow<String> = decodedChannel.receiveAsFlow()

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    @Volatile
    private var active = false

    @Volatile
    private var released = false

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (active) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun attach(view: TextureView) {
        if (released) return
        textureView = view
        view.surfaceTextureListener = surfaceListener
        if (view.isAvailable && active) openCamera()
    }

    fun start() {
        if (released || active) return
        active = true
        frameGate.reset()
        resultGate.reset()
        if (textureView?.isAvailable == true) openCamera()
    }

    /** 停止取帧并释放会话 / 设备（线程保留，[start] 可重开）。 */
    fun stop() {
        active = false
        closeCamera()
    }

    /** 页面销毁：停相机 + 退出两条后台线程，句柄不留。 */
    fun release() {
        if (released) return
        released = true
        active = false
        closeCamera()
        cameraThread.quitSafely()
        decodeThread.quitSafely()
        decodedChannel.close()
    }

    private fun openCamera() {
        if (released || cameraDevice != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(CameraManager::class.java) ?: return
        val cameraId = runCatching { pickCameraId(manager) }.getOrNull() ?: return
        runCatching {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (released || !active) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createSession()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        cameraDevice = null
                    }
                },
                cameraHandler,
            )
        }
    }

    private fun pickCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        val back = ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        return back ?: ids.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val device = cameraDevice ?: return
        val view = textureView ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        val manager = appContext.getSystemService(CameraManager::class.java) ?: return
        val map = runCatching {
            manager.getCameraCharacteristics(device.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() ?: return

        // 预览尺寸：与视图比例最接近的一档（不超过 1080p，省电且够扫）
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width <= 1920 && it.height <= 1080 }
        val preview = previewSizes.minByOrNull { abs(it.width * view.height - it.height * view.width) }
            ?: return
        // 解码帧：够大能扫、够小不拖慢（优先 >=720 宽里最小的一档）
        val frameSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val frame = frameSizes.filter { it.width >= 720 }.minByOrNull { it.width }
            ?: frameSizes.minByOrNull { it.width }
            ?: return

        surfaceTexture.setDefaultBufferSize(preview.width, preview.height)
        // 每次开会话新建一个 Surface，closeCamera 时释放（反复进出不攒 fd）
        runCatching { previewSurface?.release() }
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        val newReader = runCatching {
            ImageReader.newInstance(frame.width, frame.height, ImageFormat.YUV_420_888, 2)
        }.getOrNull() ?: return
        newReader.setOnImageAvailableListener({ onFrame(it) }, cameraHandler)
        reader = newReader

        val targets = listOf(surface, newReader.surface)
        runCatching {
            device.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (released || !active) {
                            configured.close()
                            return
                        }
                        session = configured
                        val request = runCatching {
                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                addTarget(newReader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                        }.getOrNull() ?: return
                        runCatching { configured.setRepeatingRequest(request, null, cameraHandler) }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                    }
                },
                cameraHandler,
            )
        }
    }

    /** 相机回调线程：只做「节流 + 拷 Y 平面 + 投递解码」，zxing 不在这里跑。 */
    private fun onFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (released || !active) return
            if (!frameGate.tryAcquire(SystemClock.elapsedRealtime())) return
            val plane = image.planes.firstOrNull() ?: return
            val luminance = copyLuminance(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                width = image.width,
                height = image.height,
            ) ?: return
            val width = image.width
            val height = image.height
            // 必须在 close 前把字节拷出来：Image 关闭后 buffer 失效
            decodeHandler.post { decodeFrame(luminance, width, height) }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun decodeFrame(luminance: ByteArray, width: Int, height: Int) {
        if (released || !active) return
        val text = runCatching { decoder.decodeLuminance(luminance, width, height) }.getOrNull() ?: return
        if (!resultGate.accept(text)) return
        decodedChannel.trySend(text)
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { reader?.close() }
        reader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        frameGate.reset()
    }
}
