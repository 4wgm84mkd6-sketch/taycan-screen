package com.taycanscreen.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.location.Location
import android.media.AudioManager
import android.view.ViewOutlineProvider
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdViewModel
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Signal
import com.taycanscreen.app.obd.Taycan
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DASH = "—"

/** Dark map that keeps hues (water stays blue, parks green): invert, rotate hue 180°, dim slightly. */
private val NightMapFilter: ColorMatrixColorFilter by lazy {
    val m = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    m.postConcat(ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    m.postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.9f, 1f) })
    ColorMatrixColorFilter(m)
}
private val PanelWidth = 300.dp
private val DockHeight = 64.dp

private fun Map<String, Reading>.fresh(sig: Signal, now: Long): Double? =
    this[sig.id]?.takeIf { now - it.at <= sig.staleMs }?.value

private fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0

/**
 * Phase 1 Drive screen, after the "Drive — day / night" artboards of the Taycan Center Screen
 * Concept: vehicle panel on the left, map on the right, app dock along the bottom.
 * Every number comes from the car (OBD) or the box; anything unavailable shows "—".
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
    val status by obd.status.collectAsState()
    val readings by obd.readings.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val v = VehicleValues.from(readings, now)
    val context = LocalContext.current
    // Which panel covers the map: null (map), "music" or "audio".
    var panel by remember { mutableStateOf<String?>(null) }
    val requested by UiEvents.openPanel.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { panel = it; UiEvents.openPanel.value = null }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            VehiclePanel(v, status.state, status.detail, colors, { panel = "music" }, Modifier.width(PanelWidth).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            // The map keeps running underneath while Music is open, so it's current when you close it.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                MapArea(v, colors, locationGranted, Modifier.fillMaxSize())
                when (panel) {
                    "music" -> MusicPanel(
                        colors,
                        onClose = { panel = null },
                        onOpenSpotifyApp = { launchFirst(context, "Spotify", "com.spotify.music") },
                        modifier = Modifier.fillMaxSize(),
                    )
                    "audio" -> AudioPanel(colors, onClose = { panel = null }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Dock(
            colors, themeMode, onCycleTheme, onOpenVehicle, onOpenSetup,
            onOpenMusic = { panel = if (panel == "music") null else "music" },
            onOpenAudio = { panel = if (panel == "audio") null else "audio" },
            modifier = Modifier.fillMaxWidth().height(DockHeight),
        )
    }
}

/** The numbers the Drive screen shows, already filtered for freshness. */
private data class VehicleValues(
    val soc: Double?,
    val speedMph: Double?,
    val rangeMi: Double?,
    val outsideF: Double?,
    val volts: Double?,
    val kw: Double?,
    val packMinF: Double?,
    val packMaxF: Double?,
    val gear: String?,
) {
    companion object {
        fun from(r: Map<String, Reading>, now: Long): VehicleValues {
            val volts = r.fresh(Taycan.HV_VOLTAGE, now)?.takeIf { it > 0 }
                ?: r.fresh(Taycan.HV_VOLTAGE_1818, now)?.takeIf { it > 0 }
            val amps = r.fresh(Taycan.HV_CURRENT, now)
            return VehicleValues(
                soc = r.fresh(Taycan.SOC_DISPLAY, now),
                speedMph = r.fresh(Taycan.SPEED, now)?.let { it * 0.621371 },
                rangeMi = r.fresh(Taycan.RANGE, now),
                outsideF = r.fresh(Taycan.AMBIENT, now)?.let(::cToF),
                volts = volts,
                kw = if (volts != null && amps != null) volts * amps / 1000.0 else null,
                packMinF = r.fresh(Taycan.PACK_T_MIN, now)?.let(::cToF),
                packMaxF = r.fresh(Taycan.PACK_T_MAX, now)?.let(::cToF),
                gear = Taycan.gearLetter(r.fresh(Taycan.GEAR, now)),
            )
        }
    }
}

