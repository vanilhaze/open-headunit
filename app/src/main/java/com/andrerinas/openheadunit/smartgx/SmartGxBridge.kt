package com.andrerinas.openheadunit.smartgx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Bridge between the Android Auto decoder and the motorcycle screen.
 * The video decoder renders into [surface]; each frame is converted to JPEG ([targetW]x[targetH])
 * and exposed via [latestJpeg]. [onFrame] is called after each new frame.
 *
 * Why GL and not ImageReader: on some devices (confirmed on OnePlus 15 / OP60FFL1) the AA
 * decoder's output surface hands ImageReader an opaque/tiled private buffer (e.g. Qualcomm UBWC)
 * even when YUV_420_888 + USAGE_CPU_READ_OFTEN is requested. Calling img.planes[] on that buffer
 * crashes natively ("JNI DETECTED ERROR ... non-zero capacity for nullptr pointer ...
 * NewDirectByteBuffer"), and it's unrecoverable from the Kotlin side - the OS itself can't map it
 * for CPU access. Routing the decoder's surface through a SurfaceTexture/GL external texture
 * instead works regardless of the underlying buffer format, because the GPU (not the CPU) is what
 * samples it; we then render into an offscreen FBO and read that back with glReadPixels, which is
 * always a plain RGBA buffer.
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

    /**
     * Tells the bridge what Android Auto actually negotiated for its video, once
     * AapProjectionActivity.onVideoDimensionsChanged() fires. [readerW]/[readerH] is only what we
     * *asked* AA for (MySpinLink.startAndroidAuto() forces 800x480) - AA does not always honor that
     * exactly (see the comment there about AA defaulting to portrait 1080x1920 based on the phone's
     * own screen before our override took effect). Until now the FBO was fixed at readerW/readerH
     * and drawAndCapture() always rendered a full-bleed quad into it, so any real AA frame whose
     * size/aspect differed from the assumed 800x480 got silently stretched or squashed to fit -
     * a real distortion, not just the letterboxing toJpeg() already handles correctly. Resizing the
     * FBO to the real negotiated size removes that: whatever AA actually sends is captured 1:1,
     * and toJpeg()'s letterbox math (unchanged) then fits that correctly-proportioned image into
     * the motorcycle's own screen size (targetW/targetH).
     */
    fun onVideoDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val h = handler ?: return
        h.post {
            if (width == fboW && height == fboH) return@post
            try {
                freeFbo()
                setupFbo(width, height)
                log("BRIDGE: resized render target to AA's actual video size ${width}x$height " +
                        "(we had requested ${readerW}x$readerH)")
            } catch (e: Exception) {
                log("BRIDGE: failed to resize render target to ${width}x$height: ${e.message}")
            }
        }
    }

    /** Caps the decoder at ~25 FPS (was 70ms / ~14 FPS). Lower further only if the phone keeps up - watch the FPS overlay. */
    private const val MIN_INTERVAL_MS = 40L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outputSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    // --- GL/EGL state. Touched only from the "smartgx-frames" thread, which owns the context. ---
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var oesTextureId = 0
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uSTMatrixLoc = 0
    private var uSamplerLoc = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private val stMatrix = FloatArray(16)
    private var readBuf: ByteBuffer? = null
    private var frameBitmap: Bitmap? = null
    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texCoordBuf: FloatBuffer

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

    private val QUAD_COORDS = floatArrayOf(
        -1f, -1f, 0f,
        1f, -1f, 0f,
        -1f, 1f, 0f,
        1f, 1f, 0f
    )
    private val TEX_COORDS = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """

    @Synchronized
    fun surface(): Surface {
        outputSurface?.let { return it }
        val t = HandlerThread("smartgx-frames").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        // GL/EGL init and SurfaceTexture creation must happen on the thread that will own the
        // context, so hop onto it and block this caller until it's ready.
        val latch = CountDownLatch(1)
        var created: Surface? = null
        var initError: Exception? = null
        h.post {
            try {
                initGl()
                val st = SurfaceTexture(oesTextureId)
                st.setDefaultBufferSize(readerW, readerH)
                st.setOnFrameAvailableListener({ onFrameAvailable() }, h)
                surfaceTexture = st
                created = Surface(st)
                log("BRIDGE: GL surface created (SurfaceTexture/EGL path)")
            } catch (e: Exception) {
                initError = e
                log("BRIDGE: GL init failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        initError?.let { throw it }
        outputSurface = created
        return created ?: throw IllegalStateException("SmartGxBridge: surface() init failed")
    }

    @Synchronized
    fun release() {
        val h = handler
        val latch = CountDownLatch(1)
        h?.post {
            try { releaseGl() } catch (_: Exception) {} finally { latch.countDown() }
        }
        if (h != null) { try { latch.await() } catch (_: InterruptedException) {} }
        outputSurface?.release(); outputSurface = null
        surfaceTexture?.release(); surfaceTexture = null
        thread?.quitSafely(); thread = null
        handler = null
        latestJpeg = null
        loggedSize = false
    }

    // ---- GL setup (runs on the "smartgx-frames" thread) ----

    private fun initGl() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) throw RuntimeException("eglInitialize failed")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        // 1x1 pbuffer just to have a valid current surface; all real rendering goes to the FBO.
        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surf = EGL14.eglCreatePbufferSurface(dpy, config, surfAttribs, 0)
        if (surf == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw RuntimeException("eglMakeCurrent failed")

        eglDisplay = dpy; eglContext = ctx; eglSurface = surf

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

        vertexBuf = floatBufferOf(QUAD_COORDS)
        texCoordBuf = floatBufferOf(TEX_COORDS)

        setupFbo(readerW, readerH)
    }

    private fun setupFbo(w: Int, h: Int) {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTex = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("FBO incomplete: 0x${status.toString(16)}")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboW = w; fboH = h
        readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** Releases the current FBO/texture/readback buffers so [setupFbo] can rebuild them at a new size. */
    private fun freeFbo() {
        if (fbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (fboTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0); fboTex = 0 }
        readBuf = null
        frameBitmap?.recycle(); frameBitmap = null
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $info")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $info")
        }
        return s
    }

    private fun floatBufferOf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
        }

    private fun releaseGl() {
        val dpy = eglDisplay ?: return
        try {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(dpy, it) }
            eglContext?.let { EGL14.eglDestroyContext(dpy, it) }
            EGL14.eglTerminate(dpy)
        } catch (_: Exception) {
        } finally {
            eglDisplay = null; eglContext = null; eglSurface = null
            fbo = 0; fboTex = 0; fboW = 0; fboH = 0
            readBuf = null
            frameBitmap?.recycle(); frameBitmap = null
        }
    }

    // ---- Per-frame path (runs on the "smartgx-frames" thread, via SurfaceTexture's callback) ----

    private fun onFrameAvailable() {
        val st = surfaceTexture ?: return
        val now = SystemClock.uptimeMillis()
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            log("BRIDGE: updateTexImage error: ${e.message}")
            return
        }
        // Always drain the buffer (above) even when throttled below, or the decoder stalls once
        // its small internal buffer queue fills up.
        if (now - lastMs < MIN_INTERVAL_MS) return
        try {
            st.getTransformMatrix(stMatrix)
            drawAndCapture()
            lastMs = now
            if (!loggedSize) { loggedSize = true; log("BRIDGE: first AA frame ${fboW}x${fboH}") }
            val jpeg = toJpeg(frameBitmap ?: return) ?: return
            latestJpeg = jpeg
            frameSeq++
            updateFps(now)
            onFrame?.invoke()
        } catch (e: Exception) {
            log("BRIDGE: frame error: ${e.message}")
        }
    }

    private fun drawAndCapture() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, fboW, fboH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertexBuf.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        texCoordBuf.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uSamplerLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        val buf = readBuf ?: return
        buf.rewind()
        GLES20.glReadPixels(0, 0, fboW, fboH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // glReadPixels returns bottom-up rows (GL origin is bottom-left); flip into top-down order
        // for the Bitmap.
        buf.rewind()
        val rowBytes = fboW * 4
        val full = ByteArray(rowBytes * fboH)
        buf.get(full)
        val flipped = ByteArray(full.size)
        for (row in 0 until fboH) {
            System.arraycopy(full, row * rowBytes, flipped, (fboH - 1 - row) * rowBytes, rowBytes)
        }
        frameBitmap?.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
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

    private fun toJpeg(src: Bitmap): ByteArray? {
        val w = src.width; val h = src.height
        val tw = targetW; val th = targetH

        val scale = if (fitContain) minOf(tw / w.toFloat(), th / h.toFloat())
                    else maxOf(tw / w.toFloat(), th / h.toFloat())
        val dw = (w * scale).toInt().coerceIn(1, tw)
        val dh = (h * scale).toInt().coerceIn(1, th)
        val left = (tw - dw) / 2; val top = (th - dh) / 2

        val dst = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        val c = Canvas(dst)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, null, Rect(left, top, left + dw, top + dh), paint)
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
