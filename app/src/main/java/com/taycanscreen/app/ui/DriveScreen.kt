package com.taycanscreen.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.music.SpotifyController
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdViewModel
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Signal
import com.taycanscreen.app.obd.Taycan
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0

/**
 * Freshness rule: a value is shown normally while new, faded once it's over half its stale time old,
 * and replaced by "—" once stale (never a stale number).
 */
private fun Map<String, Reading>.v(sig: Signal, now: Long): V? {
    val r = this[sig.id] ?: return null
    val x = r.value ?: return null
    val age = now - r.at
    if (age > sig.staleMs) return null
    return V(x, age > sig.staleMs / 2)
}

/** The numbers the Drive screen shows, already filtered for freshness. */
private data class VehicleValues(
    val soc: V?,
    val speedMph: V?,
    val rangeMi: V?,
    val outsideF: V?,
    val volts: V?,
    val kw: V?,
    val packMinC: V?,
    val packMaxC: V?,
    val gear: String?,
    val lowVolts: V?,
    val lowVoltage: LowVoltageValues,
) {
    companion object {
        fun from(r: Map<String, Reading>, low: Reading?, now: Long): VehicleValues {
            val volts = r.v(Taycan.HV_VOLTAGE, now)?.takeIf { it.x > 0 }
                ?: r.v(Taycan.HV_VOLTAGE_1818, now)?.takeIf { it.x > 0 }
            val amps = r.v(Taycan.HV_CURRENT, now)
            val lowAge = low?.let { now - it.at }
            val adapterVolts = low?.value?.takeIf { lowAge!! < 180_000 }?.let { V(it, lowAge!! > 90_000) }
            return VehicleValues(
                soc = r.v(Taycan.SOC_DISPLAY, now),
                speedMph = (r.v(Taycan.SPEED_DIR, now) ?: r.v(Taycan.SPEED, now))?.let { V(kotlin.math.abs(it.x) * 0.621371, it.dim) },
                rangeMi = r.v(Taycan.RANGE, now),
                outsideF = r.v(Taycan.OUTSIDE, now)?.let { V(cToF(it.x), it.dim) },
                volts = volts,
                kw = if (volts != null && amps != null) V(volts.x * amps.x / 1000.0, volts.dim || amps.dim) else null,
                packMinC = r.v(Taycan.PACK_T_MIN, now),
                packMaxC = r.v(Taycan.PACK_T_MAX, now),
                gear = r.v(Taycan.GEAR, now)?.let { Taycan.gearLetter(it.x) },
                lowVolts = adapterVolts,
                lowVoltage = LowVoltageValues(
                    soc = r.v(Taycan.LV_SOC, now),
                    volts = r.v(Taycan.LV_VOLTAGE, now),
                    adapterVolts = adapterVolts,
                    chargeAh = r.v(Taycan.LV_CHARGE, now),
                    capacityAh = r.v(Taycan.LV_CAPACITY, now),
                    resistanceMilliOhm = r.v(Taycan.LV_RESISTANCE, now),
                ),
            )
        }
    }
}

/** Seconds since [at] (elapsedRealtime) while under [duration]; null before and after. Frame-driven. */
@Composable
fun rememberSince(at: Long, duration: Float): Float? {
    var v by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(at) {
        v = null
        if (at == 0L) return@LaunchedEffect
        while (true) {
            val s = (SystemClock.elapsedRealtime() - at) / 1000f
            if (s > duration) break
            v = if (s >= 0f) s else null
            withFrameNanos { }
        }
        v = null
    }
    return v
}

/**
 * Filament Drive screen: vehicle panel on the left, map on the right, dock along the bottom, plus the
 * start-up lock-on sequence, the first-shift-to-D gauge sweep and the charging view.
 *
 * Drawn in Filament design pixels: the density is set so the 1602×720 design fits the measured window
 * (1 dp here = 1 design px). The Music and Audio panels keep the box's real density.
 */
@Composable
fun DriveScreen(
    obd: ObdViewModel,
    colors: DriveColors,
    themeMode: ThemeMode,
    locationGranted: Boolean,
    onCycleTheme: () -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val real = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val k = min(wPx / 1602f, hPx / 720f).coerceAtLeast(0.3f)
        LaunchedEffect(wPx, hPx) {
            AppLog.info("screen", "Drive screen %d×%d px, %.3f px per design px".format(wPx, hPx, k))
        }
        CompositionLocalProvider(LocalDensity provides Density(k, 1f)) {
            DriveContent(obd, colors, themeMode, locationGranted, onCycleTheme, onOpenVehicle, onOpenSetup, real)
        }
    }
}

