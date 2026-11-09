package com.taycanscreen.app.car

import com.google.gson.JsonParser
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.nav.LatLon
import com.taycanscreen.app.nav.RouteMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Speed limit of the road the car is on, from OpenStreetMap "maxspeed" tags via the free Overpass API
 * (overpass-api.de). Asked at most every 6 s and only after the car moved ~100 m; the nearest road
 * within 30 m that runs the way the car is heading wins. Coverage depends on OSM mapping.
 */
object SpeedLimit {
    private const val TAG = "limit"
    private const val UA = "TaycanScreen/1.0 (personal in-car display)"
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    data class Limit(val mph: Int, val road: String?, val at: Long)

    private val _limit = MutableStateFlow<Limit?>(null)
    val limit: StateFlow<Limit?> = _limit.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false
    private var lastAskAt = 0L
    private var lastAskPos: LatLon? = null
    private var misses = 0

    /** From the map on each fix (main thread). */
    fun onLocation(lat: Double, lon: Double, heading: Float?, speedKmh: Double?) {
        val now = System.currentTimeMillis()
        val p = LatLon(lat, lon)
        // Stale after 2 minutes without a fresh answer (e.g. lost signal).
        _limit.value?.let { if (now - it.at > 120_000) _limit.value = null }
        if (busy || now - lastAskAt < 6_000) return
        val moved = lastAskPos?.let { RouteMath.metres(it, p) } ?: Double.MAX_VALUE
        if (moved < 100 && now - lastAskAt < 60_000) return
        if ((speedKmh ?: 0.0) < 3.0 && moved < 100 && _limit.value != null) return
        busy = true
        lastAskAt = now
        lastAskPos = p
        scope.launch {
            try {
                val json = query(p)
                val found = pick(json, p, heading)
                if (found != null) {
                    misses = 0
                    val prev = _limit.value
                    _limit.value = Limit(found.first, found.second, System.currentTimeMillis())
                    if (prev?.mph != found.first || prev.road != found.second) {
                        AppLog.info(TAG, "${found.first} mph on ${found.second ?: "unnamed road"}")
                    }
                } else if (++misses >= 2) {
                    _limit.value = null
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "Speed limit lookup failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun query(p: LatLon): String {
        val q = "[out:json][timeout:10];way(around:30,%.6f,%.6f)[highway][maxspeed];out tags geom;".format(Locale.US, p.lat, p.lon)
        var last: Exception? = null
        for (url in ENDPOINTS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                try {
                    conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                    if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("no endpoint")
    }

    /** "35 mph" → 35; "50" (km/h) → 31; "none"/"signals" → null. */
    fun parseMaxspeed(tag: String?): Int? {
        val t = tag?.split(';')?.firstOrNull()?.trim()?.lowercase() ?: return null
        val m = Regex("""^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh)?$""").find(t) ?: return null
        val n = m.groupValues[1].toDouble()
        return if (m.groupValues[2] == "mph") n.roundToInt() else (n * 0.621371).roundToInt()
    }

    /** Nearest road with a limit, preferring one that runs along the car's heading. (mph, road name) */
    fun pick(json: String, p: LatLon, heading: Float?): Pair<Int, String?>? {
        val elements = JsonParser.parseString(json).asJsonObject.getAsJsonArray("elements") ?: return null
        var best: Pair<Int, String?>? = null
        var bestScore = Double.MAX_VALUE
        for (e in elements) {
            val o = e.asJsonObject
            if (o.get("type")?.asString != "way") continue
            val tags = o.getAsJsonObject("tags") ?: continue
            val mph = parseMaxspeed(tags.get("maxspeed")?.asString) ?: continue
            val geom = o.getAsJsonArray("geometry")?.map {
                val g = it.asJsonObject
                LatLon(g.get("lat").asDouble, g.get("lon").asDouble)
            } ?: continue
            if (geom.size < 2) continue
            val cum = RouteMath.cumulative(geom)
            val pr = RouteMath.project(geom, cum, p)
            if (pr.offM > 30) continue
            var score = pr.offM
            if (heading != null) {
                val a = geom[pr.seg]
                val b = geom[pr.seg + 1]
                val segBearing = Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat)).toFloat()
                val diff = abs(((heading - segBearing) % 180f + 270f) % 180f - 90f) // 0 = along the road either way
                if (diff > 40f) score += 25.0
            }
            if (score < bestScore) {
                bestScore = score
                best = mph to tags.get("name")?.asString
            }
        }
        return best
    }
}
