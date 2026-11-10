package com.taycanscreen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.car.TripComputer
import com.taycanscreen.app.obd.Cells
import com.taycanscreen.app.obd.ObdEngine
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Signal
import com.taycanscreen.app.obd.Taycan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

val LEDGER_TABS = listOf("TRIP", "BATTERY", "TIRES", "POWER", "12 V")

/** Fresh value or null (stale values are never shown). */
fun Map<String, Reading>.fresh(sig: Signal, now: Long): Double? =
    this[sig.id]?.takeIf { now - it.at <= sig.staleMs }?.value

private fun Map<String, Reading>.freshId(id: String, staleMs: Long, now: Long): Double? =
    this[id]?.takeIf { now - it.at <= staleMs }?.value

private fun cToF(c: Double) = c * 9 / 5 + 32
private fun barToPsi(b: Double) = b * 14.5038

/** Tire status from pressure and the car's own set point: 0 ok, 1 low (≥ 0.2 bar under), 2 very low (≥ 0.4). */
fun tireStatus(bar: Double?, setBar: Double?): Int {
    if (bar == null || setBar == null || setBar <= 0) return 0
    return when {
        bar < setBar - 0.4 -> 2
        bar < setBar - 0.2 -> 1
        else -> 0
    }
}

/** Worst tire status right now (for the warning chip on the Drive panel). */
fun worstTire(r: Map<String, Reading>, now: Long): Int = Taycan.TIRES.maxOf { t ->
    val corner = t.id.removePrefix("TAYCAN_TP_")
    tireStatus(r.fresh(t, now), r.freshId("TAYCAN_TP_SET_$corner", t.staleMs, now))
}

/**
 * The energy ledger (v3 board "Energy ledger"): a drawer over the map with the trip computer, battery
 * health and charging, tires, motors, and the 12 V battery.
 */
@Composable
fun LedgerPanel(c: DriveColors, tab: String, now: Long, onTab: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val r by ObdEngine.readings.collectAsState()
    val low by ObdEngine.lowVolts.collectAsState()
    // Ask the car for the extra data only while its page is open.
    LaunchedEffect(tab) {
        ObdEngine.setGroup("power", tab == "POWER")
        ObdEngine.setGroup("cells", tab == "BATTERY")
    }
    DisposableEffect(Unit) {
        onDispose {
            ObdEngine.setGroup("power", false)
            ObdEngine.setGroup("cells", false)
        }
    }
    Column(
        // Wide enough to cover the search row behind it (the Work button used to peek out).
        modifier.width(628.dp).fillMaxHeight().background(c.raised)
            .drawBehind { drawLine(c.border, Offset(size.width - density / 2, 0f), Offset(size.width - density / 2, size.height), density) }
            .padding(start = 28.dp, end = 18.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in LEDGER_TABS) {
                val on = t == tab
                Box(
                    Modifier.height(64.dp).clickable { onTab(t) }.padding(horizontal = 10.dp)
                        .drawBehind { if (on) drawRect(c.accent, Offset(0f, size.height - 3 * density), androidx.compose.ui.geometry.Size(size.width, 3 * density)) },
                    contentAlignment = Alignment.Center,
                ) { BasicText(t, style = Filament.label(14.sp, if (on) c.text else c.textDim, 0.04f, 500)) }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(56.dp).border(1.dp, c.lineStrong).clickable(onClick = onClose).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("DONE", style = Filament.label(14.sp, c.text, 0.06f, 500)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                "TRIP" -> TripTab(c, r, now)
                "BATTERY" -> BatteryTab(c, r, now)
                "TIRES" -> TiresTab(c, r, now)
                "POWER" -> PowerTab(c, r, now)
                else -> LowVoltageTab(c, r, low, now)
            }
        }
    }
}

@Composable
private fun Caption(text: String, c: DriveColors, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style = Filament.label(13.sp, c.textDim, 0.08f))

@Composable
private fun LineRow(label: String, value: String, c: DriveColors, valueColor: Color = c.text, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.border, Offset(0f, size.height - density / 2), Offset(size.width, size.height - density / 2), density) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Filament.text(18.sp, if (bold) 600 else 400, if (bold) c.text else c.textDim))
        BasicText(value, style = Filament.num(18.sp, if (bold) 600 else 400, valueColor))
    }
}

