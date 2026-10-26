package com.andrerinas.openheadunit.smartgx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Bridge between the Android Auto decoder and the motorcycle screen.
 * The video decoder renders into [surface]; each frame is converted to JPEG ([targetW]x[targetH])
 * and exposed via [latestJpeg]. [onFrame] is called after each new frame.
 */
object SmartGxBridge {
    @Volatile var enabled = false
    @Volatile var onFrame: (() -> Unit)? = null
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var frameSeq = 0L
    /** AA decoder FPS measured over a ~1s window (frames actually decoded, before the send throttle). */
    @Volatile var fps = 0.0
    @Volatile var targetW = 864
    @Volatile var targetH = 320
    /** true: full AA image with black bars (letterbox); false: cropped to fill. */
    @Volatile var fitContain = true
    /** Overlays an FPS counter in the corner of the AA image (for on-bike testing). */
    @Volatile var showFpsOverlay = true
    @Volatile var readerW = 800
    @Volatile var readerH = 480
    @Volatile var log: (String) -> Unit = {}

    /** Caps the decoder at ~25 FPS (was 70ms / ~14 FPS). Lower further only if the phone keeps up - watch the FPS overlay. */
    private const val MIN_INTERVAL_MS = 40L
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastMs = 0L
    private var loggedSize = false
    private var fpsWindowStartMs = 0L
    private var fpsWindowCount = 0
    private var lastFpsLogMs = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fpsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; textSize = 16f; isFakeBoldText = true
    }
    private val fpsBgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    @Synchronized
    fun surface(): Surface {
        reader?.let { return it.surface }
        val t = HandlerThread("smartgx-frames").also { it.start() }
        thread = t
        // Force a CPU-readable buffer. Without this, some devices (seen on OnePlus 15 / OP60FFL1)
        // hand the decoder's output surface an opaque/tiled private format (e.g. Qualcomm UBWC)
        // even though YUV_420_888 was requested; calling img.planes[] on that then crashes natively
        // with "JNI DETECTED ERROR ... non-zero capacity for nullptr pointer ... NewDirectByteBuffer".
        val r = if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(
                readerW, readerH, ImageFormat.YUV_420_888, 3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
        } else {
            ImageReader.newInstance(readerW, readerH, ImageFormat.YUV_420_888, 3)
        }
        r.setOnImageAvailableListener({ rd -> handle(rd) }, Handler(t.looper))
        reader = r
        log("BRIDGE: ImageReader created (CPU_READ_OFTEN=${Build.VERSION.SDK_INT >= 29})")
        return r.surface
    }

    @Synchronized
    fun release() {
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
        latestJpeg = null
        loggedSize = false
    }

    private fun handle(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastMs < MIN_INTERVAL_MS) return
            lastMs = now
            if (!loggedSize) { loggedSize = true; log("BRIDGE: first AA frame ${img.width}x${img.height}") }
            val jpeg = toJpeg(img) ?: return
            latestJpeg = jpeg
            frameSeq++
            updateFps(now)
            onFrame?.invoke()
        } catch (e: Exception) {
            log("BRIDGE: frame error: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun updateFps(now: Long) {
        if (fpsWindowStartMs == 0L) { fpsWindowStartMs = now; fpsWindowCount = 0 }
        fpsWindowCount++
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000) {
            fps = fpsWindowCount * 1000.0 / elapsed
            fpsWindowStartMs = now
            fpsWindowCount = 0
            if (now - lastFpsLogMs >= 3000) {
                lastFpsLogMs = now
                log("BRIDGE: AA decoder FPS = %.1f".format(fps))
            }
        }
    }

    private fun toJpeg(img: Image): ByteArray? {
        val crop = img.cropRect
        val x0 = crop.left and 1.inv(); val y0 = crop.top and 1.inv()
        val w = (crop.width() and 1.inv()).coerceAtLeast(2)
        val h = (crop.height() and 1.inv()).coerceAtLeast(2)
        val tw = targetW; val th = targetH

        // Compute the final drawn size (post letterbox/crop) up front, so the YUV source can be
        // resampled straight to that resolution instead of the full decoder frame. Avoids ever
        // JPEG-encoding/decoding more pixels than will actually end up on screen.
        val scale = if (fitContain) minOf(tw / w.toFloat(), th / h.toFloat())
                    else maxOf(tw / w.toFloat(), th / h.toFloat())
        val dw = ((w * scale).toInt() and 1.inv()).coerceIn(2, tw and 1.inv())
        val dh = ((h * scale).toInt() and 1.inv()).coerceIn(2, th and 1.inv())
        val left = (tw - dw) / 2; val top = (th - dh) / 2

        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer

        // Nearest-neighbor resample directly from YUV into a buffer already sized dw x dh (same
        // color-conversion path as before via YuvImage/BitmapFactory - only the pixel count drops).
        val nv21 = ByteArray(dw * dh * 3 / 2)
        var o = 0
        for (r in 0 until dh) {
            val sy = (r * h / dh).coerceAtMost(h - 1)
            val base = (y0 + sy) * yP.rowStride
            for (c in 0 until dw) {
                val sx = (c * w / dw).coerceAtMost(w - 1)
                nv21[o++] = yBuf.get(base + (x0 + sx) * yP.pixelStride)
            }
        }
        for (r in 0 until dh / 2) {
            val sy = (r * h / dh).coerceAtMost(h / 2 - 1)
            val uRowBase = (y0 / 2 + sy) * uP.rowStride
            val vRowBase = (y0 / 2 + sy) * vP.rowStride
            for (c in 0 until dw / 2) {
                val sx = (c * w / dw).coerceAtMost(w / 2 - 1)
                nv21[o++] = vBuf.get(vRowBase + (x0 / 2 + sx) * vP.pixelStride)
                nv21[o++] = uBuf.get(uRowBase + (x0 / 2 + sx) * uP.pixelStride)
            }
        }

        val small = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, dw, dh, null).compressToJpeg(Rect(0, 0, dw, dh), 85, small)
        val src = BitmapFactory.decodeByteArray(small.toByteArray(), 0, small.size()) ?: return null
        val dst = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        val c = Canvas(dst)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, left.toFloat(), top.toFloat(), paint)
        src.recycle()
        if (showFpsOverlay) {
            val text = "%.1f FPS".format(fps)
            val tw2 = fpsTextPaint.measureText(text)
            c.drawRect(2f, 2f, tw2 + 10f, 22f, fpsBgPaint)
            c.drawText(text, 5f, 17f, fpsTextPaint)
        }
        val out = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.JPEG, 72, out)
        dst.recycle()
        return out.toByteArray()
    }
}
