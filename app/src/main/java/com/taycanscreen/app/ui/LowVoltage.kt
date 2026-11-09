package com.taycanscreen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.roundToInt

/** The 12 V battery's numbers, all from OBDb gateway signals (710) plus the adapter's own ATRV. */
data class LowVoltageValues(
    val soc: V?,
    val volts: V?,
    val adapterVolts: V?,
    val chargeAh: V?,
    val capacityAh: V?,
    val resistanceMilliOhm: V?,
)

/** Compact readout for the battery block: "12V 92% · 13.3 V". */
fun lowVoltageSummary(lv: LowVoltageValues): String {
    val v = lv.volts ?: lv.adapterVolts
    val soc = lv.soc?.x?.roundToInt()?.let { "$it% · " } ?: ""
    return "12V $soc${v?.x?.let { "%.1f V".format(it) } ?: NO_DATA}"
}

/**
 * 12 V battery gauge over the map (design px): state-of-charge ring, voltage scale, and the health
 * readings the car reports (learned capacity and internal resistance).
 */
@Composable
fun LowVoltagePanel(lv: LowVoltageValues, c: DriveColors, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(c.panel).padding(start = 40.dp, end = 24.dp, top = 20.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Diamond(c.accent, 11.dp, glow = c.glow > 0f)
            BasicText("12 V BATTERY", Modifier.padding(start = 12.dp), style = Filament.label(16.sp, c.text, 0.2f, 500))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(72.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(FIcons.Close, "Close", tint = c.text, modifier = Modifier.size(28.dp))
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            SocRing(lv.soc, c)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                VoltageScale(lv, c)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText("HEALTH", style = Filament.label(13.sp, c.textFaint, 0.18f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HealthTile("CAPACITY", lv.capacityAh, "%.0f", "Ah", c, Modifier.weight(1f))
                        HealthTile("RESISTANCE", lv.resistanceMilliOhm, "%.1f", "mΩ", c, Modifier.weight(1f))
                        HealthTile("CHARGE", lv.chargeAh, "%.0f", "Ah", c, Modifier.weight(1f))
                    }
                    BasicText(
                        "Capacity falling or resistance rising over the months means the battery is ageing. " +
                            "A health % needs the rated Ah from the battery's label.",
                        style = Filament.text(15.sp, 400, c.textDim),
                    )
                }
            }
        }
    }
}

@Composable
private fun SocRing(soc: V?, c: DriveColors) {
    val pct = (soc?.x ?: 0.0).coerceIn(0.0, 100.0).toFloat()
    val col = if (soc != null) lowVoltageColor(soc.x, c) else c.accent
    Box(Modifier.size(300.dp).alpha(if (soc?.dim == true) 0.45f else 1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val cx = size.width / 2
            val cy = size.height / 2
            val segLen = (2 * PI * 120 / 60).toFloat()
            val dash = floatArrayOf((segLen - 3) * u, 3 * u)
            drawCircle(c.border, 140 * u, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(u))
            arcD(c.lineFaint, cx, cy, 120 * u, 0f, 359.99f, 18 * u, dash)
            if (soc != null) {
                arcD(col, cx, cy, 120 * u, 0f, pct * 3.6f, 34 * u, alpha = 0.16f * c.glow)
                arcD(col, cx, cy, 120 * u, 0f, pct * 3.6f, 18 * u, dash)
            }
            drawCircle(c.lineFaint, 96 * u, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(u))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(
                    soc?.x?.roundToInt()?.toString() ?: NO_DATA, Modifier.alignByBaseline(),
                    style = Filament.num(84.sp, 500, c.text, if (c.glow > 0f) col.copy(alpha = 0.45f) else null),
                )
                BasicText("%", Modifier.alignByBaseline().padding(start = 4.dp), style = Filament.text(30.sp, 400, c.textDim))
            }
            BasicText("STATE OF CHARGE", style = Filament.label(13.sp, col, 0.18f))
        }
    }
}

/** Voltage on an 11–15 V scale with a pointer; car sensor first, adapter reading alongside. */
@Composable
private fun VoltageScale(lv: LowVoltageValues, c: DriveColors) {
    val v = lv.volts ?: lv.adapterVolts
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("VOLTAGE", Modifier.alignByBaseline(), style = Filament.label(13.sp, c.textFaint, 0.18f))
            Spacer(Modifier.weight(1f))
            BasicText(
                v?.x?.let { "%.2f".format(it) } ?: NO_DATA,
                Modifier.alignByBaseline().alpha(if (v?.dim == true) 0.45f else 1f),
                style = Filament.num(44.sp, 600, c.text),
            )
            BasicText(" V", Modifier.alignByBaseline(), style = Filament.text(20.sp, 400, c.textDim))
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val u = density
            val w = size.width
            fun x(volts: Double) = ((volts - 11.0) / 4.0).coerceIn(0.0, 1.0).toFloat() * w
            drawRect(c.lineFaint, Offset(0f, 14 * u), androidx.compose.ui.geometry.Size(w, 8 * u))
            for (t in 0..40) {
                val major = t % 10 == 0
                val xx = t / 40f * w
                drawLine(if (major) c.lineStrong else c.border, Offset(xx, if (major) 8 * u else 12 * u), Offset(xx, 14 * u), 1.5f * u)
            }
            if (v != null) {
                drawRect(c.accent, Offset(0f, 14 * u), androidx.compose.ui.geometry.Size(x(v.x), 8 * u), alpha = if (v.dim) 0.45f else 1f)
                val px = x(v.x)
                drawPath(Path().apply { moveTo(px - 7 * u, 34 * u); lineTo(px + 7 * u, 34 * u); lineTo(px, 24 * u); close() }, c.text)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for ((i, t) in listOf("11", "12", "13", "14", "15").withIndex()) {
                BasicText(t, style = Filament.label(13.sp, c.textFaint, 0f))
                if (i < 4) Spacer(Modifier.weight(1f))
            }
        }
        BasicText(
            "CAR SENSOR ${lv.volts?.x?.let { "%.2f V".format(it) } ?: NO_DATA} · ADAPTER ${lv.adapterVolts?.x?.let { "%.1f V".format(it) } ?: NO_DATA}",
            style = Filament.label(13.sp, c.textDim, 0.08f),
        )
    }
}

@Composable
private fun HealthTile(label: String, value: V?, fmt: String, unit: String, c: DriveColors, modifier: Modifier) {
    Column(
        modifier.background(c.raised, Filament.CutS).insetLine(c.border).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(label, style = Filament.label(13.sp, c.textFaint, 0.14f))
        Row(Modifier.alpha(if (value?.dim == true) 0.45f else 1f), verticalAlignment = Alignment.Bottom) {
            BasicText(value?.x?.let { fmt.format(it) } ?: NO_DATA, Modifier.alignByBaseline(), style = Filament.num(32.sp, 600, c.text))
            BasicText(" $unit", Modifier.alignByBaseline(), style = Filament.text(16.sp, 400, c.textDim))
        }
    }
}
