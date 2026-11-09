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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Draws the active route on the osmdroid map (remaining part only, so it shrinks as you drive) and a
 * destination marker; frames the whole route when a new one starts. [onOverview] tells the map to
 * stop following for a moment.
 */
@Composable
fun RouteOnMap(mapView: MapView, c: DriveColors, onOverview: () -> Unit) {
    val active by Nav.active.collectAsState()
    val progress by Nav.progress.collectAsState()
    val px = LocalDensity.current.density // screen px per design px
    val overlays = remember(mapView) {
        fun line() = Polyline(mapView).apply {
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            isGeodesic = false
            infoWindow = null
        }
        Triple(line(), line(), line()) // glow, casing, line
    }
    val dest = remember(mapView) { DestinationOverlay() }

    LaunchedEffect(c, px) {
        overlays.first.outlinePaint.apply { color = c.accent.copy(alpha = 0.22f * c.glow).toArgb(); strokeWidth = 26f * px }
        overlays.second.outlinePaint.apply { color = c.panel.toArgb(); strokeWidth = 15f * px }
        overlays.third.outlinePaint.apply { color = c.accent.toArgb(); strokeWidth = 8f * px }
        dest.accent = c.accent.toArgb()
        dest.panel = c.panel.toArgb()
        dest.px = px
        mapView.invalidate()
    }
    DisposableEffect(mapView) {
        // Under the car marker: insert at the bottom of the overlay list.
        mapView.overlays.add(0, dest)
        mapView.overlays.add(0, overlays.third)
        mapView.overlays.add(0, overlays.second)
        mapView.overlays.add(0, overlays.first)
        onDispose {
            mapView.overlays.removeAll(listOf(overlays.first, overlays.second, overlays.third, dest))
        }
    }
    // Show the remaining route.
    LaunchedEffect(active, progress?.seg, progress?.onRoute) {
        val a = active
        val all = overlays.toList()
        if (a == null) {
            all.forEach { it.setPoints(emptyList()) }
            dest.at = null
            mapView.invalidate()
            return@LaunchedEffect
        }
        val pts = a.route.points
        val p = progress
        val remaining = if (p == null) pts.map { GeoPoint(it.lat, it.lon) }
        else listOf(GeoPoint(p.onRoute.lat, p.onRoute.lon)) + pts.drop(p.seg + 1).map { GeoPoint(it.lat, it.lon) }
        all.forEach { it.setPoints(remaining) }
        dest.at = GeoPoint(a.dest.lat, a.dest.lon)
        mapView.invalidate()
    }
    // A new route (not a re-route): show all of it, then the map follows the car again.
    LaunchedEffect(active?.id) {
        val a = active ?: return@LaunchedEffect
        if (a.route.points.size < 2) return@LaunchedEffect
        onOverview()
        val box = BoundingBox.fromGeoPoints(a.route.points.map { GeoPoint(it.lat, it.lon) })
        mapView.zoomToBoundingBox(box, true, (90 * px).toInt())
    }
}

/** Diamond pin at the destination. */
private class DestinationOverlay : Overlay() {
    var at: GeoPoint? = null
    var accent = 0
    var panel = 0
    var px = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = android.graphics.Point()

