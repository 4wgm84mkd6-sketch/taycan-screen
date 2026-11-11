package com.taycanscreen.app.log

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Screenshots of our own screen for the log (owner, 2026-11-09). Android lets an app copy its own
 * window without asking; other apps (Waze, Spotify) would need a permission prompt each time, so only
 * our screen is captured, and only while it's in front.
 *
 * Saved as JPEG (≤ 256 KB) in files/screenshots, newest [KEEP] kept; [LogUploader] sends the unsent
 * ones with the log, then marks them "*.sent.jpg".
 */
object Screenshots {
    private const val TAG = "shot"
    private const val KEEP = 16
    private const val MAX_BYTES = 250 * 1024
    private val io = Executors.newSingleThreadExecutor()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    @Volatile private var activity: WeakReference<Activity>? = null
    @Volatile private var appContext: Context? = null

    /** Called from the activity's onResume / onPause. */
    fun attach(a: Activity?) {
        activity = a?.let { WeakReference(it) }
        if (a != null) appContext = a.applicationContext
    }

    fun dir(context: Context) = File(context.filesDir, "screenshots")

    /** Copies our window to a JPEG. [onDone] gets true when saved (on the main thread). */
    fun capture(reason: String, onDone: ((Boolean) -> Unit)? = null) {
        val a = activity?.get()
        val ctx = appContext
        val view = a?.window?.decorView
        if (a == null || ctx == null || view == null || view.width <= 0 || view.height <= 0) {
            onDone?.invoke(false)
            return
        }
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(a.window, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    AppLog.warn(TAG, "Screenshot ($reason) failed: PixelCopy result $result")
                    bmp.recycle()
                    onDone?.invoke(false)
                    return@request
                }
                io.execute {
                    val ok = save(ctx, bmp, reason)
                    bmp.recycle()
                    Handler(Looper.getMainLooper()).post { onDone?.invoke(ok) }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            AppLog.warn(TAG, "Screenshot ($reason) failed: ${e.javaClass.simpleName}: ${e.message}")
            onDone?.invoke(false)
        }
    }

    private fun save(ctx: Context, bmp: Bitmap, reason: String): Boolean = try {
        val d = dir(ctx).apply { mkdirs() }
        val safe = reason.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        val file = File(d, "shot-${LocalDateTime.now().format(stamp)}-$safe.jpg")
        var quality = 80
        var bytes: ByteArray
        var img = bmp
        while (true) {
            val out = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES) break
            if (quality > 50) quality -= 15
            else img = Bitmap.createScaledBitmap(img, img.width * 3 / 4, img.height * 3 / 4, true)
        }
        // Write to a side file first: the box can lose power mid-write, which left an empty picture.
        val tmp = File(d, file.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        AppLog.info(TAG, "Screenshot ${file.name} (${bytes.size / 1024} KB, $reason)")
        (d.listFiles()?.sortedByDescending { it.name } ?: emptyList()).drop(KEEP).forEach { it.delete() }
        true
    } catch (e: Exception) {
        AppLog.warn(TAG, "Couldn't save screenshot: ${e.message}")
        false
    }

    /** Screenshots not sent yet, newest first. */
    fun unsent(context: Context): List<File> =
        (dir(context).listFiles()?.filter { it.name.endsWith(".jpg") && !it.name.endsWith(".sent.jpg") && it.length() > 0 } ?: emptyList())
            .sortedByDescending { it.name }

    fun markSent(f: File) {
        f.renameTo(File(f.parentFile, f.name.removeSuffix(".jpg") + ".sent.jpg"))
    }
}
