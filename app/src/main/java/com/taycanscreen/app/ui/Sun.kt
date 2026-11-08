package com.taycanscreen.app.ui

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Day/night from the sun's real position, so "Auto" works even when the box's clock is set to the
 * wrong time zone (only the absolute time, which the box gets from the network, matters).
 */
object Sun {
    /** Last known position; updated from the map's GPS fix. Defaults to Honolulu. */
    @Volatile var latitude: Double = 21.3069
    @Volatile var longitude: Double = -157.8583

    /** Sun altitude in degrees above the horizon (low-precision NOAA/Almanac formula, ±0.5°). */
    fun altitudeDeg(lat: Double, lon: Double, epochMs: Long): Double {
        val d = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0 // days since J2000.0
        val g = Math.toRadians(norm(357.529 + 0.98560028 * d))
        val q = norm(280.459 + 0.98564736 * d)
        val l = Math.toRadians(norm(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstDeg = norm((18.697374558 + 24.06570982441908 * d) * 15.0)
        val ha = Math.toRadians(norm(gmstDeg + lon)) - ra
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
    }

    /** Night once the sun is 3° below the horizon (a little after sunset, before full dark). */
    fun isNight(epochMs: Long = System.currentTimeMillis()): Boolean =
        altitudeDeg(latitude, longitude, epochMs) < -3.0

    private fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
