package com.taycanscreen.app.ui

/**
 * Speed from the box's GPS, used only when the car's own speed isn't available (adapter unplugged or
 * not answering; owner, 2026-11-23). The Drive screen marks it "GPS". A fix older than [MAX_AGE_MS]
 * gives nothing ("—"), never an old number. Below [STILL_MPH] it reads 0: standing still, GPS speed
 * wanders by a mile an hour or so.
 */
object GpsSpeed {
    const val MAX_AGE_MS = 3_000L
    const val STILL_MPH = 1.5
    private const val MPS_TO_MPH = 2.236936

    /** Screen pictures set this instead of a real fix. */
    @Volatile var previewMph: Double? = null

    fun mph(hasSpeed: Boolean, speedMps: Float, ageMs: Long): Double? {
        if (!hasSpeed || ageMs > MAX_AGE_MS || ageMs < -1_000L) return null
        val mph = speedMps * MPS_TO_MPH
        if (mph.isNaN() || mph < 0) return null
        return if (mph < STILL_MPH) 0.0 else mph
    }

    fun mph(loc: android.location.Location?): Double? {
        previewMph?.let { return it }
        loc ?: return null
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        return mph(loc.hasSpeed(), loc.speed, ageMs)
    }
}