@Composable
private fun Cell(label: String, value: String, c: DriveColors, modifier: Modifier, color: Color = c.text) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(label, c)
        BasicText(value, style = Filament.num(20.sp, 400, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Grid(c: DriveColors, vararg cells: Triple<String, String, Color?>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.toList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (l, v, col) -> Cell(l, v, c, Modifier.weight(1f), col ?: c.text) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun liveKw(r: Map<String, Reading>, now: Long): Double? {
    val v = r.fresh(Taycan.HV_VOLTAGE_1818, now)?.takeIf { it > 0 } ?: r.fresh(Taycan.HV_VOLTAGE, now)?.takeIf { it > 0 }
    val a = r.fresh(Taycan.HV_CURRENT, now)
    return if (v != null && a != null) v * a / 1000.0 else null
}

@Composable
private fun TripTab(c: DriveColors, r: Map<String, Reading>, now: Long) {
    val trip by TripComputer.trip.collectAsState()
    val history by TripComputer.history.collectAsState()
    val t = trip
    val clock = DateTimeFormatter.ofPattern("h:mm")
    Caption(
        if (t == null) "ENERGY · NO TRIP YET"
        else "ENERGY · THIS TRIP · SINCE ${Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(clock)} · ${t.minutes} MIN",
        c,
    )
    Column {
        LineRow("Used", t?.let { "−%.2f kWh".format(it.usedKWh) } ?: NO_DATA, c)
        LineRow("Recovered", t?.let { "+%.2f kWh".format(it.recoveredKWh) } ?: NO_DATA, c, c.accent)
        LineRow("Net", t?.let { "%s%.2f kWh".format(if (it.netKWh >= 0) "−" else "+", abs(it.netKWh)) } ?: NO_DATA, c, bold = true)
    }
    val kw = liveKw(r, now)
    val carUse = r.fresh(Taycan.TRIP_USE_SHORT, now)?.takeIf { it > 1 }
    val tMin = r.fresh(Taycan.PACK_T_MIN, now)
    val tMax = r.fresh(Taycan.PACK_T_MAX, now)
    val volts = r.fresh(Taycan.HV_VOLTAGE_1818, now)?.takeIf { it > 0 } ?: r.fresh(Taycan.HV_VOLTAGE, now)
    val amps = r.fresh(Taycan.HV_CURRENT, now)
    Grid(
        c,
        Triple("DISTANCE", t?.let { "%.1f mi".format(it.distanceMi) } ?: NO_DATA, null),
        Triple("EFFICIENCY", t?.miPerKWh?.let { "%.1f mi/kWh".format(it) } ?: NO_DATA, null),
        Triple("LIVE POWER", kw?.let { if (it < 0) "${(-it).roundToInt()} kW" else "−${it.roundToInt()} kW" } ?: NO_DATA, if ((kw ?: 0.0) > 0.5) c.accent else null),
        Triple("CAR'S TRIP AVERAGE", carUse?.let { "%.1f mi/kWh".format(100.0 / it) } ?: NO_DATA, null),
        Triple("PACK TEMP", if (tMin != null && tMax != null) "%.0f–%.0f°F".format(cToF(tMin), cToF(tMax)) else NO_DATA, null),
        Triple("HIGH VOLTAGE", if (volts != null && amps != null) "%.0f V · %.1f A".format(volts, amps) else NO_DATA, null),
    )
    if (history.isNotEmpty()) {
        Caption("RECENT DRIVES", c, Modifier.padding(top = 6.dp))
        val day = DateTimeFormatter.ofPattern("EEE h:mm")
        Column {
            history.take(6).forEach { h ->
                LineRow(
                    Instant.ofEpochMilli(h.startedAt).atZone(ZoneId.systemDefault()).format(day),
                    "%.1f mi · %s · %.1f kWh".format(h.distanceMi, h.miPerKWh?.let { "%.1f mi/kWh".format(it) } ?: NO_DATA, h.netKWh),
                    c,
                )
            }
        }
    }
}

@Composable
private fun BatteryTab(c: DriveColors, r: Map<String, Reading>, now: Long) {
    val cells by Cells.summary.collectAsState()
    val charge by TripComputer.charge.collectAsState()
    val soc = r.fresh(Taycan.SOC_DISPLAY, now)
    val soh = r.fresh(Taycan.HV_SOH, now)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Caption("CHARGE", c)
            BasicText(soc?.let { "%.0f%%".format(it) } ?: NO_DATA, style = Filament.num(48.sp, 300, soc?.let { chargeColor(it, c) } ?: c.text))
        }
        Column {
            Caption("HEALTH (CAR'S ESTIMATE)", c)
            BasicText(soh?.let { "%.1f%%".format(it) } ?: NO_DATA, style = Filament.num(48.sp, 300, c.text))
        }
    }
    Caption("CELLS · ${cells.modulesSeen}/33 MODULES READ", c)
    Grid(
        c,
        Triple("LOWEST CELL", cells.vMin?.let { "%.3f V".format(it) + (cells.vMinCell?.let { " · $it" } ?: "") } ?: NO_DATA, null),
        Triple("HIGHEST CELL", cells.vMax?.let { "%.3f V".format(it) + (cells.vMaxCell?.let { " · $it" } ?: "") } ?: NO_DATA, null),
        Triple("SPREAD", cells.spreadMv?.let { "%.0f mV".format(it) } ?: NO_DATA, cells.spreadMv?.let { if (it > 60) c.warn else null }),
        Triple("CELL CHARGE", if (cells.socMin != null && cells.socMax != null) "%.0f–%.0f%%".format(cells.socMin, cells.socMax) else NO_DATA, null),
    )
    val tMin = r.fresh(Taycan.PACK_T_MIN, now)
    val tMax = r.fresh(Taycan.PACK_T_MAX, now)
    Grid(c, Triple("PACK TEMP", if (tMin != null && tMax != null) "%.0f–%.0f°F".format(cToF(tMin), cToF(tMax)) else NO_DATA, null))
    val ch = charge
    if (ch != null) {
        Caption(if (ch.active) "CHARGING NOW" else "LAST CHARGE", c, Modifier.padding(top = 6.dp))
        Grid(
            c,
            Triple("RATE", if (ch.active) "%.0f kW".format(ch.kwNow) else NO_DATA, c.accent),
            Triple("ADDED", "%.1f kWh".format(ch.energyKWh), null),
            Triple("CHARGE", "${ch.socStart?.roundToInt() ?: NO_DATA} → ${ch.socNow?.roundToInt() ?: NO_DATA}%", null),
            Triple("PER 1%", ch.kWhPerPct?.let { "%.2f kWh".format(it) } ?: "learning…", null),
            Triple("TO 80%", ch.minutesTo(80.0)?.let { "≈ ${it.roundToInt()} min" } ?: NO_DATA, null),
            Triple("TO 100%", ch.minutesTo(100.0)?.let { "≈ ${it.roundToInt()} min" } ?: NO_DATA, null),
        )
        if (ch.samples.size >= 2) ChargeGraph(ch.samples, c)
    }
}

/** Charging power over the session. */
@Composable
private fun ChargeGraph(samples: List<Pair<Long, Float>>, c: DriveColors) {
    val peak = max(10f, samples.maxOf { it.second })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption("CHARGING POWER · PEAK %.0f kW".format(peak), c)
        Canvas(Modifier.fillMaxWidth().height(140.dp).border(1.dp, c.border)) {
            val t0 = samples.first().first
            val span = max(60_000L, samples.last().first - t0).toFloat()
            val p = Path()
            samples.forEachIndexed { i, (t, kw) ->
                val x = (t - t0) / span * size.width
                val y = size.height - kw.coerceAtLeast(0f) / peak * (size.height - 8 * density)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, c.accent, style = Stroke(2 * density))
        }
    }
}

