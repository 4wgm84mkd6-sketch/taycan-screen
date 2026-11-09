package com.taycanscreen.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val NO_DATA = "—"

/**
 * Charge colour (owner, 2026-11-09): green when full, through yellow, to red when low.
 * HV battery: red at 10 %, yellow about 50 %, green from 90 %.
 */
fun chargeColor(pct: Double, c: DriveColors, low: Double = 10.0, high: Double = 90.0): Color {
    val f = ((pct - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    return Color.hsv(120f * f, if (c.isNight) 0.78f else 0.88f, if (c.isNight) 1.0f else 0.70f)
}

/** 12 V battery on its own scale: a 12 V battery is unhealthy well before it's empty (red ≤ 60 %, green ≥ 90 %). */
fun lowVoltageColor(pct: Double, c: DriveColors): Color = chargeColor(pct, c, low = 60.0, high = 90.0)

/** A value from the car with its freshness: [dim] once it's getting old (shown faded), gone once stale. */
data class V(val x: Double, val dim: Boolean)

/** Places a child centred on [x] with its first text baseline at [baseline] inside the parent. */
fun Modifier.baselineAt(x: Dp, baseline: Dp) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    val b = p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
    layout(p.width, p.height) { p.place(x.roundToPx() - p.width / 2, baseline.roundToPx() - b) }
}

/** Places a child so its centre sits at (x, y) inside the parent. */
fun Modifier.centerAt(x: Dp, y: Dp) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(x.roundToPx() - p.width / 2, y.roundToPx() - p.height / 2) }
}

/** Arc in design angles (0° = top, clockwise), centre and radius in px. */
fun DrawScope.arcD(
    color: Color, cx: Float, cy: Float, r: Float, d0: Float, d1: Float, width: Float,
    dash: FloatArray? = null, alpha: Float = 1f,
) {
    if (abs(d1 - d0) < 0.05f || alpha <= 0f) return
    drawArc(
        color = color, startAngle = d0 - 90f, sweepAngle = d1 - d0, useCenter = false,
        topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r), alpha = alpha.coerceIn(0f, 1f),
        style = Stroke(width, cap = StrokeCap.Butt, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }),
    )
}

fun pol(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(cx + r * sin(a), cy - r * cos(a))
}

/** Piecewise-linear keyframes: pairs of (time, value). */
fun keys(t: Float, vararg kv: Pair<Float, Float>, easing: CubicBezierEasing? = null): Float {
    if (t <= kv.first().first) return kv.first().second
    for (i in 1 until kv.size) {
        val (t1, v1) = kv[i]
        val (t0, v0) = kv[i - 1]
        if (t <= t1) {
            val f = if (t1 == t0) 1f else (t - t0) / (t1 - t0)
            return v0 + (v1 - v0) * (easing?.transform(f) ?: f)
        }
    }
    return kv.last().second
}

val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
val EaseSweep = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
val EaseFly = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)
val EaseWipe = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)

/** Gauge angle for a power value: drawing power to the right (400 kW at 120°), regen to the left (270 kW at −120°). */
fun powerAngle(kw: Double): Float =
    if (kw < 0) (min(400.0, -kw) * 0.3).toFloat() else (-min(270.0, kw) * (120.0 / 270.0)).toFloat()

/**
 * The power arc with the speed inside it (Filament Main: 392×260, centre-zero, −400…+270 kW).
 * [sweepT] is seconds since the first-shift-to-D sweep began (null = no sweep); [pulseT] is seconds
 * since the car link locked onto this arc (start-up hand-off), for a one-off flash.
 */
