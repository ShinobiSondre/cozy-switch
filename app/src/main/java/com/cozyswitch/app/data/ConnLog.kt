package com.cozyswitch.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A short log of every connection attempt (kept on the phone), so failures can be seen and shared. */
object ConnLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "connection-log.txt")
        try { file!!.readLines().takeLast(MAX).forEach { lines.addLast(it) } } catch (_: Exception) { }
    }

    @Synchronized
    fun add(ctx: Context?, msg: String) {
        if (ctx != null) init(ctx)
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        try {
            file?.let { f -> if (lines.size % 50 == 0) f.writeText(lines.joinToString("\n") + "\n") else f.appendText(line + "\n") }
        } catch (_: Exception) { }
    }

    @Synchronized fun all(ctx: Context): List<String> { init(ctx); return lines.toList() }

    @Synchronized fun clear(ctx: Context) { init(ctx); lines.clear(); try { file?.writeText("") } catch (_: Exception) { } }
}
