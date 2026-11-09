package com.taycanscreen.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Filament start-up v2 ("lock-on hand-off into Drive", StartupLockOn.dc.html), timed from our first
 * frame. Target beats from the board's timeline strip:
 *   0.0 power-on · 0.3 scan · 0.6 live readings · 1.1 car link lock · 1.6 GPS lock · 2.1 Spotify lock ·
 *   2.5 systems online · 3.0 hand-off · 3.4 map acquire.
 * Each lock waits for its real event (first car data, first GPS fix, Spotify connected) and never fires
 * before its beat. The hand-off always starts by 3.0 s, so Drive takes touches by 3.4 s and the rings
 * have landed by 4.0 s, whatever is still connecting. A ring that didn't lock flies in unlit and its
 * target locks on later by itself (arc flash, map reticle SEARCHING → lock-on, card flash).
 * A tap skips straight to the hand-off.
 */
@Composable
fun StartupOverlay(
    c: DriveColors,
    targets: DriveTargets,
    gps: GpsInfo,
    readouts: StartupReadouts,
    carOk: Boolean,
    spotifyOk: Boolean,
    onFinished: () -> Unit,
) {
    var t by remember { mutableFloatStateOf(0f) }
    // Progress lives in [Run], not in this composable: Android can rebuild the whole screen during
    // boot (seen 1.6 s after the first frame on a cold start) and the sequence must carry on, not restart.
    var handoff by Run.handoff
    val locks = Run.locks
    val arrivals = Run.arrivals
    val events = rememberUpdatedState(Triple(carOk, gps.fix != null, spotifyOk))
    val values = rememberUpdatedState(readouts)

    LaunchedEffect(Unit) {
        var handedOff = Run.handedOff
        var usable = Run.usable
        if (Boot.startupBegun) AppLog.info("startup", "Screen was rebuilt; continuing the start-up sequence")
        else AppLog.info("startup", "Start-up sequence begins")
        Boot.startupBegun = true
        // Timed from our first frame; if that mark is missing for any reason, from this effect's start,
        // so the sequence can never hold the screen.
        val ownStart = SystemClock.elapsedRealtime()
        var ff = 0L
        while (true) {
            withFrameNanos { }
            ff = Boot.firstFrameAt.takeIf { it != 0L } ?: ownStart
            t = (SystemClock.elapsedRealtime() - ff) / 1000f
            // Locks: on the real event, never before the beat, and only while the rings are still centre stage.
            val ev = events.value
            val ok = booleanArrayOf(ev.first, ev.second, ev.third)
            for (i in 0..2) {
                if (locks[i] == null && ok[i] && t >= BEATS[i] && t <= minOf(LOCK_DEADLINE, handoff - 0.4f)) {
                    locks[i] = t
                    AppLog.info("startup", "%s locked at %.2f s".format(NAMES[i], t))
                }
            }
            values.value.all().forEach { (k, v) -> if (v != null && k !in arrivals) arrivals[k] = t }
            if (!handedOff && t >= handoff) {
                handedOff = true
                Run.handedOff = true
                val at = ff + (handoff * 1000).toLong()
                Boot.handoffAt.value = at
                if (locks[0] != null) targets.arcPulseAt = at + 600
                if (locks[2] != null) targets.nowPlayingPulseAt = at + 800
                if (gps.fix != null) targets.acquireAt = at + 700
                AppLog.info(
                    "startup",
                    "Hand-off at %.2f s: car link %s, GPS %s, Spotify %s".format(
                        handoff,
                        locks[0]?.let { "locked" } ?: "pending",
                        locks[1]?.let { "locked" } ?: if (gps.fix != null) "fix after the lock window" else "searching",
                        locks[2]?.let { "locked" } ?: "pending",
                    ),
                )
            }
            if (!usable && t >= handoff + 0.4f) {
                usable = true
                Run.usable = true
                Boot.mark("Drive usable (start-up hand-off done)")
            }
            if (t >= handoff + 1.05f) break
        }
        Boot.startupShown = true
        onFinished()
    }

    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spinner",
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    val h = handoff

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .then(
                if (t < h + 0.4f) Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        if (t < handoff - 0.05f) {
                            handoff = maxOf(t + 0.05f, 0.1f)
                            AppLog.info("startup", "Skipped by a tap at %.2f s".format(t))
                        }
                    }
                } else Modifier,
            ),
    ) {
        val wU = maxWidth.value
        val hU = maxHeight.value
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            drawCovers(c, targets, origin, t, h)
            val pre = keys(t, h - 0.4f to 1f, h - 0.1f to 0f)
            if (pre > 0f) drawPre(c, t, pre, u)
            val ctr = Offset(size.width / 2, size.height / 2)
            // Rings: 1 car link → power arc, 2 GPS → map reticle, 3 Spotify → now playing.
            val dests = listOf(
                (targets.arc?.minus(origin)) ?: Offset(224 * u, 250 * u),
                (targets.marker?.minus(origin)) ?: Offset(size.width - 577 * u, 380 * u),
                (targets.nowPlaying?.center?.minus(origin)) ?: Offset(224 * u, 522 * u),
            )
            for (i in 0..2) drawRing(i, c, t, h, locks[i], ctr, dests[i], u)
        }

        // Live readings counting up (left) and system checks (right), board positions.
        val pre = keys(t, h - 0.4f to 1f, h - 0.1f to 0f)
        if (pre > 0f) {
            val inA = keys(t, 0.6f to 0f, 0.8f to 1f)
            val dx = keys(t, 0.6f to -10f, 0.8f to 0f)
            Column(
                Modifier.offset(x = (80 + dx).dp, y = 262.dp).alpha(inA * pre),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val r = readouts
                Readout("HV", countUp(r.hvVolts, arrivals["hv"], 0.6f, 1.0f, t, 0), "V", c)
                Readout("12V", countUp(r.lowVolts, arrivals["lv"], 0.6f, 0.8f, t, 1), "", c)
                Readout("PACK", countUp(r.packMinC, arrivals["pmin"], 0.7f, 0.8f, t, 0) + "–" + countUp(r.packMaxC, arrivals["pmax"], 0.7f, 0.8f, t, 0), "°C", c)
                Readout("OAT", countUp(r.outsideF, arrivals["oat"], 0.8f, 0.8f, t, 0), "°F", c)
                Readout("BATT", countUp(r.socPct, arrivals["soc"], 0.8f, 1.0f, t, 0), "%", c, c.accent)
            }
            Column(
                Modifier.offset(x = (wU - 482f).dp, y = 290.dp).width(400.dp).alpha(pre),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                val sats = gps.usedSats
                CheckRow("CAR LINK", keys(t, 0.6f to 0f, 0.75f to 1f), locks[0]?.let { "OK" }, spin, c)
                CheckRow("GPS", keys(t, 0.75f to 0f, 0.9f to 1f), locks[1]?.let { if (sats > 0) "$sats SAT" else "FIX" }, spin, c)
                CheckRow("SPOTIFY", keys(t, 0.9f to 0f, 1.05f to 1f), locks[2]?.let { "READY" }, spin, c)
            }
        }

        // Title: FILAMENT / SYSTEMS ONLINE (or what's still pending), 2.5 → 3.0 s.
        val titleA = keys(t, h - 0.5f to 0f, h - 0.35f to 1f, h - 0.2f to 1f, h to 0f)
        val subA = keys(t, h - 0.45f to 0f, h - 0.35f to 1f, h - 0.15f to 1f, h + 0.05f to 0f)
        if (titleA > 0f || subA > 0f) {
            val pending = listOfNotNull(
                if (locks[0] == null) "CAR LINK" else null,
                if (locks[1] == null) "GPS" else null,
                if (locks[2] == null) "SPOTIFY" else null,
            )
            Column(
                Modifier.size(wU.dp, hU.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                BasicText(
                    "FILAMENT",
                    Modifier.alpha(titleA).offset(x = (46 * 0.21f).dp),
                    style = Filament.text(46.sp, 300, c.text).copy(
                        fontFamily = Filament.SairaTitle, letterSpacing = 0.42.em,
                        shadow = if (c.glow > 0f) Shadow(c.accent.copy(alpha = 0.5f), blurRadius = 18f) else null,
                    ),
                )
                BasicText(
                    if (pending.isEmpty()) "SYSTEMS ONLINE" else "READY · ${pending.joinToString(" · ")} PENDING",
                    Modifier.alpha(subA),
                    style = Filament.label(14.sp, c.accent, 0.3f),
                )
            }
        }
    }
}

