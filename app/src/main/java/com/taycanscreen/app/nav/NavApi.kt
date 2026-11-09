package com.taycanscreen.app.nav

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class LatLon(val lat: Double, val lon: Double)

/** A search result or saved destination. */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** One OSRM step: the manoeuvre at its start, then [distanceM] along [name] to the next one. */
data class Step(
    val at: LatLon,
    val type: String,
    val modifier: String?,
    val name: String,
    val ref: String?,
    val exit: Int?,
    val distanceM: Double,
    /** Direction of travel right after the manoeuvre (degrees from north). */
    val bearingAfter: Double? = null,
)

data class Route(val points: List<LatLon>, val distanceM: Double, val durationS: Double, val steps: List<Step>)

/**
 * Free, key-less services (no account, nothing stored in the APK):
 *  - Photon (photon.komoot.io): OpenStreetMap place search built for search-as-you-type.
 *  - OSRM demo server (router.project-osrm.org): driving routes with turn steps.
 * Both get the typed text / coordinates; nothing else about the car or the owner.
 */
object NavApi {
    private const val UA = "TaycanScreen/1.0 (personal in-car display)"

    suspend fun search(query: String, near: LatLon?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let { "&lat=%.5f&lon=%.5f".format(Locale.US, it.lat, it.lon) } ?: ""
        return parsePhoton(get("https://photon.komoot.io/api/?q=$q&limit=8&lang=en$bias"))
    }

    suspend fun route(from: LatLon, to: LatLon): Route {
        val coords = "%.6f,%.6f;%.6f,%.6f".format(Locale.US, from.lon, from.lat, to.lon, to.lat)
        return parseOsrm(get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true"))
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code ${body.take(120)}")
            body
        } finally {
            conn.disconnect()
        }
    }

    /** Photon GeoJSON → places, labelled like a car nav: name, then street / city / state. */
    fun parsePhoton(json: String): List<Place> {
        val root = JsonParser.parseString(json).asJsonObject
        val features = root.getAsJsonArray("features") ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f.asJsonObject
            val c = o.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: return@mapNotNull null
            if (c.size() < 2) return@mapNotNull null
            val p = o.getAsJsonObject("properties") ?: JsonObject()
            fun s(k: String) = p.get(k)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val streetLine = listOfNotNull(s("housenumber"), s("street")).joinToString(" ").ifBlank { null }
            val name = s("name") ?: streetLine ?: return@mapNotNull null
            val detail = listOfNotNull(
                streetLine?.takeIf { it != name },
                s("city") ?: s("district") ?: s("county"),
                s("state"),
            ).distinct().joinToString(", ")
            Place(name, detail, c[1].asDouble, c[0].asDouble)
        }.distinctBy { "${it.name}|${"%.4f".format(Locale.US, it.lat)}|${"%.4f".format(Locale.US, it.lon)}" }
    }

    fun parseOsrm(json: String): Route {
        val root = JsonParser.parseString(json).asJsonObject
        val code = root.get("code")?.asString
        if (code != "Ok") throw java.io.IOException(root.get("message")?.asString ?: "No route ($code)")
        val r = root.getAsJsonArray("routes").firstOrNull()?.asJsonObject ?: throw java.io.IOException("No route found")
        val points = r.getAsJsonObject("geometry").getAsJsonArray("coordinates").map {
            val a = it.asJsonArray
            LatLon(a[1].asDouble, a[0].asDouble)
        }
        val steps = r.getAsJsonArray("legs").flatMap { leg ->
            leg.asJsonObject.getAsJsonArray("steps").map { st ->
                val s = st.asJsonObject
                val m = s.getAsJsonObject("maneuver")
                val loc = m.getAsJsonArray("location")
                Step(
                    at = LatLon(loc[1].asDouble, loc[0].asDouble),
                    type = m.get("type")?.asString ?: "turn",
                    modifier = m.get("modifier")?.takeIf { !it.isJsonNull }?.asString,
                    name = s.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    ref = s.get("ref")?.takeIf { !it.isJsonNull }?.asString,
                    exit = m.get("exit")?.takeIf { !it.isJsonNull }?.asInt,
                    distanceM = s.get("distance")?.asDouble ?: 0.0,
                    bearingAfter = m.get("bearing_after")?.takeIf { !it.isJsonNull }?.asDouble,
                )
            }
        }
        return Route(points, r.get("distance").asDouble, r.get("duration").asDouble, steps)
    }
}