@Composable
private fun DriveContent(
    obd: ObdViewModel,
    c: DriveColors,
    themeMode: ThemeMode,
    locationGranted: Boolean,
    onCycleTheme: () -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenSetup: () -> Unit,
    realDensity: Density,
) {
    val status by obd.status.collectAsState()
    val readings by obd.readings.collectAsState()
    val lowVolts by com.taycanscreen.app.obd.ObdEngine.lowVolts.collectAsState()
    val spotify by SpotifyController.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val v = VehicleValues.from(readings, lowVolts, now)
    val context = LocalContext.current
    val gps = remember { GpsInfo() }
    val targets = remember { DriveTargets() }
    val carOk = readings.values.any { it.value != null }
    val spotifyOk = spotify.link == SpotifyController.Link.Connected

    // Which panel covers the map: null (map), "music" or "audio".
    var panel by remember { mutableStateOf<String?>(null) }
    // Music panel's own view: "now" (big Now playing) or "browse".
    var musicView by remember { mutableStateOf("now") }
    val requested by UiEvents.openPanel.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { panel = it; UiEvents.openPanel.value = null }
    }

    // Start-up: once per app start, only when this screen is the first thing drawn.
    var showStartup by remember {
        mutableStateOf(
            !Boot.startupShown && (
                Boot.startupBegun || Boot.firstFrameAt == 0L ||
                    SystemClock.elapsedRealtime() - Boot.firstFrameAt < 1_500
                ),
        )
    }
    LaunchedEffect(Unit) {
        if (!showStartup && Boot.handoffAt.value == 0L) {
            Boot.startupShown = true
            Boot.handoffAt.value = SystemClock.elapsedRealtime()
        }
    }
    // Targets that lock after the start-up (late car link / Spotify) flash when their event arrives.
    LaunchedEffect(Unit) {
        while (targets.arcPulseAt == 0L || targets.nowPlayingPulseAt == 0L) {
            delay(200)
            val h = Boot.handoffAt.value
            val t = SystemClock.elapsedRealtime()
            if (h == 0L || t < h + 1_000) continue
            if (targets.arcPulseAt == 0L && com.taycanscreen.app.obd.ObdEngine.readings.value.values.any { it.value != null }) {
                targets.arcPulseAt = t
            }
            if (targets.nowPlayingPulseAt == 0L && SpotifyController.status.value.link == SpotifyController.Link.Connected) {
                targets.nowPlayingPulseAt = t
            }
        }
    }

    // Gauge sweep: only on the first shift into D after start-up.
    var sweepAt by remember { mutableLongStateOf(0L) }
    val lastGear = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(v.gear) {
        val g = v.gear ?: return@LaunchedEffect
        val prev = lastGear[0]
        lastGear[0] = g
        if (Boot.sweepDone || g != "D") return@LaunchedEffect
        Boot.sweepDone = true
        if (prev == null) {
            AppLog.info("drive", "Already in D when the app started: no gauge sweep")
            return@LaunchedEffect
        }
        // Let the start-up rings land first.
        while (Boot.handoffAt.value == 0L || SystemClock.elapsedRealtime() < Boot.handoffAt.value + 900) delay(50)
        sweepAt = SystemClock.elapsedRealtime()
        AppLog.info("drive", "First shift $prev → D: gauge sweep")
    }
    val sweepT = rememberSince(sweepAt, 1.2f)

    // Charging theater: parked and energy flowing into the pack (on after ~1 s, off after ~3 s).
    var charging by remember { mutableStateOf(false) }
    var votes by remember { mutableIntStateOf(0) }
    LaunchedEffect(now) {
        val parked = v.gear == "P" || (v.gear == null && (v.speedMph?.x ?: 99.0) < 0.5)
        val inflow = (v.kw?.x ?: 0.0) > 1.0
        if (parked && inflow) {
            votes = if (votes < 0) 1 else votes + 1
            if (!charging && votes >= 2) {
                charging = true
                AppLog.info("drive", "Charging view on (%.1f kW in)".format(v.kw?.x ?: 0.0))
            }
        } else {
            votes = if (votes > 0) -1 else votes - 1
            if (charging && votes <= -6) {
                charging = false
                AppLog.info("drive", "Charging view off")
            }
        }
    }

    val dens = LocalDensity.current.density
    Box(Modifier.fillMaxSize().background(c.background)) {
        // Vehicle panel full height on the left (owner, 2026-11-09); map with the dock under it on the right.
        Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(448.dp).fillMaxHeight().background(c.panel)
                        .drawBehind { drawLine(c.border, Offset(size.width - density / 2, 0f), Offset(size.width - density / 2, size.height), density) }
                        .onGloballyPositioned { targets.panel = it.boundsInRoot() }
                        .padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        GearChips(v.gear, c, sweepT)
                        Spacer(Modifier.weight(1f))
                        val (label, dot) = when (status.state) {
                            LinkState.Live -> (if (carOk) "LINK" else "LINK…") to (if (carOk) c.accent else c.warn)
                            LinkState.CarAsleep -> "CAR OFF" to c.textFaint
                            LinkState.Connecting, LinkState.Initializing -> "LINKING" to c.warn
                            else -> "NO LINK" to c.warn
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Diamond(dot, 11.dp, glow = c.glow > 0f && dot == c.accent)
                            BasicText(label, style = Filament.label(13.sp, c.textDim, 0.14f))
                        }
                    }
                    if (charging) {
                        ChargingBlock(v.soc, v.kw?.x ?: 0.0, v.rangeMi, v.packMaxC, c)
                    } else {
                        val arcPulse = rememberSince(targets.arcPulseAt, 1.0f)
                        PowerGauge(
                            v.speedMph, v.kw,
                            linkProblem = if (status.state != LinkState.Live && status.state != LinkState.CarAsleep) status.detail.ifBlank { null } else null,
                            c = c, sweepT = sweepT, pulseT = arcPulse,
                            modifier = Modifier.onGloballyPositioned {
                                targets.arc = it.boundsInRoot().topLeft + Offset(196 * dens, 170 * dens)
                            },
                        )
                        BatteryBlock(v.soc, v.rangeMi, v.packMaxC, v.lowVoltage, c, onOpen12V = {
                            panel = if (panel == "lv") null else "lv"
                            AppLog.info("ui", "12 V battery panel")
                        })
                    }
                    Spacer(Modifier.weight(1f))
                    // The big Now playing view over the map makes the mini card redundant (owner, 2026-11-09).
                    if (!(panel == "music" && musicView == "now")) {
                        FilamentNowPlaying(
                            c, now,
                            onOpenMusic = { panel = "music" },
                            modifier = Modifier.onGloballyPositioned { targets.nowPlaying = it.boundsInRoot() },
                            pulseT = rememberSince(targets.nowPlayingPulseAt, 1.0f),
                        )
                    }
                }
                // The map keeps running underneath while Music is open, so it's current when you close it.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MapArea(c, v.outsideF, locationGranted, gps, targets, Modifier.fillMaxSize())
                    if (panel == "search") SearchPanel(
                        c, v.speedMph?.x, gps.fix?.let { com.taycanscreen.app.nav.LatLon(it.latitude, it.longitude) },
                        onClose = { panel = null }, modifier = Modifier.fillMaxSize(),
                    )
                    if (panel == "lv") LowVoltagePanel(v.lowVoltage, c, onClose = { panel = null }, modifier = Modifier.fillMaxSize())
                    CompositionLocalProvider(LocalDensity provides realDensity) {
                        when (panel) {
                            "music" -> MusicPanel(
                                c,
                                onClose = { panel = null },
                                onOpenSpotifyApp = { launchFirst(context, "Spotify", "com.spotify.music") },
                                modifier = Modifier.fillMaxSize(),
                                onViewChange = { musicView = it },
                            )
                            "audio" -> AudioPanel(c, onClose = { panel = null }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            Dock(
                c, themeMode, panel,
                carOk = carOk, gpsOk = gps.fix != null, spotifyOk = spotifyOk,
                onCycleTheme = onCycleTheme, onOpenVehicle = onOpenVehicle, onOpenSetup = onOpenSetup,
                onNavigate = { panel = if (panel == "search") null else "search" },
                onOpenMusic = { panel = if (panel == "music") null else "music" },
                onOpenAudio = { panel = if (panel == "audio") null else "audio" },
                modifier = Modifier.fillMaxWidth().height(96.dp).onGloballyPositioned { targets.dock = it.boundsInRoot() },
            )
                }
        }
        if (showStartup) {
            StartupOverlay(
                c, targets, gps,
                StartupReadouts(
                    hvVolts = v.volts?.x, lowVolts = v.lowVolts?.x,
                    packMinC = v.packMinC?.x, packMaxC = v.packMaxC?.x,
                    outsideF = v.outsideF?.x, socPct = v.soc?.x,
                ),
                carOk = carOk, spotifyOk = spotifyOk,
                onFinished = { showStartup = false },
            )
        }
    }
}