    override fun draw(c: android.graphics.Canvas, pj: org.osmdroid.views.Projection) {
        val g = at ?: return
        pj.toPixels(g, pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        val s = 14f * px
        val path = android.graphics.Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close() }
        paint.style = Paint.Style.FILL
        paint.color = panel
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * px
        paint.color = accent
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, 4f * px, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * px
        paint.alpha = 140
        c.drawCircle(x, y, 26f * px, paint)
        paint.alpha = 255
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
        modifier.widthIn(min = 300.dp, max = 460.dp).background(c.overlay).border(1.dp, c.border).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            busy != null -> BasicText(busy!!.uppercase(), style = Filament.label(12.sp, c.accent, 0.12f))
            prog == null -> BasicText("ROUTE READY", style = Filament.label(12.sp, c.accent, 0.12f))
            prog.arrived -> {
                BasicText("ARRIVED", style = Filament.label(12.sp, c.accent, 0.12f))
                BasicText(a.dest.name, style = Filament.text(20.sp, 500, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BasicText(RouteMath.formatDistance(prog.toNextM), Modifier.alignByBaseline(), style = Filament.num(36.sp, 300, c.text))
                    BasicText(
                        if (prog.offRoute) "RE-ROUTING" else step?.let { s -> listOfNotNull(turnWord(s), dir(s)).joinToString(" · ") } ?: "CONTINUE",
                        Modifier.alignByBaseline(),
                        style = Filament.label(12.sp, if (prog.offRoute) c.warn else c.accent, 0.12f),
                    )
                }
                BasicText(
                    step?.let { if (it.type == "arrive") a.dest.name else it.name.ifBlank { it.ref ?: RouteMath.instruction(it) } } ?: a.dest.name,
                    style = Filament.text(20.sp, 500, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                        style = Filament.text(15.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Arrival time, time and distance left, battery on arrival, hand-off, end (v3 board, bottom right). */
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
            modifier.height(64.dp).background(c.overlay).border(1.dp, if (error != null) c.warn else c.border).padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(msg, style = Filament.text(17.sp, 500, if (error != null) c.warn else c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(56.dp).clickable { Nav.clearError(); if (busy != null) Nav.stop() }, contentAlignment = Alignment.Center) {
                Icon(FIcons.Close, "Dismiss", tint = c.textDim, modifier = Modifier.size(22.dp))
            }
        }
        return
    }
    val prog = p
    val remaining = prog?.remainingM ?: a.route.distanceM
    val arrive = prog?.arriveAtMs ?: (System.currentTimeMillis() + (a.route.durationS * 1000).toLong())
    val minutes = ((arrive - System.currentTimeMillis()) / 60_000.0).coerceAtLeast(0.0)
    val now = System.currentTimeMillis()
    val soc = readings[com.taycanscreen.app.obd.Taycan.SOC_DISPLAY.id]?.takeIf { now - it.at <= com.taycanscreen.app.obd.Taycan.SOC_DISPLAY.staleMs }?.value
    val range = readings[com.taycanscreen.app.obd.Taycan.RANGE.id]?.takeIf { now - it.at <= com.taycanscreen.app.obd.Taycan.RANGE.staleMs }?.value
    val battery = com.taycanscreen.app.car.Arrival.pct(remaining, soc, range)
    Row(
        modifier.background(c.overlay).border(1.dp, c.border).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BasicText(Instant.ofEpochMilli(arrive).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm")), style = Filament.num(22.sp, 400, c.text))
        BasicText(
            (if (minutes < 60) "${minutes.toInt()} MIN" else "%d H %02d".format((minutes / 60).toInt(), (minutes % 60).toInt())) +
                " · " + RouteMath.formatDistance(remaining).uppercase(),
            style = Filament.label(12.sp, c.textDim, 0.08f),
        )
        if (battery != null) {
            BasicText(
                "${battery.roundToInt().coerceAtLeast(0)}% ON ARRIVAL",
                style = Filament.label(12.sp, when { battery < 5 -> c.danger; battery < 10 -> c.warn; else -> c.accent }, 0.08f),
            )
        }
        Box(Modifier.height(48.dp).clickable { handOff(context, a.dest.lat, a.dest.lon) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            BasicText(handoffLabel(context), style = Filament.label(12.sp, c.textDim, 0.12f))
        }
        Box(Modifier.height(48.dp).clickable { AppLog.info("nav", "Route ended by the owner"); Nav.stop() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            BasicText("END", style = Filament.label(12.sp, c.textDim, 0.12f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, c: DriveColors, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        BasicText(label, style = Filament.label(12.sp, c.textFaint, 0.16f))
        BasicText(value, style = Filament.num(26.sp, 600, color))
    }
}

@Composable
private fun CardButton(label: String, c: DriveColors, onClick: () -> Unit) {
    Box(
        Modifier.height(56.dp).background(c.accentSoft, Filament.CutS).insetLine(c.accentLine).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.label(15.sp, c.accent, 0.12f, 500)) }
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