/** Readings shown counting up during start-up; null = not available (yet). */
data class StartupReadouts(
    val hvVolts: Double?,
    val lowVolts: Double?,
    val packMinC: Double?,
    val packMaxC: Double?,
    val outsideF: Double?,
    val socPct: Double?,
) {
    fun all() = listOf("hv" to hvVolts, "lv" to lowVolts, "pmin" to packMinC, "pmax" to packMaxC, "oat" to outsideF, "soc" to socPct)
}

private const val HANDOFF = 3.0f

/** The one start-up run of this app process (survives the screen being rebuilt). */
private object Run {
    val handoff = mutableFloatStateOf(HANDOFF)
    val locks = arrayOfNulls<Float>(3)
    val arrivals = HashMap<String, Float>()
    var handedOff = false
    var usable = false
}
private const val LOCK_DEADLINE = 2.6f
private val BEATS = floatArrayOf(1.1f, 1.6f, 2.1f)
private val NAMES = arrayOf("Car link", "GPS", "Spotify")
/** Ring appear windows and start rotations (board fv-lock1..3). */
private val APPEAR = arrayOf(0.45f to 0.6f, 0.525f to 0.665f, 0.6f to 0.75f)
private val ROT0 = floatArrayOf(70f, -80f, 110f)
/** Fly windows relative to the hand-off, and landing scales (ring radius → target radius). */
private val FLY = arrayOf(Triple(-0.1f, 0.6f, 0.85f), Triple(0.1f, 0.7f, 0.95f), Triple(0.2f, 0.8f, 1.0f))
private val LAND_SCALE = floatArrayOf(132f / 230f, 34f / 190f, 0.3f)

