package com.taycanscreen.app.log

import android.view.Choreographer

/**
 * Smoothness, measured: once a minute while the app is in front, the log gets a [perf] line with the
 * frame rate and how many frames arrived late. A drive log then shows whether the screen was smooth,
 * instead of anyone having to judge it by eye.
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
            if (ms > 25.0) late++
            if (ms > 50.0) veryLate++
            if (ms > worstMs) worstMs = ms
        }
        last = t
        if (t - windowStart >= WINDOW_NS) {
            val s = (t - windowStart) / 1e9
            AppLog.info(
                "perf",
                "%.0f s: %d frames (%.1f per second), %d late (over 25 ms), %d very late (over 50 ms), worst %.0f ms".format(
                    s, frames, frames / s, late, veryLate, worstMs,
                ),
            )
            windowStart = t
            frames = 0; late = 0; veryLate = 0; worstMs = 0.0
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
