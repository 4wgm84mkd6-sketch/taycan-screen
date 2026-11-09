package com.taycanscreen.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.taycanscreen.app.log.AppLog
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The car's position for the map, from Android's location service (car GPS through CarPlay, plus
 * fused/network as a fallback).
 *
 * Smooth tracking: [tick] runs every frame and predicts where the car is now from the last fix, its
 * age, the direction of travel and the car's own speed (OBD), easing the shown position towards it.
 * Fused/network fixes are ignored while car GPS fixes are current (they interleave and pull the
 * marker back). Direction of travel falls back to the bearing between fixes when GPS has none.
 */
class CarTracker(
    private val context: Context,
    private val carSpeedKmh: () -> Double?,
    /** Main thread: each accepted fix with the current direction of travel (null until moving). */
    private val onFix: (status: String, location: Location, bearing: Float?) -> Unit,
) : LocationListener {
    data class Shown(val lat: Double, val lon: Double, val bearing: Float?, val moving: Boolean)

    private val lm = context.getSystemService(LocationManager::class.java)
    private var lastLogAt = 0L
    private var lastPoint: Location? = null
    private var lastBearing: Float? = null
    private var lastBearingSource = ""
    private var lastGpsAt = 0L
    private var fix: Location? = null
    private var fixAtMs = 0L
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var shownBearing: Float? = null
    private var lastTickMs = 0L
    private var firstFix = true

    val lastFix: Location? get() = fix

    @SuppressLint("MissingPermission")
    fun start() {
        val m = lm ?: return
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        AppLog.info("map", "Location providers on the box: ${m.allProviders} enabled: ${m.getProviders(true)} (location ${if (m.isLocationEnabled) "on" else "OFF"}); using $providers")
        for (p in providers) {
            try {
                m.requestLocationUpdates(p, 1_000L, 0f, this, Looper.getMainLooper())
            } catch (e: Exception) {
                AppLog.warn("map", "No updates from $p: ${e.message}")
            }
        }
        // Start from the last known position so the map isn't empty while GPS warms up.
        providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }?.let { onLocationChanged(it) }
    }

    fun stop() {
        runCatching { lm?.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        val nowEl = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.GPS_PROVIDER) lastGpsAt = nowEl
        else if (nowEl - lastGpsAt < 3_000) return
        val obd = carSpeedKmh()
        val moving = (location.hasSpeed() && location.speed > 1.5f) || (obd != null && obd > 5.0)
        val prev = lastPoint
        val gpsBearing = if (location.hasBearing() && (location.bearing != 0f || location.hasSpeed())) location.bearing else null
        val bearing: Float? = when {
            !moving -> null
            gpsBearing != null -> gpsBearing.also { lastBearingSource = "GPS" }
            prev != null && prev.distanceTo(location) >= 6f -> ((prev.bearingTo(location) + 360f) % 360f).also { lastBearingSource = "between fixes" }
            else -> null
        }
        if (prev == null || prev.distanceTo(location) >= 6f || !moving) lastPoint = Location(location)
        if (bearing != null) lastBearing = bearing
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
            .takeIf { location.elapsedRealtimeNanos > 0 && it in 0..3_000 } ?: 0L
        val copy = Location(location)
        fix = copy
        fixAtMs = nowEl - ageMs
        if (firstFix) {
            firstFix = false
            AppLog.info("map", "GPS fix")
        }
        val status = "GPS ±%d m · %s".format(location.accuracy.roundToInt(), location.provider ?: "?")
        Handler(Looper.getMainLooper()).post { onFix(status, copy, lastBearing) }
        val now = System.currentTimeMillis()
        if (now - lastLogAt > (if (moving) 10_000 else 30_000)) {
            lastLogAt = now
            AppLog.info(
                "map",
                "Fix from ${location.provider}: ±${location.accuracy.roundToInt()} m, age $ageMs ms, " +
                    "GPS ${if (location.hasSpeed()) "%.1f".format(location.speed * 2.23694) else "?"} mph vs car " +
                    "${obd?.let { "%.1f".format(it * 0.621371) } ?: "?"} mph, " +
                    "bearing ${lastBearing?.roundToInt() ?: -1} from ${lastBearingSource.ifBlank { "none yet" }}",
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    /** Called every frame: predicted, eased position and heading (null before the first fix). */
    fun tick(nowMs: Long): Shown? {
        val f = fix ?: return null
        val dt = if (lastTickMs == 0L) 0.033 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 0.25)
        lastTickMs = nowMs
        val b = lastBearing
        val speedMps = carSpeedKmh()?.div(3.6) ?: if (f.hasSpeed()) f.speed.toDouble() else 0.0
        val moving = b != null && speedMps > 1.0
        var pLat = f.latitude
        var pLon = f.longitude
        if (moving) {
            // Ahead by the fix's age plus the easing delay, so at steady speed the marker sits on the car.
            val t = ((nowMs - fixAtMs) / 1000.0 + EASE_S).coerceIn(0.0, 2.0 + EASE_S)
            val d = speedMps * t
            val rad = Math.toRadians(b!!.toDouble())
            pLat += d * cos(rad) / 111_320.0
            pLon += d * sin(rad) / (111_320.0 * cos(Math.toRadians(pLat)))
        }
        if (shownLat.isNaN() || metres(shownLat, shownLon, pLat, pLon) > 80.0) {
            shownLat = pLat
            shownLon = pLon
        } else {
            val k = 1.0 - exp(-dt / EASE_S)
            shownLat += (pLat - shownLat) * k
            shownLon += (pLon - shownLon) * k
        }
        if (b != null) {
            val cur = shownBearing ?: b
            val diff = ((b - cur + 540f) % 360f) - 180f
            shownBearing = (cur + diff * (1f - exp(-dt / 0.35).toFloat()) + 360f) % 360f
        }
        return Shown(shownLat, shownLon, shownBearing, moving)
    }

    private companion object {
        const val EASE_S = 0.25
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * 111_320.0
            val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
            return sqrt(dx * dx + dy * dy)
        }
    }
}
