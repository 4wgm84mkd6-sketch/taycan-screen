package com.taycanscreen.app.nav

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry on the route polyline (flat-earth approximation; fine for car distances). */
object RouteMath {
    private const val M_PER_DEG = 111_320.0

    fun metres(a: LatLon, b: LatLon): Double {
        val dy = (b.lat - a.lat) * M_PER_DEG
        val dx = (b.lon - a.lon) * M_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance from the start to each point. */
    fun cumulative(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + metres(points[i - 1], points[i])
        return out
    }

    data class Projection(val along: Double, val offM: Double, val seg: Int, val point: LatLon)

    /** Nearest point on the polyline to [p], searching segments from [fromSeg] on. */
    fun project(points: List<LatLon>, cum: DoubleArray, p: LatLon, fromSeg: Int = 0): Projection {
        if (points.size < 2) return Projection(0.0, if (points.isEmpty()) 0.0 else metres(points[0], p), 0, points.firstOrNull() ?: p)
        val kx = M_PER_DEG * cos(Math.toRadians(p.lat))
        var best = Projection(0.0, Double.MAX_VALUE, 0, points[0])
        for (i in fromSeg.coerceIn(0, points.size - 2) until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lon - p.lon) * kx
            val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best.offM) {
                best = Projection(
                    along = cum[i] + t * (cum[i + 1] - cum[i]),
                    offM = d, seg = i,
                    point = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)),
                )
            }
        }
        return best
    }

    /** US units, glanceable: "500 ft", "0.3 mi", "12 mi". */
    fun formatDistance(m: Double): String {
        val miles = m / 1609.344
        return when {
            miles < 0.1 -> "${((m * 3.28084) / 50).roundToInt().coerceAtLeast(1) * 50} ft"
            miles < 10 -> "%.1f mi".format(miles)
            else -> "${miles.roundToInt()} mi"
        }
    }

    /** Plain-English instruction for an OSRM step. */
    fun instruction(s: Step): String {
        val road = s.name.ifBlank { s.ref ?: "" }
        val onto = if (road.isNotBlank()) " onto $road" else ""
        val dir = s.modifier?.let { prettyModifier(it) }
        return when (s.type) {
            "depart" -> if (road.isNotBlank()) "Head out on $road" else "Head out"
            "arrive" -> "Arrive at your destination"
            "turn" -> if (s.modifier == "uturn") "Make a U-turn$onto" else "Turn ${dir ?: ""}$onto".replace("  ", " ")
            "new name" -> "Continue$onto"
            "continue" -> if (dir != null && dir != "straight") "Keep $dir$onto" else "Continue$onto"
            "merge" -> "Merge${dir?.let { " $it" } ?: ""}$onto"
            "on ramp" -> "Take the ramp${dir?.let { " on the $it" } ?: ""}$onto"
            "off ramp" -> "Take the exit${dir?.let { " on the $it" } ?: ""}$onto"
            "fork" -> "Keep ${dir ?: "ahead"} at the fork$onto"
            "end of road" -> "At the end of the road, turn ${dir ?: ""}$onto".replace("  ", " ")
            "roundabout", "rotary" -> "At the roundabout, take the ${ordinal(s.exit ?: 1)} exit$onto"
            "exit roundabout", "exit rotary" -> "Leave the roundabout$onto"
            else -> if (dir != null) "Go $dir$onto" else "Continue$onto"
        }.trim()
    }

    private fun prettyModifier(m: String) = when (m) {
        "slight right" -> "slightly right"
        "slight left" -> "slightly left"
        "sharp right" -> "sharp right"
        "sharp left" -> "sharp left"
        else -> m
    }

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}

/**
 * Destination search and the active route: shown on our own map, with the next turn, distance left
 * and arrival time, re-routed automatically when the car leaves the route.
 */
object Nav {
    private const val TAG = "nav"
    private const val OFF_ROUTE_M = 60.0
    private const val ARRIVED_M = 35.0

    data class Active(
        val dest: Place,
        val route: Route,
        val cum: DoubleArray,
        /** Distance along the route of each step's manoeuvre. */
        val stepAlong: DoubleArray,
        val id: Long,
    )