@Composable
fun PowerGauge(
    speedMph: V?, kw: V?, linkProblem: String?, c: DriveColors,
    sweepT: Float?, pulseT: Float?, modifier: Modifier = Modifier,
) {
    Box(modifier.size(392.dp, 260.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val cx = 196 * u
            val cy = 170 * u
            for (d in -120..120 step 6) {
                val major = d % 30 == 0
                val a = pol(cx, cy, (if (major) 141 else 145) * u, d.toFloat())
                val b = pol(cx, cy, 151 * u, d.toFloat())
                drawLine(if (major) c.lineStrong else c.border, a, b, strokeWidth = (if (major) 2f else 1.5f) * u)
            }
            val dash = floatArrayOf(7 * u, 3 * u)
            arcD(c.lineFaint, cx, cy, 132 * u, -120f, 120f, 10 * u, dash)
            if (kw != null) {
                val ang = powerAngle(kw.x)
                val col = if (kw.x > 0) c.energyIn else c.accent
                val a = if (kw.dim) 0.45f else 1f
                arcD(col, cx, cy, 132 * u, 0f, ang, 26 * u, alpha = 0.16f * c.glow * a)
                arcD(col, cx, cy, 132 * u, 0f, ang, 10 * u, dash, alpha = a)
            }
            // Pointer at zero.
            drawPath(Path().apply {
                moveTo(189 * u, 22 * u); lineTo(203 * u, 22 * u); lineTo(196 * u, 31 * u); close()
            }, c.accent)

            // Lock flash when the car link lands here (start-up), like the board's ring flash.
            if (pulseT != null) {
                val op = keys(pulseT, 0f to 0f, 0.05f to 0.9f, 0.6f to 0.16f, 1.0f to 0f)
                arcD(c.accent, cx, cy, 132 * u, -120f, 120f, 26 * u, alpha = op * (0.4f + 0.6f * c.glow))
                arcD(c.accent, cx, cy, 132 * u, -120f, 120f, 10 * u, dash, alpha = op)
            }
            // First shift into D: the needle sweeps full range and settles at zero; the arc pulses once.
            if (sweepT != null) {
                val flash = keys(sweepT, 0f to 0f, 0.14f to 0.55f, 0.8f to 0.35f, 0.98f to 0f)
                arcD(c.accent, cx, cy, 132 * u, -120f, 120f, 26 * u, alpha = flash * 0.64f)
                arcD(c.accent, cx, cy, 132 * u, -120f, 120f, 10 * u, dash, alpha = flash * 1.8f)
                val needleOp = keys(sweepT, 0f to 0f, 0.05f to 1f, 1.0f to 1f, 1.15f to 0f)
                val ang = when {
                    sweepT < 0.05f -> 0f
                    sweepT < 0.30f -> keys(sweepT, 0.05f to 0f, 0.30f to -120f, easing = EaseSweep)
                    sweepT < 0.60f -> keys(sweepT, 0.30f to -120f, 0.60f to 120f, easing = EaseSweep)
                    else -> keys(sweepT, 0.60f to 120f, 0.80f to 0f, easing = EaseSweep)
                }
                if (needleOp > 0f) rotate(ang, Offset(cx, cy)) {
                    drawLine(c.accent.copy(alpha = 0.3f * needleOp), Offset(cx, 18 * u), Offset(cx, 58 * u), 12 * u)
                    drawLine(c.text.copy(alpha = needleOp), Offset(cx, 18 * u), Offset(cx, 58 * u), 3 * u)
                }
            }
        }
        // Scale labels.
        for (k in listOf(100, 200, 300, 400)) {
            val q = pol(196f, 170f, 165f, k * 0.3f)
            BasicText("$k", Modifier.centerAt(q.x.dp, q.y.dp), style = Filament.label(13.sp, c.textFaint, 0f))
        }
        for (k in listOf(100, 200)) {
            val q = pol(196f, 170f, 165f, -k * 120f / 270f)
            BasicText("+$k", Modifier.centerAt(q.x.dp, q.y.dp), style = Filament.label(13.sp, c.textFaint, 0f))
        }
        val glowText = if (c.glow > 0f) c.accent.copy(alpha = 0.5f) else null
        // Fixed baselines (design: speed ≈ 169, MPH ≈ 207, power row ≈ 250), so bigger text can't overlap.
        BasicText(
            speedMph?.x?.roundToInt()?.toString() ?: NO_DATA,
            Modifier.baselineAt(196.dp, 164.dp).alpha(if (speedMph?.dim == true) 0.45f else 1f),
            style = Filament.num(132.sp, 500, c.text, glowText).copy(letterSpacing = (-2.6).sp),
            maxLines = 1,
        )
        BasicText("MPH", Modifier.baselineAt(196.dp, 194.dp), style = Filament.label(14.sp, c.textDim, 0.24f))
        Box(Modifier.fillMaxWidth().offset(y = 206.dp), contentAlignment = Alignment.TopCenter) {
            if (linkProblem != null) {
                BasicText(
                    linkProblem.uppercase(), Modifier.padding(horizontal = 24.dp),
                    style = Filament.label(12.sp, c.warn, 0.06f).copy(textAlign = TextAlign.Center),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            } else {
                val (label, value, color) = when {
                    kw == null -> Triple("POWER", NO_DATA, c.textDim)
                    kw.x < -0.5 -> Triple("DRAW", "${(-kw.x).roundToInt()}", c.text)
                    kw.x > 0.5 -> Triple("REGEN", "+${kw.x.roundToInt()}", c.energyIn)
                    else -> Triple("IDLE", "0", c.textDim)
                }
                Row(
                    Modifier.layout { m, cons ->
                        // Baseline of the power row at 244 in the gauge (this Box starts at 206).
                        val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                        val b = p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
                        layout(p.width, p.height) { p.place(0, 38.dp.roundToPx() - b) }
                    }.alpha(if (kw?.dim == true) 0.45f else 1f),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(label, Modifier.alignByBaseline(), style = Filament.label(13.sp, c.textDim, 0.18f))
                    BasicText(
                        value, Modifier.alignByBaseline(),
                        style = Filament.num(32.sp, 600, color, if (color == c.energyIn && c.glow > 0f) c.energyIn.copy(alpha = 0.5f) else null),
                    )
                    BasicText("kW", Modifier.alignByBaseline(), style = Filament.text(18.sp, 400, c.textDim))
                }
            }
        }
    }
}

/** Battery % and range, the 20-segment bar, and pack temperature / 12 V (Filament Main, 78 px tall). */
@Composable
fun BatteryBlock(soc: V?, rangeMi: V?, packC: V?, lowVoltage: LowVoltageValues, c: DriveColors, onOpen12V: () -> Unit, modifier: Modifier = Modifier) {
    // The whole block is the tap target for the 12 V gauge (the 12V line alone is too small to hit while driving).
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen12V), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.alpha(if (soc?.dim == true) 0.45f else 1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(soc?.x?.roundToInt()?.toString() ?: NO_DATA, Modifier.alignByBaseline(), style = Filament.num(34.sp, 600, c.text))
                BasicText("%", Modifier.alignByBaseline(), style = Filament.text(18.sp, 400, c.textDim))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.alpha(if (rangeMi?.dim == true) 0.45f else 1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(rangeMi?.x?.roundToInt()?.toString() ?: NO_DATA, Modifier.alignByBaseline(), style = Filament.num(34.sp, 600, c.text))
                BasicText("mi", Modifier.alignByBaseline(), style = Filament.text(18.sp, 400, c.textDim))
            }
        }
        val pct = soc?.x ?: 0.0
        val col = if (soc == null) c.lineFaint else chargeColor(pct, c)
        val filled = (pct / 5.0).roundToInt()
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in 0 until 20) {
                val on = soc != null && k < filled
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .then(if (on && c.glow > 0f) Modifier.drawBehind {
                            drawRect(col.copy(alpha = 0.22f), Offset(-2 * density, -2 * density), Size(size.width + 4 * density, size.height + 4 * density))
                        } else Modifier)
                        .background(if (on) col else c.lineFaint),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            BasicText(
                "PACK ${packC?.x?.roundToInt() ?: NO_DATA} °C",
                Modifier.alpha(if (packC?.dim == true) 0.45f else 1f),
                style = Filament.label(13.sp, c.textFaint, 0f),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                lowVoltageSummary(lowVoltage) + "  ›",
                style = Filament.label(13.sp, lowVoltage.soc?.let { lowVoltageColor(it.x, c) } ?: c.textDim, 0f),
            )
        }
    }
}

