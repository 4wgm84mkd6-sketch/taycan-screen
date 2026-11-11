package com.taycanscreen.app.log

import android.view.Choreographer

/**
 * Smoothness, measured: once a minute while the app is in front, the log gets a [perf] line with the
 * frame rate and how many frames were skipped. A drive log then shows whether the screen was smooth,
 * instead of anyone having to judge it by eye.
 *
 * The box draws 30 frames a second on the car's screen, not 60 (measured in the car, 2026-11-10: a
 * calm minute had 1796 frames, the longest 33 ms). So "late" is counted against the frame spacing
 * actually seen: the first minute assumes 33 ms, each later minute uses the minute before.
 */
object FrameStats : Choreographer.FrameCallback {
    private const val WINDOW_NS = 60_000_000_000L
    private var running = false
    private var last = 0L
    private var windowStart = 0L
    private var frames = 0
    private var late = 0
    private var veryLate = 0
    private var worstMs = 0.0
    /** Normal spacing between frames, ms. */
    private var unitMs = 33.3
    /** What the display says its refresh rate is (for the log; the real spacing is measured). */
    @Volatile var displayHz = 0f

    /** Main thread. */
    fun start() {
        if (running) return
        running = true
        last = 0L
        windowStart = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
    }

    override fun doFrame(t: Long) {
        if (!running) return
        if (windowStart == 0L) windowStart = t
        if (last != 0L) {
            val ms = (t - last) / 1e6
            frames++
            if (ms > unitMs * 1.6) late++      // at least one frame skipped
            if (ms > unitMs * 3.5) veryLate++  // three or more skipped: a visible hitch
            if (ms > worstMs) worstMs = ms
        }
        last = t
        if (t - windowStart >= WINDOW_NS && frames > 0) {
            val s = (t - windowStart) / 1e9
            AppLog.info(
                "perf",
                "%.0f s: %d frames (%.1f per second, display says %.0f Hz), %d late (a frame skipped), %d very late (3 or more skipped), worst %.0f ms".format(
                    s, frames, frames / s, displayHz, late, veryLate, worstMs,
                ),
            )
            unitMs = (s * 1000.0 / frames).coerceIn(8.0, 50.0)
            windowStart = t
            frames = 0; late = 0; veryLate = 0; worstMs = 0.0
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
