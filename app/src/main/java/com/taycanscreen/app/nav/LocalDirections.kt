package com.taycanscreen.app.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Directions the way people give them here (v3 design, "Local"): in Honolulu mauka (toward the
 * mountains, about NNE), makai (toward the sea), Diamond Head (east along the shore) and ʻEwa (west).
 * Elsewhere on Oʻahu mauka/makai point to/from the island's middle and along-shore falls back to compass;
 * off Oʻahu, or in "Compass" mode, plain compass words.
 */
object LocalDirections {
    enum class Mode { Local, Compass }

    /** Chosen on the map (tap the direction label); stored by the map. */
    val mode = kotlinx.coroutines.flow.MutableStateFlow(Mode.Local)

    /** Honolulu's street grid axis: mauka is about 25° (NNE). */
    private const val HONOLULU_MAUKA = 25.0
    private val OAHU_MIDDLE = LatLon(21.48, -157.98)

    private fun inHonolulu(p: LatLon) = p.lat in 21.24..21.42 && p.lon in -158.12..-157.64
    private fun onOahu(p: LatLon) = p.lat in 21.23..21.72 && p.lon in -158.30..-157.63

    private fun norm(d: Double): Double = ((d % 360.0) + 540.0) % 360.0 - 180.0 // −180..180

    private fun bearing(a: LatLon, b: LatLon): Double =
        (Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat)) + 360.0) % 360.0

    fun compass(deg: Double): String {
        val names = listOf("NORTH", "NORTHEAST", "EAST", "SOUTHEAST", "SOUTH", "SOUTHWEST", "WEST", "NORTHWEST")
        return names[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    }

    /** Name for travelling toward [deg] (degrees from north) at [at]. */
    fun name(deg: Double, at: LatLon?, mode: Mode): String {
        if (mode == Mode.Compass || at == null || !onOahu(at)) return compass(deg)
        if (inHonolulu(at)) {
            val rel = norm(deg - HONOLULU_MAUKA)
            return when {
                abs(rel) <= 45 -> "MAUKA"
                abs(rel) >= 135 -> "MAKAI"
                rel > 0 -> "DIAMOND HEAD"
                else -> "ʻEWA"
            }
        }
        val rel = norm(deg - bearing(at, OAHU_MIDDLE))
        return when {
            abs(rel) <= 45 -> "MAUKA"
            abs(rel) >= 135 -> "MAKAI"
            else -> compass(deg)
        }
    }
}
