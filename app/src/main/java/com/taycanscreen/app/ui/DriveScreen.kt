package com.taycanscreen.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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

    // Screenshots for the log: after the start-up, every 10 minutes, a new route, the charging view.
    LaunchedEffect(Unit) {
        while (Boot.handoffAt.value == 0L) delay(250)
        val wait = Boot.handoffAt.value + 2_500 - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        com.taycanscreen.app.log.Screenshots.capture("start-up")
        while (true) {
            delay(10 * 60_000L)
            com.taycanscreen.app.log.Screenshots.capture("drive")
        }
    }
    val routeId = com.taycanscreen.app.nav.Nav.active.collectAsState().value?.id
    LaunchedEffect(routeId) {
        if (routeId == null) return@LaunchedEffect
        delay(3_000)
        com.taycanscreen.app.log.Screenshots.capture("route")
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

    // Charging view: parked with energy flowing in (TripComputer decides; it also logs the session).
    val charging by com.taycanscreen.app.car.TripComputer.charging.collectAsState()
    LaunchedEffect(charging) {
        if (!charging) return@LaunchedEffect
        delay(3_000)
        com.taycanscreen.app.log.Screenshots.capture("charging")
    }

    // Energy ledger tab (Trip, Battery, Tires, Power, 12 V).
    var ledgerTab by remember { mutableStateOf("TRIP") }
    val limit by com.taycanscreen.app.car.SpeedLimit.limit.collectAsState()
    val progress by com.taycanscreen.app.nav.Nav.progress.collectAsState()
    val arrivePct = progress?.let { com.taycanscreen.app.car.Arrival.pct(it.remainingM, v.soc?.x, v.rangeMi?.x) }
    val dirMode by com.taycanscreen.app.nav.LocalDirections.mode.collectAsState()
    val headingName = gps.heading?.let {
        com.taycanscreen.app.nav.LocalDirections.name(it.toDouble(), gps.fix?.let { f -> com.taycanscreen.app.nav.LatLon(f.latitude, f.longitude) }, dirMode)
    }
    val trip by com.taycanscreen.app.car.TripComputer.trip.collectAsState()

    Box(Modifier.fillMaxSize().background(c.background)) {
        // Vehicle panel full height on the left (owner, 2026-11-09); map with the dock under it on the right.
        Row(Modifier.fillMaxSize()) {
            V3Panel(
                v, c, now, status.state, status.detail, carOk, headingName, limit, sweepT,
                arcPulse = rememberSince(targets.arcPulseAt, 1.0f),
                charging = charging, arrivePct = arrivePct,
                tireWarn = worstTire(readings, now),
                showPlayer = !(panel == "music" && musicView == "now"),
                targets = targets,
                onLedger = { tab -> ledgerTab = tab; panel = "ledger" },
                onOpenMusic = { panel = "music" },
                playerPulse = rememberSince(targets.nowPlayingPulseAt, 1.0f),
                modifier = Modifier.width(420.dp).fillMaxHeight().background(c.panel)
                    .drawBehind { drawLine(c.border, Offset(size.width - density / 2, 0f), Offset(size.width - density / 2, size.height), density) }
                    .onGloballyPositioned { targets.panel = it.boundsInRoot() },
            )
            // The map keeps running underneath the panels, so it's current when you close them.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MapArea(c, v.outsideF, locationGranted, gps, targets, Modifier.fillMaxSize())
                    when (panel) {
                        "search" -> SearchPanel(
                            c, v.speedMph?.x, gps.fix?.let { com.taycanscreen.app.nav.LatLon(it.latitude, it.longitude) },
                            onClose = { panel = null }, modifier = Modifier.fillMaxSize(),
                        )
                        "ledger" -> LedgerPanel(c, ledgerTab, now, onTab = { ledgerTab = it }, onClose = { panel = null }, modifier = Modifier)
                    }
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
                V3Dock(
                    c, themeMode, panel, trip?.distanceMi,
                    onCycleTheme = onCycleTheme, onOpenSetup = onOpenSetup,
                    onNavigate = { panel = if (panel == "search") null else "search" },
                    onOpenMusic = { panel = if (panel == "music") null else "music" },
                    onOpenAudio = { panel = if (panel == "audio") null else "audio" },
                    onOpenLedger = { panel = if (panel == "ledger") null else "ledger" },
                    modifier = Modifier.fillMaxWidth().height(64.dp).onGloballyPositioned { targets.dock = it.boundsInRoot() },
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

/**
 * v3 instrument panel (Taycan Center Screen Concept, "Center screen — v3"): time / direction / outside
 * temperature, a big light speed with gear and speed limit, the centre-zero power line, the battery
 * (opens the energy ledger), and now playing.
 */
@Composable
private fun V3Panel(
    v: VehicleValues,
    c: DriveColors,
    now: Long,
    link: LinkState,
    linkDetail: String,
    carOk: Boolean,
    headingName: String?,
    limit: com.taycanscreen.app.car.SpeedLimit.Limit?,
    sweepT: Float?,
    arcPulse: Float?,
    charging: Boolean,
    arrivePct: Double?,
    tireWarn: Int,
    showPlayer: Boolean,
    targets: DriveTargets,
    onLedger: (String) -> Unit,
    onOpenMusic: () -> Unit,
    playerPulse: Float?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(modifier.padding(start = 36.dp, end = 36.dp, top = 26.dp, bottom = 22.dp)) {
        // Time (press and hold: screenshot for the log) · direction of travel · outside temperature.
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm")),
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        com.taycanscreen.app.log.Screenshots.capture("owner") { ok ->
                            Toast.makeText(
                                context,
                                if (ok) "Screenshot saved. It goes with your next \"Send log to Claude\"." else "Couldn't take a screenshot",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    })
                },
                style = Filament.label(13.sp, c.text, 0.06f),
            )
            Spacer(Modifier.weight(1f))
            if (tireWarn > 0) {
                BasicText(
                    "TIRE PRESSURE LOW ›",
                    Modifier.clickable { onLedger("TIRES") },
                    style = Filament.label(13.sp, if (tireWarn > 1) c.danger else c.warn, 0.06f, 500),
                )
            } else {
                BasicText(headingName ?: "", style = Filament.label(13.sp, c.textDim, 0.06f))
            }
            Spacer(Modifier.weight(1f))
            BasicText(v.outsideF?.let { "${it.x.roundToInt()}°" } ?: NO_DATA, style = Filament.label(13.sp, c.textDim, 0.06f))
        }

        if (charging) {
            Spacer(Modifier.height(18.dp))
            ChargingBlock(v.soc, v.kw?.x ?: 0.0, v.rangeMi, v.packMaxC, c)
        } else {
            Spacer(Modifier.height(20.dp))
            // Speed, with gear, the road's limit and the unit on the right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // No speed yet: a short faint dash (an em dash at this size is a heavy bar).
                BasicText(
                    v.speedMph?.x?.roundToInt()?.toString() ?: "–",
                    Modifier.weight(1f).capBox(168.dp).alpha(if (v.speedMph?.dim == true) 0.45f else 1f)
                        .onGloballyPositioned { targets.arc = it.boundsInRoot().center },
                    style = Filament.num(168.sp, 300, if (v.speedMph == null) c.textFaint else c.text).copy(letterSpacing = (-8).sp),
                    maxLines = 1,
                )
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (limit != null) {
                        val over = (v.speedMph?.x ?: 0.0) > limit.mph + 5
                        Column(
                            Modifier.border(1.5.dp, if (over) c.warn else c.textDim).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BasicText("LIMIT", style = Filament.label(9.sp, if (over) c.warn else c.textFaint, 0.12f))
                            BasicText("${limit.mph}", style = Filament.num(24.sp, 500, if (over) c.warn else c.text))
                        }
                    }
                    BasicText(v.gear ?: NO_DATA, style = Filament.label(13.sp, c.text, 0.1f, 500))
                    BasicText("MPH", Modifier.padding(bottom = 4.dp), style = Filament.label(13.sp, c.textFaint, 0.1f))
                }
            }
            Spacer(Modifier.height(20.dp))
            PowerLine(
                v.kw, c, sweepT, arcPulse,
                problem = if (link != LinkState.Live && link != LinkState.CarAsleep) linkDetail.ifBlank { null }
                else if (link == LinkState.CarAsleep) "CAR OFF" else if (!carOk) "WAITING FOR THE CAR" else null,
            )
            Spacer(Modifier.height(24.dp))
            // Battery: opens the energy ledger.
            val soc = v.soc
            val col = soc?.let { chargeColor(it.x, c) } ?: c.text
            Column(Modifier.fillMaxWidth().clickable { onLedger("TRIP") }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        soc?.x?.roundToInt()?.toString() ?: "–",
                        Modifier.alignByBaseline().alpha(if (soc?.dim == true) 0.45f else 1f),
                        style = Filament.num(56.sp, 300, if (soc == null) c.textFaint else col).copy(letterSpacing = (-1.5).sp),
                    )
                    BasicText("%", Modifier.alignByBaseline(), style = Filament.num(26.sp, 300, col))
                    Spacer(Modifier.weight(1f))
                    BasicText(
                        "${v.rangeMi?.x?.roundToInt() ?: NO_DATA} mi",
                        Modifier.alignByBaseline().alpha(if (v.rangeMi?.dim == true) 0.45f else 1f),
                        style = Filament.text(22.sp, 400, c.textDim),
                    )
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(c.border)) {
                    if (soc != null) Box(Modifier.fillMaxWidth((soc.x / 100.0).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(col))
                }
                Row(Modifier.fillMaxWidth()) {
                    if (arrivePct != null) {
                        BasicText(
                            "ARRIVE ${arrivePct.roundToInt().coerceAtLeast(0)}%",
                            style = Filament.label(12.sp, if (arrivePct < 10) c.warn else c.textFaint, 0.08f),
                        )
                    } else {
                        val lv = v.lowVoltage.soc
                        BasicText(
                            "PACK ${v.packMaxC?.let { "%.0f°F".format(it.x * 9 / 5 + 32) } ?: NO_DATA} · 12V ${lv?.let { "${it.x.roundToInt()}%" } ?: NO_DATA}",
                            style = Filament.label(12.sp, lv?.let { if (it.x < 75) lowVoltageColor(it.x, c) else c.textFaint } ?: c.textFaint, 0.08f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    BasicText("LEDGER ›", style = Filament.label(12.sp, c.textFaint, 0.08f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (showPlayer) {
            V3Player(c, now, onOpenMusic, Modifier.onGloballyPositioned { targets.nowPlaying = it.boundsInRoot() }, playerPulse)
        }
    }
}

/** Centre-zero power line (v3): regen to the left third (−150 kW), drive to the right (300 kW). */
@Composable
private fun PowerLine(kw: V?, c: DriveColors, sweepT: Float?, pulseT: Float?, problem: String?) {
    val drive = kw?.let { -it.x } // positive = drawing power
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val w = size.width
            val y = size.height / 2
            val x0 = w / 3f
            drawRect(c.border, Offset(0f, y - density), Size(w, 2 * density))
            val pulse = pulseT?.let { keys(it, 0f to 0f, 0.05f to 1f, 1f to 0f) } ?: 0f
            if (pulse > 0f) drawRect(c.accent.copy(alpha = pulse), Offset(0f, y - density), Size(w, 2 * density))
            drawRect(c.textFaint, Offset(x0 - density / 2, y - 6 * density), Size(density, 12 * density))
            if (sweepT != null) {
                // First shift into D: sweep the full range once and settle at zero.
                val pos = when {
                    sweepT < 0.05f -> 0f
                    sweepT < 0.30f -> keys(sweepT, 0.05f to 0f, 0.30f to 1f, easing = EaseSweep)
                    sweepT < 0.60f -> keys(sweepT, 0.30f to 1f, 0.60f to -1f, easing = EaseSweep)
                    else -> keys(sweepT, 0.60f to -1f, 0.85f to 0f, easing = EaseSweep)
                }
                val alpha = keys(sweepT, 0f to 0f, 0.05f to 1f, 1.0f to 1f, 1.2f to 0f)
                val end = if (pos >= 0) x0 + pos * (w - x0) else x0 + pos * x0
                drawRect(c.accent.copy(alpha = alpha), Offset(minOf(x0, end), y - 2 * density), Size(kotlin.math.abs(end - x0), 4 * density))
            } else if (drive != null) {
                val a = if (kw.dim) 0.45f else 1f
                if (drive >= 0) {
                    val len = (drive / 300.0).coerceIn(0.0, 1.0).toFloat() * (w - x0)
                    drawRect(c.text.copy(alpha = a), Offset(x0, y - 2 * density), Size(len, 4 * density))
                } else {
                    val len = (-drive / 150.0).coerceIn(0.0, 1.0).toFloat() * x0
                    drawRect(c.accent.copy(alpha = a), Offset(x0 - len, y - 2 * density), Size(len, 4 * density))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            BasicText("REGEN", style = Filament.label(12.sp, c.textFaint, 0.08f))
            Spacer(Modifier.weight(1f))
            BasicText(
                when {
                    drive == null -> "$NO_DATA kW"
                    drive >= 0 -> "${drive.roundToInt()} kW"
                    else -> "−${(-drive).roundToInt()} kW"
                },
                style = Filament.label(12.sp, if ((drive ?: 0.0) < -0.5) c.accent else c.text, 0.08f),
            )
        }
        if (problem != null) {
            BasicText(problem.uppercase(), style = Filament.label(12.sp, c.warn, 0.04f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Now playing (v3 style, enlarged at the owner's request): cover, title, artist, progress, round controls. */
@Composable
private fun V3Player(c: DriveColors, now: Long, onOpenMusic: () -> Unit, modifier: Modifier, pulseT: Float?) {
    val status by SpotifyController.status.collectAsState()
    val np by SpotifyController.nowPlaying.collectAsState()
    val art by SpotifyController.artwork.collectAsState()
    val connected = status.link == SpotifyController.Link.Connected
    val flash = pulseT?.let { keys(it, 0f to 0f, 0.05f to 1f, 1.0f to 0f) } ?: 0f
    Column(
        modifier.fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (flash > 0f) drawRect(c.accent.copy(alpha = flash), style = Stroke(2 * density))
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenMusic),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(4.dp)).background(c.coverBg), contentAlignment = Alignment.Center) {
                val bmp = art
                if (connected && bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(FIcons.Music, null, tint = c.textFaint, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(
                    if (connected) np?.title ?: "Nothing playing" else "Spotify",
                    style = Filament.text(20.sp, 500, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    if (connected) np?.artist?.ifBlank { null } ?: "Tap to browse" else status.detail.ifBlank { "Connecting…" },
                    style = Filament.text(16.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val track = np
        val pos = if (track == null) 0L else (track.positionMs + if (track.paused) 0L else (now - track.positionAt)).coerceIn(0L, track.durationMs)
        val pct = if (track == null || track.durationMs <= 0) 0f else pos.toFloat() / track.durationMs
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.border)) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().background(c.text))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(FIcons.Prev, "Previous track", c, 64, false) { SpotifyController.previous() }
            val playing = track?.paused == false
            RoundButton(if (playing) FIcons.Pause else FIcons.Play, if (playing) "Pause" else "Play", c, 76, true) { SpotifyController.togglePlay() }
            RoundButton(FIcons.Next, "Next track", c, 64, false) { SpotifyController.next() }
        }
        // One-tap podcast (owner, 2026-11-09): newest "Up First" from NPR.
        val context = LocalContext.current
        val busy by com.taycanscreen.app.music.QuickPlay.busy.collectAsState()
        val msg by com.taycanscreen.app.music.QuickPlay.message.collectAsState()
        val show = com.taycanscreen.app.music.QuickPlay.UP_FIRST
        Row(
            Modifier.fillMaxWidth().height(52.dp).border(1.dp, c.border)
                .clickable { com.taycanscreen.app.music.QuickPlay.play(context, show) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(FIcons.Play, null, tint = c.accent, modifier = Modifier.size(18.dp))
            BasicText("UP FIRST", style = Filament.label(13.sp, c.text, 0.12f, 500))
            BasicText("NPR · newest episode", Modifier.weight(1f), style = Filament.text(15.sp, 400, c.textDim), maxLines = 1)
            if (busy == show.key) BasicText("…", style = Filament.label(15.sp, c.accent, 0f))
        }
        if (msg != null) {
            BasicText(
                msg!!, Modifier.clickable { com.taycanscreen.app.music.QuickPlay.clearMessage() },
                style = Filament.text(14.sp, 400, c.warn), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, c: DriveColors, size: Int, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).border(1.dp, if (primary) c.accentLine else c.border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (primary) c.accent else c.text, modifier = Modifier.size((size * 0.36f).dp))
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** v3 dock under the map: trip distance, app icons with an active dot, volume. */
@Composable
private fun V3Dock(
    c: DriveColors,
    themeMode: ThemeMode,
    panel: String?,
    tripMi: Double?,
    onCycleTheme: () -> Unit,
    onOpenSetup: () -> Unit,
    onNavigate: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier,
) {
    val eq by com.taycanscreen.app.audio.EqEngine.status.collectAsState()
    Row(
        modifier.background(c.background).drawBehind { drawLine(c.border, Offset(0f, density / 2), Offset(size.width, density / 2), density) }
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "TRIP ${tripMi?.let { "%.1f".format(it) } ?: NO_DATA} MI",
            Modifier.width(170.dp),
            style = Filament.label(12.sp, c.textFaint, 0.1f),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            DockIcon(FIcons.Navigate, if (panel == "search") "Close destination search" else "Search for a destination", panel == null || panel == "search", c, onClick = onNavigate)
            DockIcon(FIcons.Music, "Music", panel == "music", c, onClick = onOpenMusic)
            // Amber corner light while the EQ isn't on Spotify's audio.
            DockIcon(FIcons.Eq, "Equalizer", panel == "audio", c, light = if (eq.attachedTo == "Spotify") null else c.warn, onClick = onOpenAudio)
            DockIcon(FIcons.Bolt, "Energy ledger", panel == "ledger", c, onClick = onOpenLedger)
            val themeIcon = when (themeMode) {
                ThemeMode.Auto -> FIcons.Auto
                ThemeMode.Day -> FIcons.Sun
                ThemeMode.Night -> FIcons.Moon
            }
            DockIcon(themeIcon, "Day/night: ${themeMode.label()}", false, c, onClick = onCycleTheme)
            DockIcon(FIcons.Setup, "Setup", false, c, onClick = onOpenSetup)
        }
        VolumeControl(c, Modifier.width(170.dp))
    }
}

@Composable
private fun DockIcon(icon: ImageVector, description: String, active: Boolean, c: DriveColors, light: Color? = null, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = description, tint = if (active) c.text else c.textFaint, modifier = Modifier.size(24.dp))
            Box(Modifier.size(4.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent))
        }
        if (light != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(7.dp).clip(CircleShape).background(light))
    }
}

@Composable
private fun VolumeControl(c: DriveColors, modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
        Box(Modifier.size(56.dp).clickable { adjust(AudioManager.ADJUST_LOWER) }, contentAlignment = Alignment.Center) {
            Icon(FIcons.Minus, "Volume down", tint = c.textDim, modifier = Modifier.size(24.dp))
        }
        BasicText("$vol", Modifier.width(34.dp), style = Filament.label(15.sp, c.text, 0f, 500).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        Box(Modifier.size(56.dp).clickable { adjust(AudioManager.ADJUST_RAISE) }, contentAlignment = Alignment.Center) {
            Icon(FIcons.Plus, "Volume up", tint = c.textDim, modifier = Modifier.size(24.dp))
        }
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