/** Filament now-playing card (cut corners, 1 px frame), Spotify running on the box. */
@Composable
private fun FilamentNowPlaying(c: DriveColors, now: Long, onOpenMusic: () -> Unit, modifier: Modifier, pulseT: Float?) {
    val status by SpotifyController.status.collectAsState()
    val np by SpotifyController.nowPlaying.collectAsState()
    val art by SpotifyController.artwork.collectAsState()
    val connected = status.link == SpotifyController.Link.Connected
    val flash = pulseT?.let { keys(it, 0f to 0f, 0.05f to 1f, 1.0f to 0f) } ?: 0f
    Box(
        modifier.fillMaxWidth().background(c.border, Filament.Cut).padding(1.dp)
            .drawWithContent {
                drawContent()
                if (flash > 0f) {
                    val s = density
                    val cut = 14 * s
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cut, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - cut)
                        lineTo(size.width - cut, size.height); lineTo(0f, size.height); lineTo(0f, cut); close()
                    }
                    drawPath(p, c.accent.copy(alpha = flash), style = Stroke(2 * s))
                    drawPath(p, c.accent.copy(alpha = 0.12f * flash))
                }
            },
    ) {
        // Bigger now playing (owner, 2026-11-09): the full-height panel has room for it.
        Column(
            Modifier.fillMaxWidth().background(c.raised, Filament.Cut).clickable(onClick = onOpenMusic)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(c.coverBg), contentAlignment = Alignment.Center) {
                    val bmp = art
                    if (connected && bmp != null) {
                        Image(bmp.asImageBitmap(), contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(FIcons.Music, null, tint = c.textFaint, modifier = Modifier.size(36.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        if (connected) np?.title ?: "Nothing playing" else "Spotify",
                        style = Filament.text(24.sp, 600, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        if (connected) np?.artist?.ifBlank { null } ?: "Tap to browse" else status.detail.ifBlank { "Connecting…" },
                        style = Filament.text(18.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val track = np
            val pos = if (track == null) 0L else (track.positionMs + if (track.paused) 0L else (now - track.positionAt)).coerceIn(0L, track.durationMs)
            val pct = if (track == null || track.durationMs <= 0) 0f else pos.toFloat() / track.durationMs
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(c.lineFaint)) {
                    Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                }
                Row {
                    BasicText(if (track == null) NO_DATA else mmss(pos), style = Filament.label(14.sp, c.textFaint, 0f))
                    Spacer(Modifier.weight(1f))
                    BasicText(if (track == null) NO_DATA else mmss(track.durationMs), style = Filament.label(14.sp, c.textFaint, 0f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                Box(Modifier.size(88.dp, 76.dp).clickable { SpotifyController.previous() }, contentAlignment = Alignment.Center) {
                    Icon(FIcons.Prev, "Previous track", tint = c.text, modifier = Modifier.size(34.dp))
                }
                val playing = track?.paused == false
                Box(
                    Modifier.size(108.dp, 76.dp).background(c.accentSoft, Filament.CutS).insetLine(c.accentLine)
                        .clickable { SpotifyController.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) FIcons.Pause else FIcons.Play, if (playing) "Pause" else "Play", tint = c.accent, modifier = Modifier.size(38.dp))
                }
                Box(Modifier.size(88.dp, 76.dp).clickable { SpotifyController.next() }, contentAlignment = Alignment.Center) {
                    Icon(FIcons.Next, "Next track", tint = c.text, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun IconTap(icon: ImageVector, label: String, tint: Color, iconSize: Int, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

@Composable
private fun Dock(
    c: DriveColors,
    themeMode: ThemeMode,
    panel: String?,
    carOk: Boolean,
    gpsOk: Boolean,
    spotifyOk: Boolean,
    onCycleTheme: () -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenSetup: () -> Unit,
    onNavigate: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenAudio: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.background(c.panel).drawBehind { drawLine(c.border, Offset(0f, density / 2), Offset(size.width, density / 2), density) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DockButton(FIcons.Vehicle, "Vehicle details", false, c, iconSize = 34, onClick = onOpenVehicle)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusLight("CAR", carOk, c)
                StatusLight("GPS", gpsOk, c)
                StatusLight("SPOTIFY", spotifyOk, c)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            DockButton(FIcons.Navigate, if (panel == "search") "Close destination search" else "Search for a destination", panel == null || panel == "search", c, onClick = onNavigate)
            DockButton(FIcons.Music, "Music", panel == "music", c, onClick = onOpenMusic)
            // Status light: cyan when the EQ is on Spotify's audio, amber when it isn't (see the Audio panel).
            val eq by com.taycanscreen.app.audio.EqEngine.status.collectAsState()
            DockButton(
                FIcons.Eq, "Equalizer", panel == "audio", c,
                light = if (eq.attachedTo == "Spotify") c.accent else c.warn,
                onClick = onOpenAudio,
            )
            val themeIcon = when (themeMode) {
                ThemeMode.Auto -> FIcons.Auto
                ThemeMode.Day -> FIcons.Sun
                ThemeMode.Night -> FIcons.Moon
            }
            DockButton(themeIcon, "Day/night: ${themeMode.label()}", false, c, label = themeMode.label().uppercase(), onClick = onCycleTheme)
            DockButton(FIcons.Setup, "Setup", false, c, onClick = onOpenSetup)
        }
        VolumeControl(c, Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun StatusLight(label: String, ok: Boolean, c: DriveColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Diamond(if (ok) c.accent else c.textFaint, 8.5.dp, glow = false)
        BasicText(label, style = Filament.label(12.sp, if (ok) c.textDim else c.textFaint, 0.12f))
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    c: DriveColors,
    iconSize: Int = 32,
    label: String? = null,
    light: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(96.dp, 72.dp)
            .background(if (active) c.accentSoft else Color.Transparent, Filament.CutS)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp).fillMaxWidth().height(3.dp)
                    .drawBehind { if (c.glow > 0f) drawRect(c.accent.copy(alpha = 0.35f), Offset(-4 * density, -2 * density), size.copy(size.width + 8 * density, size.height + 6 * density)) }
                    .background(c.accent),
            )
        }
        if (light != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 22.dp)) { Diamond(light, 11.dp, glow = c.glow > 0f) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = description, tint = if (active) c.accent else c.textDim, modifier = Modifier.size(iconSize.dp))
            if (label != null) BasicText(label, Modifier.padding(top = 2.dp), style = Filament.label(11.sp, c.textFaint, 0.1f))
        }
    }
}

@Composable
private fun VolumeControl(c: DriveColors, modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.takeIf { it > 0 } ?: 15 }
    var vol by remember { mutableIntStateOf(audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) }
    LaunchedEffect(Unit) {
        while (true) {
            audio?.let { vol = it.getStreamVolume(AudioManager.STREAM_MUSIC) }
            delay(1_000)
        }
    }
    fun adjust(direction: Int) {
        audio ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
        IconTap(FIcons.Minus, "Volume down", c.text, 26) { adjust(AudioManager.ADJUST_LOWER) }
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(FIcons.Volume, null, tint = c.textDim, modifier = Modifier.size(18.dp))
                BasicText("$vol", style = Filament.num(28.sp, 600, c.text))
            }
            val lit = (vol * 10f / max).roundToInt()
            Row(Modifier.width(72.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (n in 0 until 10) Box(Modifier.weight(1f).fillMaxHeight().background(if (n < lit) c.accent else c.border))
            }
        }
        IconTap(FIcons.Plus, "Volume up", c.text, 26) { adjust(AudioManager.ADJUST_RAISE) }
    }
}

private fun launchFirst(context: Context, name: String, vararg packages: String) {
    for (p in packages) {
        val intent = context.packageManager.getLaunchIntentForPackage(p) ?: continue
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        AppLog.info("dock", "Opened $p")
        return
    }
    AppLog.warn("dock", "$name not installed (${packages.joinToString()})")
    Toast.makeText(context, "$name isn't installed on the box", Toast.LENGTH_LONG).show()
}
