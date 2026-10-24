package com.andrerinas.openheadunit.smartgt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Most miedzy dekoderem Android Auto a ekranem motocykla.
 * Dekoder wideo renderuje do [surface]; kazda klatka jest zamieniana na JPEG ([targetW]x[targetH])
 * i udostepniana w [latestJpeg]. [onFrame] jest wolane po nowej klatce.
 */
object SmartGtBridge {
    @Volatile var enabled = false
    @Volatile var onFrame: (() -> Unit)? = null
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var frameSeq = 0L
    /** FPS dekodera AA liczony w oknie ~1 s (klatki faktycznie zdekodowane, przed throttlingiem na wysylke). */
    @Volatile var fps = 0.0
    @Volatile var targetW = 864
    @Volatile var targetH = 320
    /** true: caly obraz AA z czarnymi pasami; false: przyciecie do wypelnienia. */
    @Volatile var fitContain = true
    /** Naklada licznik FPS w rogu obrazu AA (do testow na motocyklu). */
    @Volatile var showFpsOverlay = true
    @Volatile var readerW = 800
    @Volatile var readerH = 480
    @Volatile var log: (String) -> Unit = {}

    private const val MIN_INTERVAL_MS = 70L
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
        val t = HandlerThread("smartgt-frames").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(readerW, readerH, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd -> handle(rd) }, Handler(t.looper))
        reader = r
        log("MOST: utworzono ImageReader")
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
            if (!loggedSize) { loggedSize = true; log("MOST: pierwsza klatka AA ${img.width}x${img.height}") }
            val jpeg = toJpeg(img) ?: return
            latestJpeg = jpeg
            frameSeq++
            updateFps(now)
            onFrame?.invoke()
        } catch (e: Exception) {
            log("MOST: blad klatki: ${e.message}")
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
                log("MOST: FPS dekodera AA = %.1f".format(fps))
            }
        }
    }

    private fun toJpeg(img: Image): ByteArray? {
        val crop = img.cropRect
        val x0 = crop.left and 1.inv(); val y0 = crop.top and 1.inv()
        val w = (crop.width() and 1.inv()).coerceAtLeast(2)
        val h = (crop.height() and 1.inv()).coerceAtLeast(2)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)
        val yBuf = yP.buffer
        var o = 0
        for (r in 0 until h) {
            val base = (y0 + r) * yP.rowStride + x0 * yP.pixelStride
            for (c in 0 until w) nv21[o++] = yBuf.get(base + c * yP.pixelStride)
        }
        val uBuf = uP.buffer; val vBuf = vP.buffer
        val interleaved = vP.pixelStride == 2 && uP.pixelStride == 2
        val row = ByteArray(w)
        for (r in 0 until h / 2) {
            val uBase = (y0 / 2 + r) * uP.rowStride + (x0 / 2) * uP.pixelStride
            val vBase = (y0 / 2 + r) * vP.rowStride + (x0 / 2) * vP.pixelStride
            if (interleaved) {
                // bufor V zawiera V U V U... = uklad NV21; kopiujemy calymi wierszami
                val n = minOf(w, vBuf.limit() - vBase)
                vBuf.position(vBase)
                vBuf.get(nv21, o, n)
                o += w
            } else {
                for (c in 0 until w / 2) {
                    nv21[o++] = vBuf.get(vBase + c * vP.pixelStride)
                    nv21[o++] = uBuf.get(uBase + c * uP.pixelStride)
                }
            }
        }
        val full = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, full)
        val src = BitmapFactory.decodeByteArray(full.toByteArray(), 0, full.size()) ?: return null
        val tw = targetW; val th = targetH
        val dst = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        val c = Canvas(dst)
        c.drawColor(Color.BLACK)
        val scale = if (fitContain) minOf(tw / w.toFloat(), th / h.toFloat())
                    else maxOf(tw / w.toFloat(), th / h.toFloat())
        val dw = (w * scale).toInt(); val dh = (h * scale).toInt()
        val left = (tw - dw) / 2; val top = (th - dh) / 2
        c.drawBitmap(src, null, Rect(left, top, left + dw, top + dh), paint)
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
