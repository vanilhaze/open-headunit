package com.andrerinas.openheadunit.smartgx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SmartGX control screen: Start/Stop the service, view the log, share the log. */
class SmartGxActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startBtn: Button
    private lateinit var testBtn: Button
    private lateinit var preview: ImageView
    private var running = false
    private var testRunning = false
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLog.init(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        startBtn = Button(this).apply { text = "Start"; setOnClickListener { toggle() } }
        val shareBtn = Button(this).apply { text = "Share log"; setOnClickListener { shareLog() } }
        val aaBtn = Button(this).apply {
            text = "Android Auto (settings)"
            setOnClickListener {
                startActivity(Intent(this@SmartGxActivity, com.andrerinas.openheadunit.main.MainActivity::class.java))
            }
        }
        testBtn = Button(this).apply {
            text = "Test AA (no motorcycle)"
            setOnClickListener { toggleTest() }
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
                addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(aaBtn)
            addView(testBtn)
            addView(preview, LinearLayout.LayoutParams(-1, (120 * resources.displayMetrics.density).toInt()))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        append("SmartGX ready. Turn on BT, Wi-Fi and location, close the official mySPIN app, then press Start.")
        append("Build: ${com.andrerinas.openheadunit.BuildConfig.GIT_SHA} " +
                "(compare this against the commit a new APK was built from before reporting a test result)")
        append("Log file: ${getExternalFilesDir(null)}/smartgx-log.txt")
        append("Tip: 'Test AA (no motorcycle)' reproduces the TFT capture pipeline (GL/SmartGxBridge) " +
            "without pairing to the bike - use it to iterate on crashes/rendering from the couch.")
    }

    override fun onStart() {
        super.onStart()
        LogBus.listener = { msg -> runOnUiThread { append(msg, toFile = false) } }
    }

    override fun onStop() {
        LogBus.listener = null
        super.onStop()
    }

    private fun toggle() {
        if (running) {
            startService(Intent(this, SmartGxService::class.java).setAction(SmartGxService.ACTION_STOP))
            running = false; startBtn.text = "Start"
            return
        }
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) doStart() else requestPermissions(need.toTypedArray(), 77)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != 77) return
        val denied = perms.filterIndexed { i, _ -> res[i] != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) doStart() else append("Missing permissions: $denied")
    }

    private fun doStart() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { append("Battery: ${e.message}") }
        ContextCompat.startForegroundService(this, Intent(this, SmartGxService::class.java))
        running = true
        startBtn.text = "Stop"
    }

    private fun append(line: String, toFile: Boolean = true) {
        val t = "${fmt.format(Date())}  $line\n"
        if (toFile) FileLog.write(line)
        logView.append(t)
        if (logView.length() > 60_000) logView.text = logView.text.takeLast(40_000)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Reproduces exactly the code path that crashed in the field (AA self-mode session feeding
     * SmartGxBridge/GL), without needing the motorcycle's Bluetooth/Wi-Fi Direct/TCP handshake at
     * all - MySpinLink.startAndroidAuto() does the same SmartGxBridge setup, just gated behind
     * that whole mySPIN connection. Captured frames are shown live in [preview] so rendering bugs
     * (orientation, color, scaling) are visible here too, not just crashes.
     */
    private fun toggleTest() {
        if (testRunning) stopTest() else startTest()
    }

    private fun startTest() {
        SmartGxBridge.log = { msg -> runOnUiThread { append(msg, toFile = false) } }
        try {
            val st = com.andrerinas.openheadunit.App.provide(this).settings
            st.resolutionId = 1
            st.screenOrientation = com.andrerinas.openheadunit.utils.Settings.ScreenOrientation.LANDSCAPE
        } catch (e: Exception) { append("TEST: failed to set resolution: ${e.message}") }
        SmartGxBridge.readerW = 800; SmartGxBridge.readerH = 480
        SmartGxBridge.targetW = 864; SmartGxBridge.targetH = 320
        SmartGxBridge.fitContain = true
        SmartGxBridge.showFpsOverlay = true
        SmartGxBridge.latestJpeg = null
        SmartGxBridge.enabled = true
        SmartGxBridge.onFrame = {
            val jpeg = SmartGxBridge.latestJpeg
            if (jpeg != null) {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) runOnUiThread { preview.setImageBitmap(bmp) }
            }
        }
        try {
            val i = Intent(this, com.andrerinas.openheadunit.aap.AapService::class.java)
                .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_START_SELF_MODE)
            ContextCompat.startForegroundService(this, i)
            append("TEST: Android Auto self-mode started (864x320 TFT emulation, no motorcycle needed)")
        } catch (e: Exception) { append("TEST: failed to start: ${e.message}") }
        testRunning = true
        testBtn.text = "Stop test"
    }

    private fun stopTest() {
        SmartGxBridge.enabled = false
        SmartGxBridge.onFrame = null
        try {
            val i = Intent(this, com.andrerinas.openheadunit.aap.AapService::class.java)
                .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SELF_MODE)
            startService(i)
        } catch (e: Exception) { append("TEST: stop error: ${e.message}") }
        SmartGxBridge.release()
        preview.setImageDrawable(null)
        append("TEST: stopped")
        testRunning = false
        testBtn.text = "Test AA (no motorcycle)"
    }

    private fun shareLog() {
        val f = File(File(cacheDir, "shared"), "smartgx-log.txt")
        if (!f.exists() || f.length() == 0L) { append("No data to share."); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Send log"))
        } catch (e: Exception) { append("Sharing failed: ${e.message}") }
    }
}
