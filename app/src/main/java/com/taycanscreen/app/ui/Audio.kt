package com.taycanscreen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.audio.EqEngine
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmtDb(v: Float): String = (if (v > 0) "+" else if (v < 0) "−" else "") + "%.1f".format(abs(v))

/**
 * Sound: presets, the 10-band equalizer with its curve, and the speed-adaptive bass. Drawn in design
 * px in the same look as the Drive screen (square, Inter Tight / Plex Mono, mint accent). Everything
 * works at any speed (owner, 2026-11-10).
 */
@Composable
fun AudioPanel(c: DriveColors, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val settings by EqEngine.settings.collectAsState()
    val status by EqEngine.status.collectAsState()
    val boost by EqEngine.speedBoost.collectAsState()
    val user by EqEngine.userPresets.collectAsState()
    var saving by remember { mutableStateOf(false) }
    // "Reset" can be taken back for a few seconds (it used to wipe the curve with one tap).
    var undo by remember { mutableStateOf<Pair<List<Float>, String>?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            kotlinx.coroutines.delay(8_000)
            undo = null
        }
    }

    Column(modifier.background(c.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText("Sound", style = Filament.text(26.sp, 600, c.text))
                BasicText(
                    when (status.attachedTo) {
                        null -> status.detail
                        "All audio" -> "Equalizer on · all sound from the box, not Spotify itself"
                        else -> "Equalizer on · ${status.attachedTo}"
                    },
                    style = Filament.text(16.sp, 400, c.textDim), maxLines = 1,
                )
            }
            // Music is playing and the EQ isn't on it: one tap pauses and resumes for a moment, which
            // makes Spotify hand its audio over (it also happens by itself a few seconds after starting).
            val playing = com.taycanscreen.app.music.SpotifyController.nowPlaying.collectAsState().value?.paused == false
            if (playing && status.attachedTo != "Spotify") {
                Button("CONNECT TO SPOTIFY", c, accent = true) { com.taycanscreen.app.music.SpotifyController.ensureEq(byOwner = true) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("SPEED-ADAPTIVE BASS", style = Filament.label(13.sp, c.text, 0.06f, 500))
                BasicText(
                    if (!settings.adaptive) "Off" else if (boost > 0.05f) "+${"%.1f".format(boost)} dB at this speed" else "Adds bass above 45 mph",
                    style = Filament.text(16.sp, 400, c.textDim),
                )
            }
            Switch(settings.adaptive, c) { EqEngine.setAdaptive(!settings.adaptive) }
            Button("DONE", c, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saving) {
                    user.forEachIndexed { i, p ->
                        Chip("Save as ${p.label}" + if (p.bands == null) "" else " (replace)", false, c) {
                            EqEngine.saveUser(i + 1)
                            saving = false
                        }
                    }
                } else {
                    (EqEngine.PRESETS + user.filter { it.bands != null }).forEach { p ->
                        Chip(p.label, settings.preset == p.id, c) { EqEngine.pickPreset(p.id) }
                    }
                    if (settings.preset == "custom") Chip("Custom", true, c) {}
                }
            }
            Button(if (saving) "CANCEL" else "SAVE", c) { saving = !saving }
            val u = undo
            if (u != null) {
                Button("UNDO", c, accent = true) {
                    u.first.forEachIndexed { i, v -> EqEngine.setBand(i, v) }
                    if (u.second != "custom") EqEngine.pickPreset(u.second)
                    undo = null
                }
            } else {
                Button("RESET", c) {
                    if (settings.bands.any { it != 0f }) undo = settings.bands to settings.preset
                    EqEngine.pickPreset("flat")
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).card(c.raised, c.border).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EqBands(settings.bands, EqEngine.effectiveBands(), c, Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                val trim = -EqEngine.preampDb()
                BasicText(
                    if (trim > 0.05f) "Volume trimmed ${"%.1f".format(trim)} dB to keep it clean" else "No volume trim needed",
                    Modifier.weight(1f), style = Filament.text(15.sp, 400, c.textDim),
                )
                BasicText("Set the car's own bass and treble to 0", style = Filament.text(15.sp, 400, c.textDim))
            }
        }
    }
}

@Composable
private fun EqBands(bands: List<Float>, effective: List<Float>, c: DriveColors, modifier: Modifier) {
    val labelH = 30.dp
    BoxWithConstraints(modifier) {
        val trackH = maxHeight - labelH * 2
        // Curve behind the sliders (smooth line + light fill; dashed line = with the speed boost).
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
            drawLine(c.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), density)
            drawPath(pathOf(bands, true), c.accent.copy(alpha = 0.10f))
            if (effective != bands) {
                drawPath(pathOf(effective, false), c.accent, style = Stroke(2 * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10 * density, 10 * density))))
            }
            drawPath(pathOf(bands, false), c.accent, style = Stroke(2.5f * density))
        }
        Row(Modifier.fillMaxSize()) {
            bands.forEachIndexed { i, v ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(labelH), contentAlignment = Alignment.Center) {
                        BasicText(fmtDb(v), style = Filament.num(16.sp, 500, if (v == 0f) c.textDim else c.text))
                    }
                    // The whole column is the slider (it was a 48-wide strip).
                    BandSlider(v, c, Modifier.fillMaxWidth().height(trackH)) { EqEngine.setBand(i, it) }
                    Box(Modifier.height(labelH), contentAlignment = Alignment.Center) {
                        BasicText(EqEngine.FREQS[i], style = Filament.label(14.sp, c.textDim, 0f))
                    }
                }
            }
        }
    }
}

/** Vertical −12…+12 dB slider: drag or tap anywhere in its column; 0.5 dB steps. */
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
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.lineStrong))
        val top = h * minOf(pos, 0.5f)
        val len = h * abs(pos - 0.5f)
        Box(Modifier.width(4.dp).height(len).offset(y = top).background(c.accent))
        // Fader cap: a wide bar is easier to see and grab than a small ring.
        Box(
            Modifier.offset(y = h * pos - 9.dp).size(56.dp, 18.dp).background(c.text),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(56.dp, 2.dp).background(c.panel)) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, c: DriveColors, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().card(if (on) c.accentSoft else Color.Transparent, if (on) c.accentLine else c.border)
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.text(18.sp, if (on) 600 else 500, if (on) c.accent else c.text), maxLines = 1) }
}

@Composable
private fun Button(label: String, c: DriveColors, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(60.dp).card(if (accent) c.accentSoft else c.raised, if (accent) c.accentLine else c.lineStrong)
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.label(14.sp, if (accent) c.accent else c.text, 0.08f, 500)) }
}

@Composable
private fun Switch(on: Boolean, c: DriveColors, onToggle: () -> Unit) {
    Box(
        Modifier.size(88.dp, 48.dp).background(if (on) c.accent else c.track).clickable(onClick = onToggle),
    ) {
        Box(Modifier.offset(x = if (on) 46.dp else 6.dp, y = 6.dp).size(36.dp).background(if (on) c.onAccent else c.textDim))
    }
}