/** Value counting up from 0 with the board's ease, from max(start, arrival) over [dur] seconds. */
private fun countUp(target: Double?, arrivedAt: Float?, start: Float, dur: Float, t: Float, decimals: Int): String {
    if (target == null || arrivedAt == null) return NO_DATA
    val from = maxOf(start, arrivedAt)
    val f = EaseOut.transform(((t - from) / dur).coerceIn(0f, 1f))
    val v = target * f
    return if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
}

@Composable
private fun Readout(label: String, value: String, unit: String, c: DriveColors, valueColor: Color = c.text) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        BasicText(label, style = Filament.label(15.sp, c.textFaint, 0.16f))
        BasicText(value, style = Filament.label(15.sp, valueColor, 0.16f, 500))
        if (unit.isNotEmpty()) BasicText(unit, style = Filament.label(15.sp, c.textFaint, 0.16f))
    }
}

@Composable
private fun CheckRow(label: String, alpha: Float, status: String?, spin: Float, c: DriveColors) {
    Row(Modifier.alpha(alpha).height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(label, style = Filament.label(16.sp, c.textDim, 0.14f))
        Canvas(Modifier.weight(1f).height(20.dp)) {
            val y = size.height * 0.75f
            drawLine(c.border, Offset(0f, y), Offset(size.width, y), density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(density, 3 * density)))
        }
        Box(Modifier.width(90.dp).height(26.dp), contentAlignment = Alignment.CenterEnd) {
            if (status == null) {
                Canvas(Modifier.size(12.dp).rotate(spin)) {
                    val s = 1.5f * density
                    drawRect(c.textFaint, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
                    drawLine(c.accent, Offset(0f, s / 2), Offset(size.width, s / 2), s)
                }
            } else {
                BasicText(
                    status,
                    style = Filament.label(16.sp, c.accent, 0.14f).copy(
                        shadow = if (c.glow > 0f) Shadow(c.accent.copy(alpha = 0.6f), blurRadius = 10f) else null,
                    ),
                )
            }
        }
    }
}