@Composable
private fun TiresTab(c: DriveColors, r: Map<String, Reading>, now: Long) {
    Caption("TIRES · PRESSURE (TARGET) · TEMPERATURE", c)
    @Composable
    fun tile(corner: String, modifier: Modifier) {
        val sig = Taycan.TIRES.first { it.id.endsWith("_$corner") }
        val p = r.fresh(sig, now)
        val set = r.freshId("TAYCAN_TP_SET_$corner", sig.staleMs, now)
        val t = r.freshId("TAYCAN_TT_$corner", sig.staleMs, now)
        val st = tireStatus(p, set)
        val col = when (st) { 2 -> c.danger; 1 -> c.warn; else -> c.text }
        Column(
            modifier.border(1.dp, if (st > 0) col else c.border).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Caption(when (corner) { "FL" -> "FRONT LEFT"; "FR" -> "FRONT RIGHT"; "RL" -> "REAR LEFT"; else -> "REAR RIGHT" }, c)
            BasicText(p?.let { "%.0f psi".format(barToPsi(it)) } ?: NO_DATA, style = Filament.num(30.sp, 400, col))
            BasicText(
                "target ${set?.let { "%.0f".format(barToPsi(it)) } ?: NO_DATA} · ${t?.let { "%.0f°F".format(cToF(it)) } ?: NO_DATA}",
                style = Filament.label(14.sp, c.textDim, 0.02f),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        tile("FL", Modifier.weight(1f))
        tile("FR", Modifier.weight(1f))
    }
    // The car between the axles.
    Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(140.dp, 70.dp).border(1.dp, c.border), contentAlignment = Alignment.Center) {
            Caption("FRONT ↑", c)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        tile("RL", Modifier.weight(1f))
        tile("RR", Modifier.weight(1f))
    }
    BasicText(
        "Amber: 3 psi or more under the car's target. Red: 6 psi or more under. Values refresh about every 20 s.",
        style = Filament.text(14.sp, 400, c.textDim),
    )
}

@Composable
private fun PowerTab(c: DriveColors, r: Map<String, Reading>, now: Long) {
    val kw = liveKw(r, now)
    val t1 = r.fresh(Taycan.EM_TORQ_1, now)
    val t2 = r.fresh(Taycan.EM_TORQ_2, now)
    val rpm = r.fresh(Taycan.EM_RPM_1, now)
    Column {
        Caption("TOTAL POWER (BATTERY)", c)
        BasicText(
            kw?.let { if (it <= 0) "${(-it).roundToInt()} kW" else "+${it.roundToInt()} kW regen" } ?: NO_DATA,
            style = Filament.num(48.sp, 300, if ((kw ?: 0.0) > 0.5) c.accent else c.text),
        )
    }
    TorqueBar("MOTOR 1", t1, c)
    TorqueBar("MOTOR 2", t2, c)
    val pos1 = (t1 ?: 0.0).coerceAtLeast(0.0)
    val pos2 = (t2 ?: 0.0).coerceAtLeast(0.0)
    Grid(
        c,
        Triple("TORQUE SPLIT 1 : 2", if (t1 != null && t2 != null && pos1 + pos2 > 5) "%.0f : %.0f".format(pos1 / (pos1 + pos2) * 100, pos2 / (pos1 + pos2) * 100) else NO_DATA, null),
        Triple("MOTOR 1 SPEED", rpm?.let { "%,.0f rpm".format(it) } ?: NO_DATA, null),
    )
    BasicText(
        "Torque comes from the car's two motors (which one is front is still to confirm from a drive). The car doesn't report motor 2's speed, so power per motor can't be shown.",
        style = Filament.text(14.sp, 400, c.textDim),
    )
}

@Composable
private fun TorqueBar(label: String, nm: Double?, c: DriveColors) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Caption(label, c)
            Spacer(Modifier.weight(1f))
            BasicText(nm?.let { "%.0f Nm".format(it) } ?: NO_DATA, style = Filament.num(18.sp, 400, if ((nm ?: 0.0) < 0) c.accent else c.text))
        }
        // Centre zero: drive to the right, regen to the left (±600 Nm).
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val mid = size.width / 2
            drawRect(c.border, Offset(0f, size.height / 2 - density), androidx.compose.ui.geometry.Size(size.width, 2 * density))
            drawRect(c.textFaint, Offset(mid - density / 2, 0f), androidx.compose.ui.geometry.Size(density, size.height))
            if (nm != null) {
                val w = (abs(nm) / 600.0).coerceIn(0.0, 1.0).toFloat() * mid
                val x = if (nm >= 0) mid else mid - w
                drawRect(if (nm >= 0) c.text else c.accent, Offset(x, size.height / 2 - 2 * density), androidx.compose.ui.geometry.Size(w, 4 * density))
            }
        }
    }
}

