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

/**
 * Trims a text's box to its digits: from cap height above the baseline to [pad] below it (big numbers
 * in Inter Tight carry a lot of empty line space).
 */
fun Modifier.capBox(fontSize: Dp, pad: Dp = 0.dp, cap: Float = 0.74f) = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val b = p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
    val top = (b - fontSize.toPx() * cap - pad.toPx()).roundToInt().coerceAtLeast(0)
    val h = b + pad.roundToPx() - top
    layout(p.width, h) { p.place(0, -top) }
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