/** Before the hand-off everything is covered; then the panel and dock fade in and the map is wiped open. */
private fun DrawScope.drawCovers(c: DriveColors, targets: DriveTargets, origin: Offset, t: Float, h: Float) {
    if (t < h) {
        drawRect(c.background)
        return
    }
    val map = targets.map?.translate(-origin)
    val coverA = keys(t, h to 1f, h + 0.4f to 0f)
    if (map == null) {
        drawRect(c.background, alpha = coverA)
        return
    }
    if (coverA > 0f) {
        // Everything but the map: left panel and dock.
        drawRect(c.background, Offset.Zero, Size(map.left, size.height), alpha = coverA)
        drawRect(c.background, Offset(map.left, map.bottom), Size(size.width - map.left, size.height - map.bottom), alpha = coverA)
        drawRect(c.background, Offset(map.right, 0f), Size(size.width - map.right, map.bottom), alpha = coverA)
    }
    // One scan-line wipe opens the map from the top.
    val y = keys(t, h to 0f, h + 0.6f to map.height, easing = EaseWipe)
    if (y < map.height) {
        clipRect(map.left, map.top, map.right, map.bottom) {
            drawRect(c.background, Offset(map.left, map.top + y), Size(map.width, map.height - y))
            val edge = keys(t, h to 0f, h + 0.05f to 1f, h + 0.55f to 1f, h + 0.6f to 0f)
            if (edge > 0f) {
                val ly = map.top + y
                drawLine(c.accent.copy(alpha = 0.35f * edge * (0.3f + 0.7f * c.glow)), Offset(map.left, ly), Offset(map.right, ly), 10 * density)
                drawLine(c.accent.copy(alpha = edge), Offset(map.left, ly), Offset(map.right, ly), 2 * density)
            }
        }
    }
}

/** Corner brackets, horizon line and the scan line (0–0.9 s). */
private fun DrawScope.drawPre(c: DriveColors, t: Float, a: Float, u: Float) {
    val w = size.width
    val hh = size.height
    val f = keys(t, 0f to 0f, 0.3f to 1f, easing = EaseOut)
    if (f > 0f) {
        val L = 32 * u * f
        val col = c.accent.copy(alpha = 0.55f * a)
        val st = Stroke(2 * u)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawPath(Path().apply { moveTo(x, y + dy * L); lineTo(x, y); lineTo(x + dx * L, y) }, col, style = st)
        }
        corner(40 * u, 40 * u, 1f, 1f)
        corner(w - 40 * u, 40 * u, -1f, 1f)
        corner(w - 40 * u, hh - 40 * u, -1f, -1f)
        corner(40 * u, hh - 40 * u, 1f, -1f)
    }
    val hl = keys(t, 0.3f to 0f, 0.6f to 1f, easing = EaseOut)
    if (hl > 0f) drawLine(c.border.copy(alpha = c.border.alpha * a), Offset(w / 2 * (1 - hl), hh / 2), Offset(w / 2 * (1 + hl), hh / 2), u)
    val sy = keys(t, 0.3f to 0f, 0.9f to hh)
    val sa = keys(t, 0.3f to 0f, 0.32f to 1f, 0.9f to 0.8f, 0.93f to 0f) * a
    if (sa > 0f) {
        drawLine(c.accent.copy(alpha = 0.3f * sa * (0.3f + 0.7f * c.glow)), Offset(0f, sy), Offset(w, sy), 8 * u)
        drawLine(c.accent.copy(alpha = sa), Offset(0f, sy), Offset(w, sy), u)
    }
}

