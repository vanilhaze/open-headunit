package com.andrerinas.openheadunit.smartgx

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log do pliku: przetrwa minimalizację i zabicie aplikacji. Plik: Android/data/<pakiet>/files/smartgx-log.txt */
object FileLog {
    private var file: File? = null
    private var shared: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(dir, "smartgx-log.txt").also {
            if (it.length() > 2_000_000) it.delete()
        }
        shared = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, "smartgx-log.txt")
        write("===== START ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} =====")
        logLastExitReasons(ctx)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write("CRASH w watku ${t.name}: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(12).forEach { write("    at $it") }
                e.cause?.let { c -> write("  przyczyna: ${c.javaClass.name}: ${c.message}") }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Android 11+: dlaczego poprzedni proces zostal zakonczony (crash, native crash, brak pamieci, zabicie...). */
    private fun logLastExitReasons(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3)
            val f = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (i in list) {
                write("POPRZEDNI PROCES: ${f.format(Date(i.timestamp))} proces=${i.processName} powod=${i.reason} status=${i.status} opis=${i.description}")
            }
        } catch (e: Exception) { write("exit reasons: ${e.message}") }
    }

    @Synchronized
    fun write(msg: String) {
        val line = "${fmt.format(Date())}  $msg\n"
        try { file?.appendText(line) } catch (_: Exception) {}
        try { shared?.appendText(line) } catch (_: Exception) {}
    }
}
