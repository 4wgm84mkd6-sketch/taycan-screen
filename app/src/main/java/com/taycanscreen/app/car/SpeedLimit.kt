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
 * Speed limit of the road the car is on, from OpenStreetMap "maxspeed" tags. The nearest road within
 * 30 m that runs the way the car is heading wins. Coverage depends on OSM mapping.
 *
 * Offline first (build 155): CI bundles every Hawaii road with a limit as assets/speedlimits.txt
 * (tools/speedlimits.py), looked up locally on each fix. Only if that file is missing does it ask the
 * public Overpass API online (at most every 6 s, after the car moved ~100 m); those servers timed out
 * from the car on every try in builds 149–154.
 */
object SpeedLimit {
    private const val TAG = "limit"
    private const val UA = "TaycanScreen/1.0 (personal in-car display)"
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    /** The endpoint that answered last time goes first. */
    @Volatile private var preferred = 0

    data class Limit(val mph: Int, val road: String?, val at: Long)

    private val _limit = MutableStateFlow<Limit?>(null)
    val limit: StateFlow<Limit?> = _limit.asStateFlow()

    /** Screenshot tests only. */
    internal fun preview(mph: Int?) {
        _limit.value = mph?.let { Limit(it, null, 0L) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false
    private var lastAskAt = 0L
    private var lastAskPos: LatLon? = null
    private var misses = 0
    /** After a failed lookup, wait before asking again (the public servers are sometimes slow). */
    private var backoffUntil = 0L
    private var failures = 0

    /** A road with a limit: mph, name and its points. */
    class Road(val mph: Int, val name: String?, val geom: List<LatLon>)

    /** Offline roads in a grid of [CELL]-degree cells (about 220 m), so a lookup only checks nearby roads. */
    class Index(val roads: List<Road>, private val cells: Map<Long, IntArray>, val header: String) {
        fun near(p: LatLon): List<Road> {
            val (cy, cx) = cellOf(p.lat, p.lon)
            val ids = HashSet<Int>()
            for (dy in -1..1) for (dx in -1..1) cells[key(cy + dy, cx + dx)]?.let { a -> a.forEach { ids += it } }
            return ids.map { roads[it] }
        }
    }

    private const val CELL = 0.002
    private fun cellOf(lat: Double, lon: Double) = Math.floor(lat / CELL).toInt() to Math.floor(lon / CELL).toInt()
    private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    /** Parses assets/speedlimits.txt (format in tools/speedlimits.py). */
    fun parseIndex(lines: Sequence<String>): Index {
        val roads = ArrayList<Road>()
        val cells = HashMap<Long, MutableList<Int>>()
        var header = ""
        for (line in lines) {
            if (line.startsWith("#")) { header = line.removePrefix("#").trim(); continue }
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val mph = parts[0].toIntOrNull() ?: continue
            val nums = parts[2].split(',').mapNotNull { it.toIntOrNull() }
            if (nums.size < 4 || nums.size % 2 != 0) continue
            val geom = ArrayList<LatLon>(nums.size / 2)
            var lat = 0; var lon = 0
            for (i in nums.indices step 2) {
                lat = if (i == 0) nums[0] else lat + nums[i]
                lon = if (i == 0) nums[1] else lon + nums[i + 1]
                geom += LatLon(lat / 1e5, lon / 1e5)
            }
            val id = roads.size
            roads += Road(mph, parts[1].ifBlank { null }, geom)
            val seen = HashSet<Long>()
            for (i in 0 until geom.size - 1) {
                val (y1, x1) = cellOf(geom[i].lat, geom[i].lon)
                val (y2, x2) = cellOf(geom[i + 1].lat, geom[i + 1].lon)
                for (y in minOf(y1, y2)..maxOf(y1, y2)) for (x in minOf(x1, x2)..maxOf(x1, x2)) {
                    val k = key(y, x)
                    if (seen.add(k)) cells.getOrPut(k) { ArrayList(2) } += id
                }
            }
        }
        return Index(roads, cells.mapValues { it.value.toIntArray() }, header)
    }

    @Volatile private var offline: Index? = null
    @Volatile private var offlineChecked = false

    /** Loads the bundled speed limits in the background (app start). */
    fun init(context: android.content.Context) {
        val app = context.applicationContext
        scope.launch {
            // After the start-up sequence: on a cold boot this parse (0.6–0.7 s) competed with the
            // first frames, which came 2.2–2.9 s after the app started instead of 0.8 s.
            kotlinx.coroutines.delay(4_000)
            val t0 = System.currentTimeMillis()
            try {
                val idx = app.assets.open("speedlimits.txt").bufferedReader().useLines { parseIndex(it) }
                offline = idx
                AppLog.info(TAG, "Offline speed limits: ${idx.roads.size} roads loaded in ${System.currentTimeMillis() - t0} ms (${idx.header})")
            } catch (e: java.io.FileNotFoundException) {
                AppLog.warn(TAG, "No offline speed limits in this build; asking Overpass online")
            } catch (e: Exception) {
                AppLog.warn(TAG, "Offline speed limits unreadable (${e.javaClass.simpleName}: ${e.message}); asking Overpass online")
            } finally {
                offlineChecked = true
            }
        }
    }

    private var offlineLastAt = 0L
    @Volatile private var offlineBusy = false

    /** From the map on each fix (main thread). */
    fun onLocation(lat: Double, lon: Double, heading: Float?, speedKmh: Double?) {
        val now = System.currentTimeMillis()
        val p = LatLon(lat, lon)
        // Stale after 2 minutes without a fresh answer (e.g. lost signal).
        _limit.value?.let { if (now - it.at > 120_000) _limit.value = null }
        if (!offlineChecked) return
        offline?.let { idx ->
            if (offlineBusy || now - offlineLastAt < 800) return
            offlineLastAt = now
            offlineBusy = true
            // Off the screen's thread: matching the nearby roads takes a few milliseconds every time.
            scope.launch(Dispatchers.Default) {
                try {
                    publish(choose(idx.near(p), p, heading), now)
                } finally {
                    offlineBusy = false
                }
            }
            return
        }
        if (busy || now - lastAskAt < 6_000 || now < backoffUntil) return
        if (!com.taycanscreen.app.net.NetMonitor.online.value) return
        val moved = lastAskPos?.let { RouteMath.metres(it, p) } ?: Double.MAX_VALUE
        if (moved < 100 && now - lastAskAt < 60_000) return
        if ((speedKmh ?: 0.0) < 3.0 && moved < 100 && _limit.value != null) return
        busy = true
        lastAskAt = now
        lastAskPos = p
        scope.launch {
            try {
                val json = query(p)
                publish(pick(json, p, heading), System.currentTimeMillis())
                failures = 0
            } catch (e: Exception) {
                failures++
                backoffUntil = System.currentTimeMillis() + minOf(300_000L, 20_000L * failures)
                AppLog.warn(TAG, "Speed limit lookup failed (${failures}×, next try in ${minOf(300, 20 * failures)} s): ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /** A found limit shows at once; two misses in a row clear it (no stale values). */
    private fun publish(found: Pair<Int, String?>?, now: Long) {
        if (found != null) {
            misses = 0
            val prev = _limit.value
            _limit.value = Limit(found.first, found.second, now)
            if (prev?.mph != found.first || prev.road != found.second) {
                AppLog.info(TAG, "${found.first} mph on ${found.second ?: "unnamed road"}")
            }
        } else if (++misses >= 2) {
            if (_limit.value != null) AppLog.info(TAG, "No speed limit mapped here")
            _limit.value = null
        }
    }

    private fun query(p: LatLon): String {
        val q = "[out:json][timeout:15];way(around:30,%.6f,%.6f)[highway][maxspeed];out tags geom;".format(Locale.US, p.lat, p.lon)
        var last: Exception? = null
        val order = ENDPOINTS.indices.sortedBy { if (it == preferred) -1 else it }
        for (i in order) {
            val url = ENDPOINTS[i]
            val t0 = System.currentTimeMillis()
            try {
                // GET (cacheable, simplest for the servers).
                val conn = (URL(url + "?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", UA)
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw java.io.IOException("HTTP $code")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (preferred != i) AppLog.info(TAG, "Speed limits from ${URL(url).host} (${System.currentTimeMillis() - t0} ms)")
                    preferred = i
                    return body
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "${URL(url).host}: ${e.javaClass.simpleName} (${e.message}) after ${System.currentTimeMillis() - t0} ms")
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
        val roads = elements.mapNotNull { e ->
            val o = e.asJsonObject
            if (o.get("type")?.asString != "way") return@mapNotNull null
            val tags = o.getAsJsonObject("tags") ?: return@mapNotNull null
            val mph = parseMaxspeed(tags.get("maxspeed")?.asString) ?: return@mapNotNull null
            val geom = o.getAsJsonArray("geometry")?.map {
                val g = it.asJsonObject
                LatLon(g.get("lat").asDouble, g.get("lon").asDouble)
            } ?: return@mapNotNull null
            Road(mph, tags.get("name")?.asString, geom)
        }
        return choose(roads, p, heading)
    }

    /** Nearest of [roads] within 30 m, preferring one that runs along the car's heading. */
    fun choose(roads: List<Road>, p: LatLon, heading: Float?): Pair<Int, String?>? {
        var best: Pair<Int, String?>? = null
        var bestScore = Double.MAX_VALUE
        for (r in roads) {
            val geom = r.geom
            val mph = r.mph
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
                best = mph to r.name
            }
        }
        return best
    }
}