@Composable
private fun VehiclePanel(v: VehicleValues, link: LinkState, detail: String, c: DriveColors, onOpenMusic: () -> Unit, modifier: Modifier) {
    Column(
        modifier.background(c.panel).padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val live = link == LinkState.Live
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) c.good else c.warn))
            Spacer(Modifier.width(12.dp))
            // Gear row as in the design: current gear bold, the others faint.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("P", "R", "N", "D").forEach { g ->
                    val current = g == v.gear
                    Text(
                        g,
                        color = if (current) c.text else c.textFaint.copy(alpha = 0.55f),
                        fontSize = if (current) 20.sp else 17.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryGlyph(v.soc, c)
                    Text(
                        " ${v.soc?.roundToInt() ?: DASH}%",
                        color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${v.rangeMi?.roundToInt() ?: DASH} mi",
                    color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
                )
            }
        }
        if (link != LinkState.Live && detail.isNotBlank()) {
            Text(detail, color = c.warn, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Column {
            Text(
                v.speedMph?.roundToInt()?.toString() ?: DASH,
                color = c.text, fontSize = 116.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 108.sp, letterSpacing = (-0.045).em, maxLines = 1,
            )
            Text("MPH", color = c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em)
        }

        Text(
            when {
                v.kw == null -> "$DASH kW"
                v.kw < -0.5 -> "Using %.1f kW".format(-v.kw)
                v.kw > 0.5 -> "Charging / regen %.1f kW".format(v.kw)
                else -> "Idle"
            },
            color = c.textDim, fontSize = 15.sp,
        )

        Spacer(Modifier.weight(1f))
        NowPlayingCard(c, onOpenMusic)
    }
}