    data class Progress(
        val remainingM: Double,
        val arriveAtMs: Long,
        val next: Step?,
        val toNextM: Double,
        val seg: Int,
        val onRoute: LatLon,
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    /** "Finding a route…" / "Re-routing…" while a request runs. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _recents = MutableStateFlow<List<Place>>(emptyList())
    val recents: StateFlow<List<Place>> = _recents.asStateFlow()

    /** Saved places by kind ("home", "work"), for one-tap routes. */
    private val _saved = MutableStateFlow<Map<String, Place>>(emptyMap())
    val saved: StateFlow<Map<String, Place>> = _saved.asStateFlow()

    private var app: Context? = null
    private var routeJob: Job? = null
    private var lastSeg = 0
    private var offCount = 0
    private var lastRerouteAt = 0L
    private var lastLatLon: LatLon? = null
    private var arrivedJob: Job? = null
    /** A destination picked before the car's position was known; routed as soon as a fix arrives. */
    private var pending: Place? = null
    private var rerouteFailures = 0

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val json = context.getSharedPreferences("nav", Context.MODE_PRIVATE).getString("recents", null)
        _recents.value = json?.let {
            runCatching { Gson().fromJson<List<Place>>(it, object : TypeToken<List<Place>>() {}.type) }.getOrNull()
        } ?: emptyList()
        val savedJson = context.getSharedPreferences("nav", Context.MODE_PRIVATE).getString("saved", null)
        _saved.value = savedJson?.let {
            runCatching { Gson().fromJson<Map<String, Place>>(it, object : TypeToken<Map<String, Place>>() {}.type) }.getOrNull()
        } ?: emptyMap()
    }

    /** Saves [place] as Home or Work. */
    fun save(kind: String, place: Place) {
        val map = _saved.value + (kind to place)
        _saved.value = map
        app?.getSharedPreferences("nav", Context.MODE_PRIVATE)?.edit()?.putString("saved", Gson().toJson(map))?.apply()
        AppLog.info(TAG, "${kind.replaceFirstChar { it.uppercase() }} set to ${place.name} (${place.detail})")
    }

    /** Route to Home or Work; false when it isn't set yet. */
    fun goSaved(kind: String, from: LatLon?): Boolean {
        val place = _saved.value[kind] ?: return false
        AppLog.info(TAG, "Quick route: ${kind.replaceFirstChar { it.uppercase() }}")
        start(place, from ?: lastLatLon)
        return true
    }

    fun clearError() { _error.value = null }

    /** Screenshot tests only: a fixed route with the car at [at] (null clears it). */
    internal fun preview(place: Place?, route: Route?, at: LatLon?) {
        if (place == null || route == null || at == null) {
            _active.value = null
            _progress.value = null
            return
        }
        val cum = RouteMath.cumulative(route.points)
        var seg = 0
        val along = DoubleArray(route.steps.size) { i ->
            val pr = RouteMath.project(route.points, cum, route.steps[i].at, seg)
            seg = pr.seg
            pr.along
        }
        lastSeg = 0
        _active.value = Active(place, route, cum, along, 1L)
        update(at)
    }

    /** Route from [from] (the car) to [place]; replaces any active route. */
    fun start(place: Place, from: LatLon?, reroute: Boolean = false) {
        if (from == null) {
            // Don't lose the choice: the route starts by itself when GPS has the car's position.
            pending = place
            _error.value = null
            _busy.value = "Waiting for GPS to start the route…"
            AppLog.info(TAG, "No position yet: ${place.name} will be routed when GPS has a fix")
            return
        }
        pending = null
        routeJob?.cancel()
        if (!reroute) {
            arrivedJob?.cancel()
            arrivedJob = null
            rerouteFailures = 0
        }
        _error.value = null
        _busy.value = if (reroute) "Re-routing…" else "Finding a route…"
        routeJob = scope.launch {
            val me = coroutineContext[Job]
            try {
                val r = NavApi.route(from, LatLon(place.lat, place.lon))
                val cum = RouteMath.cumulative(r.points)
                var seg = 0
                val along = DoubleArray(r.steps.size) { i ->
                    val pr = RouteMath.project(r.points, cum, r.steps[i].at, seg)
                    seg = pr.seg
                    pr.along
                }
                lastSeg = 0
                offCount = 0
                _active.value = Active(place, r, cum, along, if (reroute) _active.value?.id ?: System.currentTimeMillis() else System.currentTimeMillis())
                AppLog.info(
                    TAG,
                    (if (reroute) "Re-routed" else "Route") + " to ${place.name}: %.1f mi, %d min, %d steps, %d points".format(
                        r.distanceM / 1609.344, (r.durationS / 60).roundToInt(), r.steps.size, r.points.size,
                    ),
                )
                if (!reroute) remember(place)
                rerouteFailures = 0
                update(from)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(TAG, "Route failed: ${e.javaClass.simpleName}: ${e.message}")
                // A failed re-route keeps the old route on screen and tries again later, quietly.
                if (reroute) rerouteFailures++ else _error.value = friendly(e)
            } finally {
                // Only the newest request may clear the "Finding a route…" note.
                if (routeJob == me) _busy.value = null
            }
        }
    }

