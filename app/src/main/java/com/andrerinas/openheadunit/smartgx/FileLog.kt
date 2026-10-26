package com.andrerinas.openheadunit.smartgx

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File log: survives minimization and the app being killed. File: Android/data/<package>/files/smartgx-log.txt */
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
                write("CRASH in thread ${t.name}: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(12).forEach { write("    at $it") }
                e.cause?.let { c -> write("  cause: ${c.javaClass.name}: ${c.message}") }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Android 11+: why the previous process ended (crash, native crash, out of memory, killed...). */
    private fun logLastExitReasons(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3)
            val f = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (i in list) {
                write("PREVIOUS PROCESS: ${f.format(Date(i.timestamp))} process=${i.processName} reason=${i.reason} status=${i.status} description=${i.description}")
                // reason 4 = CRASH (Java), 5 = CRASH_NATIVE (C/C++): try to pull a trace with where it happened.
                if (i.reason == 4 || i.reason == 5) {
                    try {
                        i.traceInputStream?.use { ins ->
                            val text = ins.bufferedReader(Charsets.UTF_8).readText()
                            if (text.isNotBlank()) {
                                write("  NATIVE TRACE (${text.length} chars, first 60 lines):")
                                text.lineSequence().take(60).forEach { write("    $it") }
                            }
                        } ?: write("  no trace available for this exit")
                    } catch (e: Exception) { write("  trace read error: ${e.message}") }
                }
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
