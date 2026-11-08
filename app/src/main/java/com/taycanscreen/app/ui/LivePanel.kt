package com.taycanscreen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdViewModel
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Taycan
import kotlinx.coroutines.delay

private const val DASH = "—"

/** Live OBD values: big numbers for glancing, raw replies underneath for checking against Car Scanner. */
@Composable
fun LivePanel(obd: ObdViewModel, onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    val status by obd.status.collectAsState()
    val readings by obd.readings.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    fun fresh(id: String): Reading? {
        val stale = Taycan.POLLED.firstOrNull { it.id == id }?.staleMs ?: 5_000L
        return readings[id]?.takeIf { now - it.at <= stale }
    }
    fun value(id: String): Double? = fresh(id)?.value

    val soc = value(Taycan.SOC_DISPLAY.id)
    val speedKmh = value(Taycan.SPEED.id)
    val v1801 = value(Taycan.HV_VOLTAGE.id)
    val v1818 = value(Taycan.HV_VOLTAGE_1818.id)
    val volts = v1801?.takeIf { it > 0 } ?: v1818?.takeIf { it > 0 }
    val amps = value(Taycan.HV_CURRENT.id)
    val kw = if (volts != null && amps != null) volts * amps / 1000.0 else null
    val tMax = value(Taycan.PACK_T_MAX.id)
    val tMin = value(Taycan.PACK_T_MIN.id)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val color = when (status.state) {
                LinkState.Live -> Palette.Accent
                LinkState.Retrying, LinkState.BluetoothOff, LinkState.NeedsPermission, LinkState.ChooseAdapter -> Palette.Warn
                else -> Palette.TextDim
            }
            Text(
                "OBD: ${status.adapterName ?: "no adapter"} · ${status.detail}",
                color = color, fontSize = 18.sp, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            when (status.state) {
                LinkState.NeedsPermission -> SmallButton("Allow Bluetooth", onRequestPermission)
                LinkState.Stopped -> SmallButton("Connect") { obd.start() }
                else -> SmallButton("Stop") { obd.stop() }
            }
        }
        if (status.state == LinkState.ChooseAdapter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.pairedDevices.take(4).forEach { (name, address) ->
                    SmallButton(name) { obd.chooseAdapter(address) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Battery", soc?.let { "%.1f".format(it) }, "%", fresh(Taycan.SOC_DISPLAY.id), Modifier.weight(1f))
            Tile("Speed", speedKmh?.let { "%.0f".format(it * 0.621371) }, "mph", fresh(Taycan.SPEED.id), Modifier.weight(1f),
                note = speedKmh?.let { "%.0f km/h".format(it) })
            Tile("Power", kw?.let { "%.1f".format(it) }, "kW", null, Modifier.weight(1f),
                note = if (kw != null) "= V × A" else "needs V and A")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("HV voltage", volts?.let { "%.1f".format(it) }, "V", fresh(Taycan.HV_VOLTAGE.id), Modifier.weight(1f),
                note = "1801: ${v1801?.let { "%.1f".format(it) } ?: DASH} · 1818: ${v1818?.let { "%.1f".format(it) } ?: DASH}",
                raw2 = fresh(Taycan.HV_VOLTAGE_1818.id))
            Tile("HV current", amps?.let { "%.1f".format(it) }, "A", fresh(Taycan.HV_CURRENT.id), Modifier.weight(1f))
            Tile(
                "Pack temp min / max",
                if (tMin != null || tMax != null) "${tMin?.let { "%.0f".format(it * 9 / 5 + 32) } ?: DASH} / ${tMax?.let { "%.0f".format(it * 9 / 5 + 32) } ?: DASH}" else null,
                "°F", fresh(Taycan.PACK_T_MIN.id), Modifier.weight(1f),
                note = "${tMin?.let { "%.0f".format(it) } ?: DASH} / ${tMax?.let { "%.0f".format(it) } ?: DASH} °C",
                raw2 = fresh(Taycan.PACK_T_MAX.id),
            )
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: String?,
    unit: String,
    raw: Reading?,
    modifier: Modifier,
    note: String? = null,
    raw2: Reading? = null,
) {
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = Palette.TextDim, fontSize = 16.sp)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(value ?: DASH, color = Palette.Text, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", color = Palette.TextDim, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (note != null) Text(note, color = Palette.TextDim, fontSize = 13.sp)
        listOfNotNull(raw, raw2).forEach {
            Text(it.raw, color = Color(0xFF7CC4FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}
