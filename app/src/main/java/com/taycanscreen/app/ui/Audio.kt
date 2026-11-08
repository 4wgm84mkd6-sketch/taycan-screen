package com.taycanscreen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.audio.EqEngine
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmtDb(v: Float): String = (if (v > 0) "+" else if (v < 0) "−" else "") + "%.1f".format(abs(v))

/** Design's Audio screen: speed-adaptive switch, presets, 10-band EQ with curve, automatic preamp. */
@Composable
fun AudioPanel(c: DriveColors, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val settings by EqEngine.settings.collectAsState()
    val status by EqEngine.status.collectAsState()
    val boost by EqEngine.speedBoost.collectAsState()

    Column(modifier.background(c.background).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Audio", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    status.attachedTo?.let { "Equalizer on $it" } ?: status.detail,
                    color = if (status.attachedTo != null) c.textDim else c.warn, fontSize = 14.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Speed-adaptive EQ", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (!settings.adaptive) "Off" else if (boost > 0.05f) "Bass +${"%.1f".format(boost)} dB at this speed" else "Adds bass above 45 mph",
                    color = c.textDim, fontSize = 13.sp,
                )
            }
            Box(Modifier.width(12.dp))
            Switch(settings.adaptive, c) { EqEngine.setAdaptive(!settings.adaptive) }
            Box(Modifier.width(12.dp))
            Text(
                "Done", color = c.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(10.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqEngine.PRESETS.forEach { p ->
                val on = settings.preset == p.id
                Text(
                    p.label,
                    color = if (on) c.panel else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp))
                        .background(if (on) c.text else c.panel)
                        .border(1.dp, if (on) c.text else c.border, RoundedCornerShape(22.dp))
                        .clickable { EqEngine.pickPreset(p.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Box(Modifier.weight(1f))
            Text(
                "Reset to flat", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { EqEngine.pickPreset("flat") }.padding(10.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(c.panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EqBands(settings.bands, EqEngine.effectiveBands(), c, Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth()) {
                Text("Preamp ${fmtDb(EqEngine.preampDb())} dB · automatic, prevents clipping", color = c.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Set the car's own bass and treble to 0", color = c.textDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EqBands(bands: List<Float>, effective: List<Float>, c: DriveColors, modifier: Modifier) {
    val labelH = 22.dp
    BoxWithConstraints(modifier) {
        val trackH = maxHeight - labelH * 2
        // Curve behind the sliders (design: smooth line + light fill; dashed line = with speed boost).
        Canvas(Modifier.fillMaxWidth().height(trackH).offset(y = labelH)) {
            fun y(v: Float) = size.height / 2 - v / 12f * (size.height / 2)
            fun pathOf(vals: List<Float>, close: Boolean): Path {
                val pts = listOf(Offset(0f, y(vals.first()))) +
                    vals.mapIndexed { i, v -> Offset((i + 0.5f) / vals.size * size.width, y(v)) } +
                    listOf(Offset(size.width, y(vals.last())))
                return Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[maxOf(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(pts.size - 1, i + 2)]
                        cubicTo(
                            p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
                            p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
                            p2.x, p2.y,
                        )
                    }
                    if (close) { lineTo(size.width, size.height / 2); lineTo(0f, size.height / 2); close() }
                }
            }
            drawPath(pathOf(bands, true), c.accent.copy(alpha = 0.10f))
            if (effective != bands) {
                drawPath(pathOf(effective, false), c.accent, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))))
            }
            drawPath(pathOf(bands, false), c.accent, style = Stroke(2.5.dp.toPx()))
        }
        Row(Modifier.fillMaxSize()) {
            bands.forEachIndexed { i, v ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtDb(v), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.height(labelH))
                    BandSlider(v, c, Modifier.width(48.dp).height(trackH)) { EqEngine.setBand(i, it) }
                    Text(EqEngine.FREQS[i], color = c.textDim, fontSize = 13.sp, modifier = Modifier.height(labelH))
                }
            }
        }
    }
}

/** Vertical −12…+12 dB slider: drag or tap anywhere on it; 0.5 dB steps. */
@Composable
private fun BandSlider(value: Float, c: DriveColors, modifier: Modifier, onChange: (Float) -> Unit) {
    fun toDb(y: Float, h: Float) = (((0.5f - y / h) * 48f).roundToInt() / 2f).coerceIn(-12f, 12f)
    BoxWithConstraints(
        modifier
            .pointerInput(Unit) { detectTapGestures { onChange(toDb(it.y, size.height.toFloat())) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ -> onChange(toDb(change.position.y, size.height.toFloat())) }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val h = maxHeight
        val pos = 0.5f - value / 24f // 0 = top (+12), 1 = bottom (−12)
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.track))
        Box(Modifier.width(24.dp).height(2.dp).offset(y = h / 2 - 1.dp).background(c.border))
        val top = h * minOf(pos, 0.5f)
        val len = h * abs(pos - 0.5f)
        Box(Modifier.width(4.dp).height(len).offset(y = top).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Box(
            Modifier.size(28.dp).offset(y = h * pos - 14.dp).clip(CircleShape).background(c.panel)
                .border(2.dp, c.accent, CircleShape),
        )
    }
}

@Composable
private fun Switch(on: Boolean, c: DriveColors, onToggle: () -> Unit) {
    Box(
        Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) c.accent else c.track).clickable(onClick = onToggle),
    ) {
        Box(
            Modifier.offset(x = if (on) 27.dp else 3.dp, y = 3.dp).size(26.dp).clip(CircleShape).background(c.panel),
        )
    }
}
