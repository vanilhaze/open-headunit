package com.andrerinas.openheadunit.smartgt

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log do pliku: przetrwa minimalizację i zabicie aplikacji. Plik: Android/data/<pakiet>/files/smartgt-log.txt */
object FileLog {
    private var file: File? = null
    private var shared: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(dir, "smartgt-log.txt").also {
            if (it.length() > 2_000_000) it.delete()
        }
        shared = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, "smartgt-log.txt")
        write("===== START ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} =====")
    }

    @Synchronized
    fun write(msg: String) {
        val line = "${fmt.format(Date())}  $msg\n"
        try { file?.appendText(line) } catch (_: Exception) {}
        try { shared?.appendText(line) } catch (_: Exception) {}
    }
}
