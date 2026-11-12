package com.taycanscreen.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.nav.Nav
import com.taycanscreen.app.nav.RouteMath
import com.taycanscreen.app.nav.Step
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Draws the active route on the vector map (remaining part only, so it shrinks as you drive), under the
 * street names, and the destination; frames the whole route when a new one starts. [onOverview] tells
 * the map to stop following for a moment.
 */
@Composable
fun RouteOnMap(map: MapLibreMap?, style: Style?, c: DriveColors, onOverview: () -> Unit) {
    val active by Nav.active.collectAsState()
    val progress by Nav.progress.collectAsState()
    // Sources and layers (again after a style reload or a map-size change, which makes a new map).
    LaunchedEffect(style, c) {
        val s = style ?: return@LaunchedEffect
        if (s.getSource("route") == null) s.addSource(GeoJsonSource("route"))
        if (s.getSource("dest") == null) s.addSource(GeoJsonSource("dest"))
        val below = com.taycanscreen.app.map.MapTheme.firstSymbolLayerId(s)
        fun line(id: String) = s.getLayer(id) as? LineLayer ?: LineLayer(id, "route").withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).also { if (below != null) s.addLayerBelow(it, below) else s.addLayer(it) }
        line("route-casing").setProperties(PropertyFactory.lineColor(c.background.toArgb()), PropertyFactory.lineWidth(11f))
        line("route-line").setProperties(PropertyFactory.lineColor(c.accent.toArgb()), PropertyFactory.lineWidth(6f))
        fun circle(id: String) = s.getLayer(id) as? CircleLayer ?: CircleLayer(id, "dest").also { s.addLayer(it) }
        circle("dest-ring").setProperties(
            PropertyFactory.circleRadius(13f), PropertyFactory.circleColor(c.accent.copy(alpha = 0.15f).toArgb()),
            PropertyFactory.circleStrokeColor(c.accent.toArgb()), PropertyFactory.circleStrokeWidth(1.5f),
        )
        circle("dest-dot").setProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(c.background.toArgb()),
            PropertyFactory.circleStrokeColor(c.accent.toArgb()), PropertyFactory.circleStrokeWidth(3f),
        )
    }
    // Show the remaining route.
    LaunchedEffect(style, active, progress?.seg, progress?.onRoute) {
        val s = style ?: return@LaunchedEffect
        val route = s.getSource("route") as? GeoJsonSource ?: return@LaunchedEffect
        val dest = s.getSource("dest") as? GeoJsonSource ?: return@LaunchedEffect
        val a = active
        if (a == null) {
            route.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            dest.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return@LaunchedEffect
        }
        val pts = a.route.points
        val p = progress
        val remaining = if (p == null) pts else listOf(p.onRoute) + pts.drop(p.seg + 1)
        if (remaining.size >= 2) route.setGeoJson(LineString.fromLngLats(remaining.map { Point.fromLngLat(it.lon, it.lat) }))
        dest.setGeoJson(Point.fromLngLat(a.dest.lon, a.dest.lat))
    }
    // A new route (not a re-route): show all of it, then the map follows the car again.
    LaunchedEffect(active?.id, map) {
        val a = active ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (a.route.points.size < 2) return@LaunchedEffect
        onOverview()
        val bounds = LatLngBounds.Builder().includes(a.route.points.map { LatLng(it.lat, it.lon) }).build()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 900)
    }
}

private fun turnIcon(s: Step?): ImageVector = when {
    s == null -> FIcons.TurnStraight
    s.type == "arrive" -> FIcons.Pin
    s.type in setOf("roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary") -> FIcons.Roundabout
    s.modifier == "uturn" -> FIcons.UTurn
    s.modifier == "sharp right" -> FIcons.TurnSharpRight
    s.modifier == "sharp left" -> FIcons.TurnSharpLeft
    s.modifier == "right" -> FIcons.TurnRight
    s.modifier == "left" -> FIcons.TurnLeft
    s.modifier == "slight right" -> FIcons.TurnSlightRight
    s.modifier == "slight left" -> FIcons.TurnSlightLeft
    else -> FIcons.TurnStraight
}

