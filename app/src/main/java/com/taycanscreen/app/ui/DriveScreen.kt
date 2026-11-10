package com.taycanscreen.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.basicMarquee
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
    // Day ↔ night: dip to the new background, swap the colours behind it, then reveal (no hard cut).
    var shown by remember { mutableStateOf(colors) }
    val veil = remember { Animatable(0f) }
    LaunchedEffect(colors) {
        if (colors == shown) return@LaunchedEffect
        veil.animateTo(1f, tween(180))
        shown = colors
        delay(80) // the map recolours
        veil.animateTo(0f, tween(360))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(shown.background)) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val k = min(wPx / 1602f, hPx / 720f).coerceAtLeast(0.3f)
        LaunchedEffect(wPx, hPx) {
            AppLog.info("screen", "Drive screen %d×%d px, %.3f px per design px".format(wPx, hPx, k))
        }
        CompositionLocalProvider(LocalDensity provides Density(k, 1f)) {
            DriveContent(obd, shown, themeMode, locationGranted, onCycleTheme, onOpenVehicle, onOpenSetup, real)
        }
        if (veil.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(colors.background))
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
    var now by remember { mutableLongStateOf(com.taycanscreen.app.util.Mono.ms()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = com.taycanscreen.app.util.Mono.ms()
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
            !UiEvents.preview && !Boot.startupShown && (
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
    // Search opened to set Home or Work (null = normal search).
    var saveAs by remember { mutableStateOf<String?>(null) }
    val limit by com.taycanscreen.app.car.SpeedLimit.limit.collectAsState()
    val trip by com.taycanscreen.app.car.TripComputer.trip.collectAsState()

    Box(Modifier.fillMaxSize().background(c.background)) {
        // Vehicle panel full height on the left (owner, 2026-11-09); map with the dock under it on the right.
        Row(Modifier.fillMaxSize()) {
            V3Panel(
                v, c, now, status, carOk, limit, sweepT,
                arcPulse = rememberSince(targets.arcPulseAt, 1.0f),
                charging = charging,
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
                    MapArea(
                        c, v.outsideF, locationGranted, gps, targets, Modifier.fillMaxSize(),
                        onSearch = { saveAs = null; panel = "search" },
                        onSaved = { kind ->
                            // Home / Work: route there at once, or set it first.
                            val here = gps.fix?.let { com.taycanscreen.app.nav.LatLon(it.latitude, it.longitude) }
                            if (!com.taycanscreen.app.nav.Nav.goSaved(kind, here)) {
                                AppLog.info("nav", "${kind.replaceFirstChar { it.uppercase() }} not set yet: opening search to set it")
                                saveAs = kind
                                panel = "search"
                            }
                        },
                    )
                    // Panels ease in and out over the map instead of cutting.
                    Appear(panel == "search") {
                        SearchPanel(
                            // Typing lock: the car's speed, or GPS speed when the car link is down (it used to
                            // unlock whenever the car's speed was unknown).
                            c, v.speedMph?.x ?: gps.fix?.takeIf { it.hasSpeed() }?.let { it.speed * 2.23694 },
                            gps.fix?.let { com.taycanscreen.app.nav.LatLon(it.latitude, it.longitude) },
                            onClose = { panel = null; saveAs = null }, modifier = Modifier.fillMaxSize(),
                            initialSaveAs = saveAs,
                        )
                    }
                    Appear(
                        panel == "ledger",
                        enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { -it / 6 },
                        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it / 6 },
                    ) {
                        LedgerPanel(c, ledgerTab, now, onTab = { ledgerTab = it }, onClose = { panel = null }, modifier = Modifier)
                    }
                    Appear(panel == "music") {
                        CompositionLocalProvider(LocalDensity provides realDensity) {
                            MusicPanel(
                                c,
                                onClose = { panel = null },
                                onOpenSpotifyApp = { launchFirst(context, "Spotify", "com.spotify.music") },
                                modifier = Modifier.fillMaxSize(),
                                onViewChange = { musicView = it },
                            )
                        }
                    }
                    Appear(panel == "audio") {
                        CompositionLocalProvider(LocalDensity provides realDensity) {
                            AudioPanel(c, onClose = { panel = null }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                V3Dock(
                    c, themeMode, panel, trip?.distanceMi,
                    onCycleTheme = onCycleTheme, onOpenSetup = onOpenSetup,
                    onNavigate = { saveAs = null; panel = if (panel == "search") null else "search" },
                    onOpenMusic = { panel = if (panel == "music") null else "music" },
                    onOpenAudio = { panel = if (panel == "audio") null else "audio" },
                    onOpenLedger = { panel = if (panel == "ledger") null else "ledger" },
                    modifier = Modifier.fillMaxWidth().height(128.dp).onGloballyPositioned { targets.dock = it.boundsInRoot() },
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
    link: com.taycanscreen.app.obd.ObdStatus,
    carOk: Boolean,
    limit: com.taycanscreen.app.car.SpeedLimit.Limit?,
    sweepT: Float?,
    arcPulse: Float?,
    charging: Boolean,
    tireWarn: Int,
    showPlayer: Boolean,
    targets: DriveTargets,
    onLedger: (String) -> Unit,
    onOpenMusic: () -> Unit,
    playerPulse: Float?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 20.dp)) {
        // Status row, top right (owner, 2026-11-10: less clutter): hotspot, a small battery with its %,
        // and the clock in the corner. The outside temperature is gone (Porsche's frame shows it).
        // Tap the battery for the energy ledger; press and hold the clock for a screenshot.
        val soc = v.soc
        val col = soc?.let { chargeColor(it.x, c) } ?: c.text
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            HotspotIcon(c, Modifier.size(34.dp, 28.dp))
            Spacer(Modifier.width(18.dp))
            Row(
                Modifier.height(58.dp).clickable { onLedger("TRIP") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatteryIcon(soc?.x, col, c, Modifier.size(48.dp, 26.dp))
                BasicText(
                    soc?.x?.roundToInt()?.let { "$it%" } ?: NO_DATA,
                    Modifier.padding(start = 8.dp).alpha(if (soc?.dim == true) 0.45f else 1f),
                    style = Filament.num(18.sp, 500, if (soc == null) c.textFaint else col),
                )
            }
            Spacer(Modifier.width(22.dp))
            BasicText(
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm")),
                Modifier.capBox(58.dp, pad = 2.dp).pointerInput(Unit) {
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
                style = Filament.num(46.sp, 400, c.text).copy(letterSpacing = (-1).sp),
            )
        }
        if (tireWarn > 0) {
            BasicText(
                "TIRE PRESSURE LOW ›",
                Modifier.padding(top = 8.dp).clickable { onLedger("TIRES") },
                style = Filament.label(13.sp, if (tireWarn > 1) c.danger else c.warn, 0.06f, 500),
            )
        }

        if (charging) {
            Spacer(Modifier.height(14.dp))
            ChargingBlock(v.soc, v.kw?.x ?: 0.0, v.rangeMi, v.packMaxC, c)
        } else {
            Spacer(Modifier.height(10.dp))
            // Speed, with gear, the road's limit and the unit on the right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // No speed yet: a short faint dash (an em dash at this size is a heavy bar).
                SpeedNumber(
                    v.speedMph, c,
                    Modifier.weight(1f).capBox(168.dp).alpha(if (v.speedMph?.dim == true) 0.45f else 1f)
                        .onGloballyPositioned { targets.arc = it.boundsInRoot().center },
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
                    BasicText(v.gear ?: NO_DATA, Modifier.capBox(58.dp, pad = 2.dp), style = Filament.num(46.sp, 400, c.text))
                    BasicText("MPH", Modifier.padding(bottom = 4.dp), style = Filament.label(13.sp, c.textFaint, 0.1f))
                }
            }
            Spacer(Modifier.height(18.dp))
            PowerLine(
                v.kw, c, sweepT, arcPulse,
                note = linkNote(link, carOk, now),
            )
        }
        Spacer(Modifier.height(22.dp))
        // Now playing fills the rest of the panel (owner, 2026-11-10).
        if (showPlayer) {
            V3Player(c, now, onOpenMusic, Modifier.weight(1f).onGloballyPositioned { targets.nowPlaying = it.boundsInRoot() }, playerPulse)
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Connection to the car's hotspot (the box's Wi-Fi): three arcs lit by signal strength, accent when
 * online. Faint with a slash when there's no internet.
 */
@Composable
private fun HotspotIcon(c: DriveColors, modifier: Modifier) {
    val net by com.taycanscreen.app.net.NetMonitor.link.collectAsState()
    val bars = when {
        !net.online -> 0
        net.rssi == null -> 3
        net.rssi!! >= -60 -> 3
        net.rssi!! >= -72 -> 2
        else -> 1
    }
    Canvas(modifier) {
        val s = density
        val cx = size.width / 2
        val base = size.height - 2 * s
        val on = if (net.online) c.text else c.textFaint
        val off = c.border
        drawCircle(if (net.online) on else off, 3 * s, Offset(cx, base - 1 * s))
        for (i in 1..3) {
            val r = size.height * (0.30f + 0.27f * (i - 1))
            drawArc(
                if (i <= bars) on else off, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - r, base - r), size = Size(2 * r, 2 * r), style = Stroke(3 * s, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        if (!net.online) drawLine(c.warn, Offset(cx - size.height * 0.55f, 2 * s), Offset(cx + size.height * 0.55f, size.height - 2 * s), 2.5f * s)
    }
}

/** Battery outline with a nub, filled to [pct] in the charge colour. */
@Composable
private fun BatteryIcon(pct: Double?, fill: Color, c: DriveColors, modifier: Modifier) {
    Canvas(modifier) {
        val s = density
        val nub = 5 * s
        val body = Size(size.width - nub - 2 * s, size.height)
        drawRoundRect(c.textDim, size = body, cornerRadius = androidx.compose.ui.geometry.CornerRadius(5 * s), style = Stroke(2 * s))
        drawRoundRect(
            c.textDim, topLeft = Offset(body.width + 2 * s, size.height * 0.3f), size = Size(nub, size.height * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * s),
        )
        if (pct != null) {
            val inset = 5 * s
            val w = (body.width - 2 * inset) * (pct / 100.0).toFloat().coerceIn(0f, 1f)
            drawRoundRect(fill, topLeft = Offset(inset, inset), size = Size(w, body.height - 2 * inset), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * s))
        }
    }
}

/**
 * The big speed. Readings arrive 3 to 4 times a second; the number glides from one to the next (through
 * the whole numbers in between) instead of jumping, like the instrument cluster's. Only this text is
 * redrawn as it moves.
 */
@Composable
private fun SpeedNumber(speed: V?, c: DriveColors, modifier: Modifier) {
    val shown = remember { Animatable(speed?.x?.toFloat() ?: 0f) }
    var had by remember { mutableStateOf(speed != null) }
    LaunchedEffect(speed?.x) {
        val target = speed?.x?.toFloat()
        if (target == null) {
            had = false
            return@LaunchedEffect
        }
        // First value after a gap, or a big step: show it at once rather than counting up to it.
        if (!had || kotlin.math.abs(target - shown.value) > 15f) shown.snapTo(target)
        else shown.animateTo(target, tween(320, easing = LinearEasing))
        had = true
    }
    val text by remember { derivedStateOf { shown.value.roundToInt().toString() } }
    BasicText(
        // No speed yet: a short faint dash (an em dash at this size is a heavy bar).
        if (speed == null) "–" else text,
        modifier,
        style = Filament.num(168.sp, 300, if (speed == null) c.textFaint else c.text).copy(letterSpacing = (-8).sp),
        maxLines = 1,
    )
}

/** One short line under the power bar about the car link; amber only when something needs attention. */
private data class LinkNote(val text: String, val warn: Boolean)

/**
 * What the driver needs to know about the car link, in a few plain words. Connecting is normal and
 * shown calmly; it turns amber only after 30 s of trying. Exception text never reaches this screen
 * (the longer explanation is on the Vehicle screen, the detail in the log).
 */
private fun linkNote(s: com.taycanscreen.app.obd.ObdStatus, carOk: Boolean, now: Long): LinkNote? = when (s.state) {
    LinkState.Live -> if (carOk) null else LinkNote("WAITING FOR THE CAR…", false)
    LinkState.CarAsleep -> LinkNote("CAR OFF", false)
    LinkState.Connecting, LinkState.Initializing, LinkState.Retrying ->
        if (s.downSince != 0L && now - s.downSince > 30_000) LinkNote("CAR LINK NOT ANSWERING · STILL TRYING", true)
        else LinkNote("CONNECTING TO CAR…", false)
    LinkState.BluetoothOff -> LinkNote("BLUETOOTH IS OFF ON THE BOX", true)
    LinkState.NeedsPermission -> LinkNote("ALLOW BLUETOOTH IN SETUP", true)
    LinkState.ChooseAdapter -> LinkNote("CHOOSE THE ADAPTER IN SETUP › VEHICLE", true)
    LinkState.Stopped -> LinkNote("CAR LINK STOPPED", false)
}

/** Centre-zero power line (v3): regen to the left third (−150 kW), drive to the right (300 kW). */
@Composable
private fun PowerLine(kw: V?, c: DriveColors, sweepT: Float?, pulseT: Float?, note: LinkNote?) {
    val drive = kw?.let { -it.x } // positive = drawing power
    // The bar eases between readings instead of stepping.
    val eased by animateFloatAsState((drive ?: 0.0).toFloat(), tween(320, easing = LinearEasing), label = "power")
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
                if (eased >= 0) {
                    val len = (eased / 300f).coerceIn(0f, 1f) * (w - x0)
                    drawRect(c.text.copy(alpha = a), Offset(x0, y - 2 * density), Size(len, 4 * density))
                } else {
                    val len = (-eased / 150f).coerceIn(0f, 1f) * x0
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
        if (note != null) {
            BasicText(note.text, style = Filament.label(12.sp, if (note.warn) c.warn else c.textDim, 0.04f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Now playing, filling the panel under the speed (owner, 2026-11-10): round controls on top, then the
 * cover as a large backdrop with the title and artist over it (one line each, scrolling sideways when
 * too long), and the progress line.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun V3Player(c: DriveColors, now: Long, onOpenMusic: () -> Unit, modifier: Modifier, pulseT: Float?) {
    val status by SpotifyController.status.collectAsState()
    val np by SpotifyController.nowPlaying.collectAsState()
    val art by SpotifyController.artwork.collectAsState()
    val connected = status.link == SpotifyController.Link.Connected
    val flash = pulseT?.let { keys(it, 0f to 0f, 0.05f to 1f, 1.0f to 0f) } ?: 0f
    val track = np
    Column(
        modifier.fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (flash > 0f) drawRect(c.accent.copy(alpha = flash), style = Stroke(2 * density))
            },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(FIcons.Prev, "Previous track", c, 68, false) { SpotifyController.previous() }
            val playing = track?.paused == false
            RoundButton(if (playing) FIcons.Pause else FIcons.Play, if (playing) "Pause" else "Play", c, 80, true) { SpotifyController.togglePlay() }
            RoundButton(FIcons.Next, "Next track", c, 68, false) { SpotifyController.next() }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)).background(c.coverBg).clickable(onClick = onOpenMusic),
        ) {
            val bmp = art
            if (connected && bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Darken the lower part so the white text reads on any cover.
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(0.30f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                    ),
                )
            } else {
                Icon(FIcons.Music, null, tint = c.textFaint, modifier = Modifier.align(Alignment.Center).padding(bottom = 50.dp).size(44.dp))
            }
            val onArt = connected && bmp != null
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().clipToBounds().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    if (connected) np?.title ?: "Nothing playing" else "Spotify",
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = Filament.text(30.sp, 600, if (onArt) Color.White else c.text), maxLines = 1, softWrap = false,
                )
                BasicText(
                    if (connected) np?.artist?.ifBlank { null } ?: "Tap to browse" else status.detail.ifBlank { "Connecting…" },
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = Filament.text(22.sp, 500, if (onArt) Color.White.copy(alpha = 0.8f) else c.textDim), maxLines = 1, softWrap = false,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val pos = if (track == null) 0L else (track.positionMs + if (track.paused) 0L else (now - track.positionAt)).coerceIn(0L, track.durationMs)
        val pct = if (track == null || track.durationMs <= 0) 0f else pos.toFloat() / track.durationMs
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.border)) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().background(c.text))
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
        // Twice as tall as the v3 board's 64 px (owner, 2026-11-09); everything scaled with it.
        BasicText(
            "TRIP ${tripMi?.let { "%.1f".format(it) } ?: NO_DATA} MI",
            Modifier.width(230.dp),
            style = Filament.label(18.sp, c.textFaint, 0.08f),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
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
        // Volume control removed (owner: the box's volume doesn't change the car's).
        Spacer(Modifier.width(230.dp))
    }
}

@Composable
private fun DockIcon(icon: ImageVector, description: String, active: Boolean, c: DriveColors, light: Color? = null, onClick: () -> Unit) {
    Box(Modifier.size(96.dp, 112.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = description, tint = if (active) c.text else c.textFaint, modifier = Modifier.size(46.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent))
        }
        if (light != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 16.dp).size(12.dp).clip(CircleShape).background(light))
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