/**
 * Charging theater (parked + energy flowing in): the battery ring with 60 segments, the charge rate
 * on the inner arc, and current lines flowing into the ring at a speed scaled to the real kW.
 */
@Composable
fun ChargingBlock(soc: V?, chargeKw: Double, rangeMi: V?, packC: V?, c: DriveColors, modifier: Modifier = Modifier) {
    val breathe by rememberInfiniteTransition(label = "lead").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "breathe",
    )
    // Flow phase in design px; speed follows the charge rate (11 kW ≈ 46 px/s, 250 kW ≈ 400 px/s).
    var phase by remember { mutableFloatStateOf(0f) }
    val kwNow by rememberUpdatedState(chargeKw)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase = (phase + (30f + kwNow.toFloat().coerceIn(0f, 350f) * 1.5f) * (t - last) / 1e9f) % 2400f
                last = t
            }
        }
    }
    Column(modifier.fillMaxWidth().height(348.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val u = density
                val cx = size.width / 2
                val cy = 130 * u
                // Current lines: three each side, flowing in towards the ring.
                val pattern = floatArrayOf(10 * u, 14 * u)
                // Positive phase moves dashes towards the line's start, so count down to move them inwards.
                val ph = (24f - (phase % 24f)) * u
                for (dy in listOf(-36f, 0f, 36f)) {
                    val y = cy + dy * u
                    val reach = sqrt(122f * 122f - dy * dy) * u
                    for (side in listOf(-1, 1)) {
                        val outer = Offset(if (side < 0) 0f else size.width, y)
                        val inner = Offset(cx + side * reach, y)
                        drawLine(c.lineFaint, outer, inner, 2 * u)
                        // Dashes travel from the outer end to the ring: the phase grows towards the centre.
                        drawLine(
                            c.energyIn.copy(alpha = 0.8f), outer, inner, 2 * u,
                            pathEffect = PathEffect.dashPathEffect(pattern, ph),
                        )
                        if (c.glow > 0f) drawLine(
                            c.energyIn.copy(alpha = 0.18f), outer, inner, 7 * u,
                            pathEffect = PathEffect.dashPathEffect(pattern, ph),
                        )
                    }
                }
                drawCircle(c.panel, 121 * u, Offset(cx, cy))
                drawCircle(c.border, 122 * u, Offset(cx, cy), style = Stroke(1 * u))
                val segLen = (2 * PI * 104 / 60).toFloat()
                val dash = floatArrayOf((segLen - 3) * u, 3 * u)
                arcD(c.lineFaint, cx, cy, 104 * u, 0f, 359.99f, 14 * u, dash)
                val pct = (soc?.x ?: 0.0).coerceIn(0.0, 100.0).toFloat()
                val fDeg = pct * 3.6f
                arcD(c.energyIn, cx, cy, 104 * u, 0f, fDeg, 30 * u, alpha = 0.16f * c.glow)
                arcD(c.energyIn, cx, cy, 104 * u, 0f, fDeg, 14 * u, dash)
                if (soc != null && fDeg > 0f) {
                    val lead = floor((fDeg - 0.01f) / 6f) * 6f
                    arcD(c.text, cx, cy, 104 * u, lead, min(360f, lead + 6f) - 0.01f, 14 * u, dash, alpha = breathe)
                }
                arcD(c.energyIn, cx, cy, 88 * u, 0f, (min(270.0, chargeKw) / 270.0 * 359.9).toFloat(), 3 * u, alpha = 0.75f)
                drawCircle(c.lineFaint, 80 * u, Offset(cx, cy), style = Stroke(1 * u))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        soc?.x?.roundToInt()?.toString() ?: NO_DATA, Modifier.alignByBaseline(),
                        style = Filament.num(84.sp, 500, c.text, if (c.glow > 0f) c.energyIn.copy(alpha = 0.45f) else null),
                    )
                    BasicText("%", Modifier.alignByBaseline(), style = Filament.text(30.sp, 400, c.textDim))
                }
                BasicText("CHARGING", style = Filament.label(13.sp, c.energyIn, 0.22f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("RATE", "+${chargeKw.roundToInt()}", "kW", c.energyIn, c, Modifier.weight(1f))
            Stat("RANGE", rangeMi?.x?.roundToInt()?.toString() ?: NO_DATA, "mi", c.text, c, Modifier.weight(1f))
            Stat("PACK", packC?.x?.roundToInt()?.toString() ?: NO_DATA, "°C", c.text, c, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, color: Color, c: DriveColors, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = Filament.label(13.sp, c.textFaint, 0.14f))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(value, Modifier.alignByBaseline(), style = Filament.num(28.sp, 600, color))
            BasicText(" $unit", Modifier.alignByBaseline(), style = Filament.text(16.sp, 400, c.textDim))
        }
    }
}