/** Battery outline filled in proportion to charge: green, amber at 20 % or below. */
@Composable
private fun BatteryGlyph(soc: Double?, c: DriveColors) {
    Canvas(Modifier.size(width = 32.dp, height = 17.dp)) {
        val stroke = 1.8.dp.toPx()
        val nubW = 2.6.dp.toPx()
        val bodyW = size.width - nubW - 1.dp.toPx()
        drawRoundRect(
            color = c.text, topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(3.5.dp.toPx()), style = Stroke(stroke),
        )
        drawRoundRect(
            color = c.text, topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.32f),
            size = Size(nubW, size.height * 0.36f), cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (soc != null) {
            val inset = stroke + 1.6.dp.toPx()
            val frac = (soc / 100.0).coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(
                color = if (soc <= 20) c.warn else c.good,
                topLeft = Offset(inset, inset),
                size = Size((bodyW - 2 * inset) * frac, size.height - 2 * inset),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

/** Where the design has "Now playing": live battery power (media integration comes later). */
@Composable
private fun PowerCard(kw: Double?, c: DriveColors) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.chip).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val label = when {
            kw == null -> "Power"
            kw < -0.5 -> "Using"
            kw > 0.5 -> "Charging / regen"
            else -> "Idle"
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = c.textDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(kw?.let { "%.1f".format(abs(it)) } ?: DASH, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(" kW", color = c.textDim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        // Centre-zero bar: drawing power fills to the right, charging/regen to the left (±200 kW).
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.track)) {
            val half = maxWidth / 2
            if (kw != null) {
                val frac = (abs(kw) / 200.0).coerceIn(0.0, 1.0).toFloat()
                val w: Dp = half * frac
                val x: Dp = if (kw < 0) half else half - w
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().background(if (kw < 0) c.accent else c.good))
            }
            Box(Modifier.offset(x = half - 1.dp).width(2.dp).fillMaxHeight().background(c.textFaint))
        }
    }
}

@Composable
private fun MapArea(v: VehicleValues, c: DriveColors, locationGranted: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
        }
        MapView(context).apply {
            // Keep the map inside its own area (it pans by scrolling and otherwise spills over the panel).
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(21.2969, -157.8171)) // Honolulu until GPS reports in
        }
    }
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var gps by remember { mutableStateOf<String?>(null) }
    var lastTouchAt by remember { mutableLongStateOf(0L) }
    var northUp by remember { mutableStateOf(prefs.getBoolean("north_up", false)) }
    val headingUp = rememberUpdatedState(!northUp)
    val locationOverlay = remember(locationGranted) {
        if (!locationGranted) null
        else CarLocationOverlay(locationProvider(context), mapView, { headingUp.value }) { gps = it }.apply {
            val arrow = carArrowBitmap(context)
            setPersonIcon(arrow)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionArrow(arrow, arrow)
            setDirectionAnchor(0.5f, 0.5f)
            isDrawAccuracyEnabled = false
            enableMyLocation()
            enableFollowLocation()
            runOnFirstFix {
                myLocation?.let { Sun.latitude = it.latitude; Sun.longitude = it.longitude }
                AppLog.info("map", "GPS fix")
            }
        }
    }
    // Heading-up: car sits lower on screen so more road ahead is visible. North-up: centred, north at top.
    LaunchedEffect(northUp) {
        if (northUp) mapView.mapOrientation = 0f
        applyCenterOffset(mapView, northUp)
    }
    DisposableEffect(mapView) {
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCenterOffset(mapView, !headingUp.value)
        }
        mapView.addOnLayoutChangeListener(listener)
        onDispose { mapView.removeOnLayoutChangeListener(listener) }
    }
    DisposableEffect(locationOverlay) {
        locationOverlay?.let { mapView.overlays.add(it) }
        onDispose {
            locationOverlay?.let {
                it.disableMyLocation()
                mapView.overlays.remove(it)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }

    // Touching the map stops "follow my position"; resume it 10 s after the last touch, like car navs do.
    LaunchedEffect(locationOverlay) {
        while (locationOverlay != null) {
            delay(2_000)
            if (!locationOverlay.isFollowLocationEnabled && System.currentTimeMillis() - lastTouchAt > 10_000) {
                locationOverlay.enableFollowLocation()
                AppLog.info("map", "Following the car again")
            }
        }
    }
    // The car passes only ONE touch point to the box (logs: every gesture had 1 finger), so pinch
    // can't work. One-finger zoom instead, like Google Maps: double-tap and slide (down = in, up = out);
    // a plain double-tap zooms in one step. Finger counts are still logged.
    DisposableEffect(mapView) {
        val density = context.resources.displayMetrics.density
        var maxFingers = 0
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var lastTapUpAt = 0L
        var lastTapX = 0f
        var lastTapY = 0f
        var zoomDrag = false
        var moved = false
        var startZoom = 0.0
        mapView.setOnTouchListener { _, e ->
            lastTouchAt = System.currentTimeMillis()
            maxFingers = maxOf(maxFingers, e.pointerCount)
            var consumed = false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downAt = e.eventTime
                    downX = e.x
                    downY = e.y
                    val near = kotlin.math.hypot(e.x - lastTapX, e.y - lastTapY) < 60 * density
                    if (e.eventTime - lastTapUpAt < 350 && near) {
                        zoomDrag = true
                        moved = false
                        startZoom = mapView.zoomLevelDouble
                        consumed = true
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> if (zoomDrag) {
                    val dy = e.y - downY
                    if (kotlin.math.abs(dy) > 8 * density) moved = true
                    if (moved) {
                        val z = (startZoom + dy / (120 * density)).coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel)
                        mapView.controller.setZoom(z)
                    }
                    consumed = true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (zoomDrag) {
                        if (!moved && e.actionMasked == android.view.MotionEvent.ACTION_UP) mapView.controller.zoomIn()
                        AppLog.info("map", if (moved) "One-finger zoom to %.1f".format(mapView.zoomLevelDouble) else "Double-tap zoom in")
                        zoomDrag = false
                        lastTapUpAt = 0L
                        consumed = true
                    } else {
                        val isTap = e.eventTime - downAt < 250 && kotlin.math.hypot(e.x - downX, e.y - downY) < 20 * density
                        if (isTap) {
                            lastTapUpAt = e.eventTime
                            lastTapX = e.x
                            lastTapY = e.y
                        } else {
                            lastTapUpAt = 0L
                        }
                    }
                    AppLog.info("map", "Touch gesture with up to $maxFingers finger(s)")
                    maxFingers = 0
                }
            }
            consumed
        }
        onDispose { mapView.setOnTouchListener(null) }
    }
    // Satellite count from the box's GPS receiver, to tell "no signal" apart from "no GPS at all".
    var sats by remember { mutableStateOf<String?>(null) }
    DisposableEffect(locationGranted) {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        var lastLog = 0L
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val seen = status.satelliteCount
                val used = (0 until seen).count { status.usedInFix(it) }
                val strong = (0 until seen).count { status.getCn0DbHz(it) >= 25f }
                sats = "$used used / $seen seen"
                val now = System.currentTimeMillis()
                if (now - lastLog > 30_000) {
                    lastLog = now
                    AppLog.info("map", "Satellites: $seen seen, $strong strong (≥25 dB-Hz), $used used in fix")
                }
            }
        }
        if (locationGranted && lm != null) {
            try {
                lm.registerGnssStatusCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: SecurityException) {
                AppLog.warn("map", "No satellite status: ${e.message}")
            }
        }
        onDispose { lm?.unregisterGnssStatusCallback(cb) }
    }
    // Keep the sun calculation's position current while driving.
    LaunchedEffect(locationOverlay) {
        while (locationOverlay != null) {
            locationOverlay.myLocation?.let { Sun.latitude = it.latitude; Sun.longitude = it.longitude }
            delay(60_000)
        }
    }

    Box(modifier.clipToBounds().background(c.background)) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = {
                it.overlayManager.tilesOverlay.setColorFilter(if (c.isNight) NightMapFilter else null)
                it.invalidate()
            },
        )

        // Clock and outside temperature (design: top-right pill).
        var clock by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                clock++
                delay(10_000)
            }
        }
        val time = remember(clock) { LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm")) }
        Row(
            Modifier.align(Alignment.TopEnd).padding(12.dp).shadow(6.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)).background(c.panel).height(42.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(time, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${v.outsideF?.roundToInt() ?: DASH}°F", color = c.textDim, fontSize = 16.sp)
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(DesignIcons.Plus, "Zoom in", c, c.text) { mapView.controller.zoomIn() }
            MapButton(DesignIcons.Minus, "Zoom out", c, c.text) { mapView.controller.zoomOut() }
            MapButton(
                if (northUp) DesignIcons.NorthUp else DesignIcons.HeadingUp,
                if (northUp) "North up (tap for heading up)" else "Heading up (tap for north up)",
                c, c.text,
            ) {
                northUp = !northUp
                prefs.edit().putBoolean("north_up", northUp).apply()
                AppLog.info("map", if (northUp) "North up" else "Heading up")
            }
            MapButton(DesignIcons.Recenter, "Recenter", c, c.accent) {
                locationOverlay?.let { o ->
                    o.enableFollowLocation()
                    o.myLocation?.let { mapView.controller.animateTo(it) }
                }
            }
        }

        Text(
            if (!locationGranted) "GPS: no permission"
            else (gps ?: "GPS: searching…") + (sats?.let { " · $it satellites" } ?: ""),
            color = c.textDim, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .clip(RoundedCornerShape(6.dp)).background(c.panel.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (!locationGranted) {
            Text(
                "Allow location to show where you are",
                color = c.text, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .clip(RoundedCornerShape(14.dp)).background(c.panel).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Text(
            "© OpenStreetMap contributors",
            color = c.textDim, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                .clip(RoundedCornerShape(6.dp)).background(c.panel.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Follows the car and, in heading-up mode, turns the map so the direction of travel points up. */
private class CarLocationOverlay(
    provider: GpsMyLocationProvider,
    private val map: MapView,
    private val headingUp: () -> Boolean,
    private val onStatus: (String) -> Unit,
) : MyLocationNewOverlay(provider, map) {
    private var lastLogAt = 0L

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        super.onLocationChanged(location, source)
        location ?: return
        Sun.latitude = location.latitude
        Sun.longitude = location.longitude
        val status = "GPS ±%d m · %s".format(location.accuracy.roundToInt(), location.provider ?: "?")
        map.post { onStatus(status) }
        val now = System.currentTimeMillis()
        if (now - lastLogAt > 30_000) {
            lastLogAt = now
            AppLog.info(
                "map",
                "Fix from ${location.provider}: ±${location.accuracy.roundToInt()} m, " +
                    "${"%.1f".format(location.speed * 2.23694)} mph, bearing ${if (location.hasBearing()) location.bearing.roundToInt() else -1}",
            )
        }
        // GPS bearing is only meaningful while moving; keep the last orientation when stopped.
        if (headingUp() && location.hasBearing() && location.speed > 1.5f) {
            val bearing = location.bearing
            map.post { map.mapOrientation = -bearing }
        }
    }
}

/** Every location source the box offers (GPS, network, and the fused provider on Android 12+). */
private fun locationProvider(context: Context): GpsMyLocationProvider =
    GpsMyLocationProvider(context).apply {
        clearLocationSources()
        addLocationSource(android.location.LocationManager.GPS_PROVIDER)
        addLocationSource(android.location.LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            addLocationSource(android.location.LocationManager.FUSED_PROVIDER)
        }
        locationUpdateMinTime = 1_000
        locationUpdateMinDistance = 0f
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        AppLog.info(
            "map",
            "Location providers on the box: ${lm?.allProviders} enabled: ${lm?.getProviders(true)} " +
                "(location ${if (lm?.isLocationEnabled == true) "on" else "OFF"})",
        )
    }

private fun applyCenterOffset(map: MapView, northUp: Boolean) {
    map.setMapCenterOffset(0, if (northUp) 0 else (map.height * 0.22f).toInt())
}

/** Blue disc with a white arrow (design accent), used for the car's position. */
private fun carArrowBitmap(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (40 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    paint.color = 0x403E6AE1
    canvas.drawCircle(cx, cx, size / 2f, paint)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cx, size * 0.30f, paint)
    paint.color = 0xFF3E6AE1.toInt()
    canvas.drawCircle(cx, cx, size * 0.25f, paint)
    val arrow = android.graphics.Path().apply {
        moveTo(cx, cx - size * 0.16f)
        lineTo(cx + size * 0.11f, cx + size * 0.13f)
        lineTo(cx, cx + size * 0.06f)
        lineTo(cx - size * 0.11f, cx + size * 0.13f)
        close()
    }
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawPath(arrow, paint)
    return bmp
}

@Composable
private fun MapButton(icon: ImageVector, label: String, c: DriveColors, tint: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = c.panel,
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Dock(
    c: DriveColors,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenAudio: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier.background(c.panel).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(DesignIcons.Vehicle, "Vehicle details", c, onClick = onOpenVehicle)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockButton(DesignIcons.Navigate, "Navigate", c, tint = c.accent) {
                launchFirst(context, "Google Maps", "com.google.android.apps.maps", "com.waze")
            }
            DockButton(DesignIcons.Media, "Music", c, onClick = onOpenMusic)
            DockButton(DesignIcons.Equalizer, "Audio and equalizer", c, onClick = onOpenAudio)
            val themeIcon = when (themeMode) {
                ThemeMode.Auto -> DesignIcons.Auto
                ThemeMode.Day -> DesignIcons.Sun
                ThemeMode.Night -> DesignIcons.Moon
            }
            DockButton(themeIcon, "Day/night: ${themeMode.label()}", c, label = themeMode.label(), onClick = onCycleTheme)
            DockButton(DesignIcons.Settings, "Setup", c, onClick = onOpenSetup)
        }
        Spacer(Modifier.weight(1f))
        VolumeControl(c)
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    description: String,
    c: DriveColors,
    tint: Color = c.text,
    label: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.height(52.dp).widthIn(min = 52.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(26.dp))
            if (label != null) Text("  $label", color = c.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun VolumeControl(c: DriveColors) {
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        DockButton(DesignIcons.ChevronLeft, "Volume down", c) { adjust(AudioManager.ADJUST_LOWER) }
        Row(
            Modifier.widthIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(DesignIcons.Volume, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
            Text(" $vol", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        DockButton(DesignIcons.ChevronRight, "Volume up", c) { adjust(AudioManager.ADJUST_RAISE) }
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
