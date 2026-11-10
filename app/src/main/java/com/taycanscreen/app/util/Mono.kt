package com.taycanscreen.app.util

/**
 * A clock that only moves forward at one second per second. The box starts with a wrong date
 * (30 July 2025) and jumps to the real one once it syncs, mid-drive; anything that measures how old
 * a reading is, or how long to wait, must use this and never the date clock.
 */
object Mono {
    fun ms(): Long = System.nanoTime() / 1_000_000L
}
