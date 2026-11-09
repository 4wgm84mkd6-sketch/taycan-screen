package com.taycanscreen.app.ui

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Start-up timing for this app process, kept outside any screen so the start-up sequence and the
 * first-shift gauge sweep run once per start (not again when Android rebuilds the screen).
 *
 * Every milestone is logged as "[boot] … Android up X.X s, +Y ms after first frame", so a log from a
 * cold start shows box power-on → app → first frame → car data / GPS / Spotify.
 */
object Boot {
    /** elapsedRealtime of our first drawn frame; 0 until then. */
    @Volatile var firstFrameAt = 0L
        private set

    /** The start-up sequence has been shown (or skipped) in this process. */
    var startupShown = false

    /** When the start-up hands off to Drive (elapsedRealtime); 0 = not yet. Drives the map wipe and fly-ins. */
    val handoffAt = MutableStateFlow(0L)

    /** First shift into D after start-up has swept the gauge (or the car was already in D). */
    var sweepDone = false

    private val marked = HashSet<String>()

    fun markFirstFrame() {
        if (firstFrameAt != 0L) return
        val now = SystemClock.elapsedRealtime()
        firstFrameAt = now
        val processStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime() else 0L
        AppLog.info(
            "boot",
            "First frame on screen: Android up %.1f s".format(now / 1000.0) +
                (if (processStart > 0) ", %d ms after the app process started".format(now - processStart) else ""),
        )
    }

    /** Seconds since the first frame (0 before it). */
    fun sinceFirstFrame(): Float =
        if (firstFrameAt == 0L) 0f else (SystemClock.elapsedRealtime() - firstFrameAt) / 1000f

    /** Logs a start-up milestone once per process. */
    fun mark(event: String) {
        synchronized(marked) { if (!marked.add(event)) return }
        val now = SystemClock.elapsedRealtime()
        AppLog.info(
            "boot",
            "$event: Android up %.1f s".format(now / 1000.0) +
                (if (firstFrameAt > 0) ", +%d ms after first frame".format(now - firstFrameAt) else ", before first frame"),
        )
    }
}