    fun stop() {
        routeJob?.cancel()
        arrivedJob?.cancel()
        arrivedJob = null // or the next trip would never clear itself after arriving
        pending = null
        _active.value?.let { AppLog.info(TAG, "Route to ${it.dest.name} ended") }
        _active.value = null
        _progress.value = null
        _busy.value = null
    }

    /** Car position from the map (main thread, about once a second). */
    fun onLocation(lat: Double, lon: Double) {
        val p = LatLon(lat, lon)
        lastLatLon = p
        pending?.let { start(it, p) }
        if (_active.value != null) update(p)
    }

    fun lastPosition(): LatLon? = lastLatLon

    private fun update(p: LatLon) {
        val a = _active.value ?: return
        val pts = a.route.points
        val pr = RouteMath.project(pts, a.cum, p, (lastSeg - 10).coerceAtLeast(0))
        lastSeg = pr.seg
        val total = a.cum.lastOrNull() ?: 0.0
        val remaining = (total - pr.along).coerceAtLeast(0.0)
        var nextIdx = -1
        for (i in a.route.steps.indices) {
            if (a.route.steps[i].type == "depart") continue
            if (a.stepAlong[i] > pr.along + 8) { nextIdx = i; break }
        }
        val next = a.route.steps.getOrNull(nextIdx)
        val toNext = if (nextIdx >= 0) a.stepAlong[nextIdx] - pr.along else remaining
        val leftS = if (total > 0) a.route.durationS * remaining / total else 0.0
        val arrived = remaining < ARRIVED_M
        val off = pr.offM > OFF_ROUTE_M && !arrived
        _progress.value = Progress(
            remainingM = remaining,
            arriveAtMs = System.currentTimeMillis() + (leftS * 1000).toLong(),
            next = next, toNextM = toNext, seg = pr.seg, onRoute = pr.point,
            offRoute = off, arrived = arrived,
        )
        if (arrived && arrivedJob == null) {
            AppLog.info(TAG, "Arrived at ${a.dest.name}")
            arrivedJob = scope.launch {
                delay(60_000)
                arrivedJob = null
                if (_active.value?.id == a.id) stop()
            }
        }
        // Off the route for three fixes in a row: ask for a new route from here (at most every 20 s).
        offCount = if (off) offCount + 1 else 0
        val now = System.currentTimeMillis()
        // After failures (no signal), wait longer each time: 20 s, 40 s, … up to 5 min.
        val wait = minOf(300_000L, 20_000L * (1L shl rerouteFailures.coerceAtMost(4)))
        if (offCount >= 3 && (now - lastRerouteAt > wait || now < lastRerouteAt) && routeJob?.isActive != true) {
            lastRerouteAt = now
            AppLog.info(TAG, "Off route by ${pr.offM.roundToInt()} m: re-routing")
            start(a.dest, p, reroute = true)
        }
    }

    private fun remember(place: Place) {
        val list = (listOf(place) + _recents.value.filterNot {
            it.name == place.name && abs(it.lat - place.lat) < 1e-4 && abs(it.lon - place.lon) < 1e-4
        }).take(8)
        _recents.value = list
        app?.getSharedPreferences("nav", Context.MODE_PRIVATE)?.edit()?.putString("recents", Gson().toJson(list))?.apply()
    }

    fun friendly(e: Exception): String {
        val m = "${e.javaClass.simpleName} ${e.message}"
        return if ("No route" in m || "NoRoute" in m) "No driving route found to that place."
        else com.taycanscreen.app.util.Friendly.net(e, "Couldn't reach the map service. Try again.")
    }
}
