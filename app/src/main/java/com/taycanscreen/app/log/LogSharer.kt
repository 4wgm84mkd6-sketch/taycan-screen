package com.taycanscreen.app.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.taycanscreen.app.BuildConfig

object LogSharer {
    /** Text copies are capped so apps that only accept text (chat, notes) don't choke. */
    private const val MAX_TEXT_CHARS = 90_000

    /**
     * Opens Android's share menu with the full session log attached as a file, plus the
     * log text itself (the most recent part if it is very long) for apps that only take text.
     */
    fun share(context: Context): Boolean {
        val file = AppLog.currentFile() ?: return false
        AppLog.info("log", "Sharing log ${file.name} (${file.length()} bytes)")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val full = file.readText()
        val text = if (full.length <= MAX_TEXT_CHARS) full
        else "[first ${full.length - MAX_TEXT_CHARS} characters cut; the attached file has everything]\n" +
            full.takeLast(MAX_TEXT_CHARS)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Taycan Screen log, build ${BuildConfig.VERSION_CODE}, ${file.name}")
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share log").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }
}
