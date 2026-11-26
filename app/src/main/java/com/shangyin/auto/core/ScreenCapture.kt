package com.shangyin.auto.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.shangyin.auto.util.LogBus
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕采集：MediaProjection + VirtualDisplay + ImageReader。
 *
 * 性能要点（v2.0.0）：
 * 1. 虚拟屏幕直接按 captureMaxWidth 等比缩小创建，从源头拿到小图，
 *    免去原先每帧「全屏 12MB Bitmap + createScaledBitmap 再次分配」的开销；
 * 2. Bitmap 走固定池复用，不再每帧 new/recycle，避免 GC 抖动导致卡顿；
 * 3. 拿一帧失败时先短睡 20ms 再试，最多 6 次，失败快速返回让主循环继续。
 *
 * 坐标换算：OCR 得到的是小图坐标，点击时需乘 [scaleX]/[scaleY] 还原到真实屏幕。
 */
class ScreenCapture(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 真实屏幕尺寸 */
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** 采集（虚拟屏幕）尺寸 */
    var frameWidth: Int = 0
        private set
    var frameHeight: Int = 0
        private set

    /** 小图坐标 → 真实坐标 的比例 */
    var scaleX: Float = 1f
        private set
    var scaleY: Float = 1f
        private set

    /** 启动失败的具体原因，供界面与日志展示 */
    var lastError: String = ""
        private set

    private var stopped = false

    /** 复用的帧缓冲（含 rowStride 对齐后的原始宽） */
    private val rawPool = ArrayDeque<Bitmap>()
    private val framePool = ArrayDeque<Bitmap>()
    private var rawBitmap: Bitmap? = null
    private var paddedWidth = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopped = true
            LogBus.warn("系统已结束屏幕录制会话")
        }
    }

    fun start(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            lastError = "屏幕录制授权未通过（resultCode=$resultCode）"
            return false
        }
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            lastError = "getMediaProjection 失败：${t.javaClass.simpleName} ${t.message}"
            LogBus.error(lastError)
            return false
        }
        if (proj == null) {
            lastError = "getMediaProjection 返回空，授权可能已过期"
            LogBus.error(lastError)
            return false
        }

        projection = proj
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            metrics.widthPixels = b.width()
            metrics.heightPixels = b.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        width = metrics.widthPixels
        height = metrics.heightPixels
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi
        else context.resources.displayMetrics.densityDpi

        // 等比缩小采集尺寸：宽度不超过 captureMaxWidth
        val capW = AutoConfig.captureMaxWidth
        val ratio = min(1f, capW.toFloat() / max(1, width))
        frameWidth = max(2, (width * ratio).toInt()) and 0xFFFFFFFE.toInt()
        frameHeight = max(2, (height * ratio).toInt()) and 0xFFFFFFFE.toInt()
        scaleX = width.toFloat() / frameWidth
        scaleY = height.toFloat() / frameHeight
        val capDpi = max(160, (dpi * ratio).toInt())

        return try {
            val ht = HandlerThread("shangyin-capture").also { it.start() }
            thread = ht
            handler = Handler(ht.looper)

            // Android 14 起强制要求：createVirtualDisplay 之前必须注册 Callback
            proj.registerCallback(projectionCallback, handler)

            val ir = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 3)
            reader = ir

            virtualDisplay = proj.createVirtualDisplay(
                "shangyin-screen",
                frameWidth,
                frameHeight,
                capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface,
                null,
                handler
            )
            if (virtualDisplay == null) {
                lastError = "createVirtualDisplay 返回空"
                LogBus.error(lastError)
                stop()
                false
            } else {
                LogBus.info("采集尺寸 ${frameWidth}x${frameHeight}（屏幕 ${width}x$height，缩放 ${"%.2f".format(1f / scaleX)}x）")
                true
            }
        } catch (t: Throwable) {
            lastError = "创建虚拟屏幕失败：${t.javaClass.simpleName} ${t.message}"
            LogBus.error(lastError)
            stop()
            false
        }
    }

    /** 抓取最新一帧（复用缓冲），失败返回 null。用完必须调用 [releaseFrame]。 */
    fun grab(): Bitmap? {
        if (stopped) return null
        val ir = reader ?: return null

        var img: Image? = null
        var tries = 0
        while (tries < 6 && img == null) {
            img = runCatching { ir.acquireLatestImage() }.getOrNull()
            if (img == null) Thread.sleep(20)
            tries++
        }
        val frame = img ?: run {
            LogBus.warn("截图超时：连续 ${tries} 次未取到画面")
            return null
        }

        return try {
            val plane = frame.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0) return null
            val rowPadding = rowStride - pixelStride * frameWidth
            val bmpW = frameWidth + rowPadding / pixelStride

            val raw = obtainRaw(bmpW)
            raw.copyPixelsFromBuffer(buffer)

            if (bmpW == frameWidth) {
                raw
            } else {
                // 有行填充：拷到目标尺寸的缓冲，复用不新建
                val out = obtainFrame()
                Canvas(out).drawBitmap(
                    raw,
                    Rect(0, 0, frameWidth, frameHeight),
                    Rect(0, 0, frameWidth, frameHeight),
                    null
                )
                out
            }
        } catch (t: Throwable) {
            LogBus.warn("画面解码失败：${t.javaClass.simpleName} ${t.message}")
            null
        } finally {
            runCatching { frame.close() }
        }
    }

    /** 归还帧缓冲，供下一轮复用 */
    fun releaseFrame(bmp: Bitmap) {
        if (bmp.isRecycled) return
        if (bmp === rawBitmap) return
        if (framePool.size < 3 && framePool.none { it === bmp }) framePool.addLast(bmp)
    }

    private fun obtainRaw(bmpW: Int): Bitmap {
        if (rawBitmap == null || paddedWidth != bmpW || rawBitmap!!.isRecycled) {
            rawBitmap?.let { if (!it.isRecycled) rawPool.addLast(it) }
            paddedWidth = bmpW
            rawBitmap = rawPool.removeLastOrNull()
                ?: Bitmap.createBitmap(bmpW, frameHeight, Bitmap.Config.ARGB_8888)
        }
        return rawBitmap!!
    }

    private fun obtainFrame(): Bitmap {
        framePool.removeLastOrNull()?.let { if (!it.isRecycled) return it }
        return Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
    }

    fun stop() {
        stopped = true
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        runCatching { thread?.quitSafely() }
        virtualDisplay = null
        reader = null
        projection = null
        handler = null
        thread = null
        runCatching { rawBitmap?.recycle() }
        rawBitmap = null
        rawPool.forEach { runCatching { it.recycle() } }
        framePool.forEach { runCatching { it.recycle() } }
        rawPool.clear()
        framePool.clear()
    }
}