/** Gear chips P R N D; on the first shift into D the D chip pops in with an outline (sweep). */
@Composable
fun GearChips(gear: String?, c: DriveColors, sweepT: Float?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (g in listOf("P", "R", "N", "D")) {
            val on = g == gear
            val pop = if (g == "D" && sweepT != null) keys(sweepT, 0f to 1.35f, 0.1f to 1f) else 1f
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (g == "D" && sweepT != null) {
                    val op = keys(sweepT, 0f to 0f, 0.1f to 1f, 0.45f to 1f, 0.7f to 0f)
                    Canvas(Modifier.requiredSize(64.dp)) {
                        drawRect(c.accent.copy(alpha = 0.35f * c.glow * op), Offset(-3 * density, -3 * density), Size(size.width + 6 * density, size.height + 6 * density), style = Stroke(6 * density))
                        drawRect(c.accent.copy(alpha = op), style = Stroke(1.5f * density))
                    }
                }
                Box(
                    Modifier.size(52.dp).scale(pop).background(if (on) c.accentSoft else Color.Transparent, Filament.CutS),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        g,
                        style = Filament.text(30.sp, 600, if (on) c.accent else c.textFaint).copy(
                            fontFamily = Filament.SairaWide,
                            shadow = if (on && c.glow > 0f) androidx.compose.ui.graphics.Shadow(c.accent.copy(alpha = 0.5f), blurRadius = 14f) else null,
                        ),
                    )
                }
            }
        }
    }
}

/** Small diamond status light (rotated square), as in the Filament dock and panel header. */
@Composable
fun Diamond(color: Color, size: Dp, glow: Boolean) {
    Canvas(Modifier.size(size)) {
        rotate(45f) {
            val s = this.size.width / 1.41f
            val o = Offset((this.size.width - s) / 2, (this.size.height - s) / 2)
            if (glow) drawRect(color.copy(alpha = 0.35f), o - Offset(2 * density, 2 * density), Size(s + 4 * density, s + 4 * density))
            drawRect(color, o, Size(s, s))
        }
    }
}