@Composable
private fun LowVoltageTab(c: DriveColors, r: Map<String, Reading>, low: Reading?, now: Long) {
    val soc = r.fresh(Taycan.LV_SOC, now)
    val v = r.fresh(Taycan.LV_VOLTAGE, now)
    val adapter = low?.takeIf { now - it.at < 180_000 }?.value
    Column {
        Caption("12 V BATTERY · CHARGE", c)
        BasicText(soc?.let { "%.0f%%".format(it) } ?: NO_DATA, style = Filament.num(48.sp, 300, soc?.let { lowVoltageColor(it, c) } ?: c.text))
    }
    Grid(
        c,
        Triple("VOLTAGE (CAR)", v?.let { "%.2f V".format(it) } ?: NO_DATA, null),
        Triple("VOLTAGE (ADAPTER)", adapter?.let { "%.1f V".format(it) } ?: NO_DATA, null),
        Triple("CHARGE", r.fresh(Taycan.LV_CHARGE, now)?.let { "%.0f Ah".format(it) } ?: NO_DATA, null),
        Triple("CAPACITY", r.fresh(Taycan.LV_CAPACITY, now)?.let { "%.0f Ah".format(it) } ?: NO_DATA, null),
        Triple("RESISTANCE", r.fresh(Taycan.LV_RESISTANCE, now)?.let { "%.1f mΩ".format(it) } ?: NO_DATA, null),
    )
    BasicText(
        "Capacity falling or resistance rising over the months means the battery is ageing. A health % needs the rated Ah from the battery's label.",
        style = Filament.text(14.sp, 400, c.textDim),
    )
}