private fun DrawScope.drawRing(i: Int, c: DriveColors, t: Float, h: Float, lock: Float?, ctr: Offset, dest: Offset, u: Float) {
    val (a0, a1) = APPEAR[i]
    val appear = keys(t, a0 to 0f, a1 to 1f)
    if (appear <= 0f) return
    val (f0, f1, fEnd) = FLY[i]
    val fly = keys(t, h + f0 to 0f, h + f1 to 1f, easing = EaseFly)
    val flyA = keys(t, h + f1 to 1f, h + fEnd to 0f)
    if (flyA <= 0f) return
    val r0 = ROT0[i]
    val sgn = sign(r0)
    // While waiting the ring creeps round; on its event it snaps past zero and settles.
    fun waiting(at: Float) = r0 - sgn * 14f * (1f - exp(-(at - a0) / 0.8f))
    val rot = if (lock == null) waiting(t) else keys(t, lock to waiting(lock), lock + 0.1f to -5f * sgn, lock + 0.2f to 0f)
    val pos = Offset(ctr.x + (dest.x - ctr.x) * fly, ctr.y + (dest.y - ctr.y) * fly)
    val sc = 1f + (LAND_SCALE[i] - 1f) * fly
    val alpha = appear * flyA
    val faint = c.border.copy(alpha = minOf(1f, c.border.alpha * 1.1f) * alpha)
    val flash = if (lock == null) 0f else keys(t, lock to 0f, lock + 0.05f to 0.9f, lock + 0.5f to 0.16f) * (0.4f + 0.6f * c.glow)
    val on = if (lock == null) 0f else keys(t, lock to 0f, lock + 0.05f to 1f)
    translate(pos.x - ctr.x, pos.y - ctr.y) {
        scale(sc, sc, ctr) {
            rotate(rot, ctr) {
                when (i) {
                    0 -> {
                        val dash = floatArrayOf(12 * u, 5 * u)
                        arcD(faint, ctr.x, ctr.y, 230 * u, -120f, 120f, 14 * u, dash)
                        arcD(c.accent, ctr.x, ctr.y, 230 * u, -120f, 120f, 34 * u, alpha = flash * alpha)
                        arcD(c.accent, ctr.x, ctr.y, 230 * u, -120f, 120f, 14 * u, dash, alpha = on * alpha)
                    }
                    1 -> {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(54 * u, 16 * u))
                        drawCircle(faint, 190 * u, ctr, style = Stroke(6 * u, pathEffect = dash))
                        if (flash > 0f) drawCircle(c.accent.copy(alpha = flash * alpha), 190 * u, ctr, style = Stroke(22 * u))
                        if (on > 0f) {
                            drawCircle(c.accent.copy(alpha = on * alpha), 190 * u, ctr, style = Stroke(6 * u, pathEffect = dash))
                            val tick = c.accent.copy(alpha = on * alpha)
                            for (d in listOf(0f, 90f, 180f, 270f)) {
                                drawLine(tick, pol(ctr.x, ctr.y, 176 * u, d), pol(ctr.x, ctr.y, 208 * u, d), 2 * u)
                            }
                        }
                    }
                    else -> {
                        val dash = floatArrayOf(7 * u, 3 * u)
                        arcD(faint, ctr.x, ctr.y, 150 * u, -135f, 135f, 10 * u, dash)
                        arcD(c.accent, ctr.x, ctr.y, 150 * u, -135f, 135f, 28 * u, alpha = flash * alpha)
                        arcD(c.accent, ctr.x, ctr.y, 150 * u, -135f, 135f, 10 * u, dash, alpha = on * alpha)
                    }
                }
            }
        }
    }
}
