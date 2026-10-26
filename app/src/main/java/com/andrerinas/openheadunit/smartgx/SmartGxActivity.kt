package com.andrerinas.openheadunit.smartgx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekran sterujacy SmartGX: Start/Stop uslugi, podglad logu, udostepnianie logu. */
class SmartGxActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startBtn: Button
    private var running = false
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLog.init(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        startBtn = Button(this).apply { text = "Start"; setOnClickListener { toggle() } }
        val shareBtn = Button(this).apply { text = "Udostepnij log"; setOnClickListener { shareLog() } }
        val aaBtn = Button(this).apply {
            text = "Android Auto (ustawienia)"
            setOnClickListener {
                startActivity(Intent(this@SmartGxActivity, com.andrerinas.openheadunit.main.MainActivity::class.java))
            }
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
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        append("SmartGX gotowy. Wlacz BT, Wi-Fi i lokalizacje, zamknij oficjalne mySPIN, potem Start.")
        append("Log w pliku: ${getExternalFilesDir(null)}/smartgx-log.txt")
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
        if (denied.isEmpty()) doStart() else append("Brak uprawnien: $denied")
    }

    private fun doStart() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { append("Bateria: ${e.message}") }
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

    private fun shareLog() {
        val f = File(File(cacheDir, "shared"), "smartgx-log.txt")
        if (!f.exists() || f.length() == 0L) { append("Brak danych do udostepnienia."); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Wyslij log"))
        } catch (e: Exception) { append("Udostepnianie nie dziala: ${e.message}") }
    }
}