/** Short turn word for the v3 card: RIGHT, SLIGHT LEFT, U-TURN, ROUNDABOUT… */
private fun turnWord(s: Step): String = when {
    s.type == "arrive" -> "ARRIVE"
    s.type in setOf("roundabout", "rotary", "roundabout turn") -> "ROUNDABOUT"
    s.modifier == "uturn" -> "U-TURN"
    s.type == "merge" -> "MERGE"
    s.type == "on ramp" -> "RAMP"
    s.type == "off ramp" -> "EXIT"
    s.type == "fork" -> "KEEP ${(s.modifier ?: "").replace("slight ", "").uppercase()}".trim()
    s.modifier != null -> s.modifier.uppercase()
    else -> "CONTINUE"
}

/**
 * Next manoeuvre (v3 board): distance, turn and where it heads in local terms ("RIGHT · DIAMOND HEAD"),
 * the street, and the one after ("Then left, mauka, on Piʻikoi St").
 */
@Composable
fun NextTurnBanner(c: DriveColors, modifier: Modifier) {
    val active by Nav.active.collectAsState()
    val p by Nav.progress.collectAsState()
    val busy by Nav.busy.collectAsState()
    val mode by com.taycanscreen.app.nav.LocalDirections.mode.collectAsState()
    val a = active ?: return
    val prog = p
    val step = prog?.next
    val here = prog?.onRoute
    fun dir(s: Step): String? = s.bearingAfter?.let { com.taycanscreen.app.nav.LocalDirections.name(it, here, mode) }
    Column(
        modifier.widthIn(min = 300.dp, max = 460.dp).cardLine(1.dp, c.border, c.overlay).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            busy != null -> BasicText(busy!!.uppercase(), style = Filament.label(15.sp, c.accent, 0.08f))
            prog == null -> BasicText("ROUTE READY", style = Filament.label(15.sp, c.accent, 0.08f))
            prog.arrived -> {
                BasicText("ARRIVED", style = Filament.label(15.sp, c.accent, 0.08f))
                BasicText(a.dest.name, style = Filament.text(20.sp, 500, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BasicText(RouteMath.formatDistance(prog.toNextM), Modifier.alignByBaseline(), style = Filament.num(36.sp, 300, c.text))
                    BasicText(
                        if (prog.offRoute) "RE-ROUTING" else step?.let { s -> listOfNotNull(turnWord(s), dir(s)).joinToString(" · ") } ?: "CONTINUE",
                        Modifier.alignByBaseline(),
                        style = Filament.label(15.sp, if (prog.offRoute) c.warn else c.accent, 0.08f, 500),
                    )
                }
                BasicText(
                    step?.let { if (it.type == "arrive") a.dest.name else it.name.ifBlank { it.ref ?: RouteMath.instruction(it) } } ?: a.dest.name,
                    style = Filament.text(22.sp, 500, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // The manoeuvre after this one.
                val steps = a.route.steps
                val idx = step?.let { steps.indexOf(it) } ?: -1
                val then = steps.getOrNull(idx + 1)
                if (then != null) {
                    val word = when {
                        then.type == "arrive" -> null
                        then.modifier == "uturn" -> "U-turn"
                        then.modifier != null -> then.modifier
                        else -> "continue"
                    }
                    BasicText(
                        if (then.type == "arrive") "Then arrive at ${a.dest.name}"
                        else "Then $word" + (dir(then)?.let { ", ${it.lowercase().replace("ʻewa", "ʻEwa").replace("diamond head", "Diamond Head")}" } ?: "") +
                            (then.name.ifBlank { then.ref ?: "" }.takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""),
                        style = Filament.text(17.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Arrival time, time and distance left, battery on arrival, hand-off, end (under the next turn). */
@Composable
fun RouteCard(c: DriveColors, modifier: Modifier) {
    val active by Nav.active.collectAsState()
    val p by Nav.progress.collectAsState()
    val busy by Nav.busy.collectAsState()
    val error by Nav.error.collectAsState()
    val readings by com.taycanscreen.app.obd.ObdEngine.readings.collectAsState()
    val context = LocalContext.current
    val a = active
    if (a == null) {
        val msg = error ?: busy ?: return
        Row(
            modifier.height(72.dp).cardLine(1.dp, if (error != null) c.warn else c.border, c.overlay).padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(msg, Modifier.weight(1f), style = Filament.text(17.sp, 500, if (error != null) c.warn else c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(72.dp).clickable { Nav.clearError(); if (busy != null) Nav.stop() }, contentAlignment = Alignment.Center) {
                Icon(FIcons.Close, "Dismiss", tint = c.text, modifier = Modifier.size(26.dp))
            }
        }
        return
    }
    val prog = p
    val remaining = prog?.remainingM ?: a.route.distanceM
    val arrive = prog?.arriveAtMs ?: (System.currentTimeMillis() + (a.route.durationS * 1000).toLong())
    val minutes = ((arrive - System.currentTimeMillis()) / 60_000.0).coerceAtLeast(0.0)
    val now = com.taycanscreen.app.util.Mono.ms()
    val soc = readings[com.taycanscreen.app.obd.Taycan.SOC_DISPLAY.id]?.takeIf { now - it.at <= com.taycanscreen.app.obd.Taycan.SOC_DISPLAY.staleMs }?.value
    val range = readings[com.taycanscreen.app.obd.Taycan.RANGE.id]?.takeIf { now - it.at <= com.taycanscreen.app.obd.Taycan.RANGE.staleMs }?.value
    val battery = com.taycanscreen.app.car.Arrival.pct(remaining, soc, range)
    // Under the next-turn card, top left (owner, 2026-11-10): arrival and distance, then battery on
    // arrival with the Waze hand-off and End.
    Column(
        modifier.cardLine(1.dp, c.border, c.overlay).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BasicText(
                Instant.ofEpochMilli(arrive).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm")),
                Modifier.alignByBaseline(), style = Filament.num(26.sp, 400, c.text),
            )
            BasicText(
                (if (minutes < 60) "${minutes.toInt()} MIN" else "%d H %02d".format((minutes / 60).toInt(), (minutes % 60).toInt())) +
                    " · " + RouteMath.formatDistance(remaining).uppercase(),
                Modifier.alignByBaseline(), style = Filament.label(15.sp, c.textDim, 0.06f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (battery != null) {
                BasicText(
                    "${battery.roundToInt().coerceAtLeast(0)}% ON ARRIVAL",
                    style = Filament.label(15.sp, when { battery < 5 -> c.danger; battery < 10 -> c.warn; else -> c.accent }, 0.04f),
                )
            }
            Spacer(Modifier.weight(1f))
            // Real buttons with a gap between them (they were two small words side by side).
            CardButton(handoffLabel(context), c) { handOff(context, a.dest.lat, a.dest.lon) }
            Spacer(Modifier.width(10.dp))
            CardButton("END", c) { AppLog.info("nav", "Route ended by the owner"); Nav.stop() }
        }
    }
}

@Composable
private fun CardButton(label: String, c: DriveColors, onClick: () -> Unit) {
    Box(
        Modifier.height(64.dp).widthIn(min = 96.dp).cardLine(1.dp, c.lineStrong).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.label(15.sp, c.text, 0.08f, 500)) }
}

private fun handoffLabel(context: Context): String = when {
    installed(context, "com.waze") -> "WAZE"
    installed(context, "com.google.android.apps.maps") -> "MAPS"
    else -> "WAZE"
}

private fun installed(context: Context, pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

/** Opens the same destination in Waze (or Google Maps) for voice guidance. */
private fun handOff(context: Context, lat: Double, lon: Double) {
    val tries = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")).setPackage("com.waze"),
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).setPackage("com.google.android.apps.maps"),
    )
    for (i in tries) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.info("nav", "Handed the destination to ${i.`package`}")
            return
        } catch (_: Exception) {
        }
    }
    Toast.makeText(context, "Waze isn't installed on the box", Toast.LENGTH_LONG).show()
}

