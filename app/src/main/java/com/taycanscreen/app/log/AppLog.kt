package com.taycanscreen.app.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Timestamped log of everything the app does, including every raw adapter exchange.
 *
 * Each app launch writes a new session file under files/logs/. The full file is what
 * "Share log" sends; the on-screen viewer shows the most recent [MAX_SCREEN_LINES] lines.
 *
 * Line format:  2026-11-08 14:03:22.517 KIND [tag] message
 *   KIND is INFO, WARN, ERR, TX (sent to adapter) or RX (received from adapter).
 *   Raw adapter text is escaped so \r, \n and other control bytes stay visible.
 */
object AppLog {
    private const val MAX_SCREEN_LINES = 1500
    private const val KEEP_SESSIONS = 15

    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileStampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val lock = Any()
    private val recent = ArrayDeque<String>()
    private var writer: BufferedWriter? = null
    private var sessionFile: File? = null
    private var lastFlushNs = 0L
    private var lastPublishNs = 0L

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = logDir(context)
            dir.mkdirs()
            sessionFiles(context).drop(KEEP_SESSIONS - 1).forEach { it.delete() }
            // A running number orders sessions correctly even when the box's clock jumps around.
            val prefs = context.getSharedPreferences("log", Context.MODE_PRIVATE)
            val seq = prefs.getInt("seq", 0) + 1
            prefs.edit().putInt("seq", seq).apply()
            val file = File(dir, "session-%05d-%s.log".format(seq, LocalDateTime.now().format(fileStampFormat)))
            sessionFile = file
            writer = BufferedWriter(FileWriter(file, true))
        }
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    private val seqPattern = Regex("""^session-(\d{5})-""")

    /** Session log files, newest first: numbered sessions by number, then older unnumbered ones. */
    fun sessionFiles(context: Context): List<File> =
        logDir(context).listFiles { f -> f.name.endsWith(".log") }.orEmpty()
            .sortedWith(
                compareByDescending<File> { seqPattern.find(it.name)?.groupValues?.get(1)?.toInt() ?: -1 }
                    .thenByDescending { it.name },
            )

    fun currentFile(): File? = synchronized(lock) {
        writer?.flush()
        sessionFile
    }

    fun info(tag: String, msg: String) = write("INFO", tag, msg)
    fun warn(tag: String, msg: String) = write("WARN", tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) =
        write("ERR", tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    /** Raw bytes/text sent to the OBD adapter. */
    fun tx(tag: String, raw: String) = write("TX", tag, escape(raw))

    /** Raw bytes/text received from the OBD adapter. */
    fun rx(tag: String, raw: String) = write("RX", tag, escape(raw))

    fun escape(raw: String): String = buildString {
        for (c in raw) {
            when {
                c == '\r' -> append("\\r")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.code < 0x20 || c.code == 0x7f -> append("\\x%02X".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun write(kind: String, tag: String, msg: String) {
        val line = "${LocalDateTime.now().format(stampFormat)} ${kind.padEnd(4)} [$tag] $msg"
        synchronized(lock) {
            val t = System.nanoTime()
            try {
                writer?.apply {
                    write(line)
                    newLine()
                    // The car link writes ~50 lines a second. Push them to storage once a second (and at
                    // once for warnings and errors) instead of on every line; the box can lose power at
                    // any moment, so never hold back more than a second.
                    if (kind == "WARN" || kind == "ERR" || t - lastFlushNs > 1_000_000_000L) {
                        flush()
                        lastFlushNs = t
                    }
                }
            } catch (_: Exception) {
                // Never let logging crash the app.
            }
            recent.addLast(line)
            while (recent.size > MAX_SCREEN_LINES) recent.removeFirst()
            // The on-screen log (Setup) is refreshed a few times a second, not copied for every line.
            if (t - lastPublishNs > 300_000_000L) {
                lastPublishNs = t
                _lines.value = recent.toList()
            }
        }
        android.util.Log.println(
            if (kind == "ERR") android.util.Log.ERROR else android.util.Log.INFO,
            "Taycan/$tag",
            msg,
        )
    }
}
