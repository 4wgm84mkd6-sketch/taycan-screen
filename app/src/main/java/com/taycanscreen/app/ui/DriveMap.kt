package com.taycanscreen.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import android.os.SystemClock
import android.view.ViewOutlineProvider
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taycanscreen.app.log.AppLog
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Filament map styling (MapStyle board): one 4×5 ColorMatrix per theme over OSM Standard tiles. */
private val NightMapFilter by lazy {
    ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.1914f, -0.4632f, -0.3673f, 0f, 160.47f,
        0.5202f, -0.6820f, -0.6743f, 0f, 222.81f,
        0.6474f, -0.8464f, -0.6799f, 0f, 241.55f,
        0f, 0f, 0f, 1f, 0f,
    )))
}
private val DayMapFilter by lazy {
    ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.8469f, 0.1873f, -0.0075f, 0f, -12.92f,
        0.6097f, 0.2451f, 0.0394f, 0f, 29.10f,
        0.6383f, -0.0369f, 0.2545f, 0f, 43.70f,
        0f, 0f, 0f, 1f, 0f,
    )))
}

/** What the map knows about the car's position, shared with the start-up sequence. */
@Stable
class GpsInfo {
    var fix by mutableStateOf<Location?>(null)
    var usedSats by mutableIntStateOf(0)
    var seenSats by mutableIntStateOf(0)
    /** Direction of travel (degrees from north), once known. */
    var heading by mutableStateOf<Float?>(null)
    var mapOrientation by mutableFloatStateOf(0f)
}

/** Where the start-up rings land, in root px; filled in by the Drive layout. */
@Stable
class DriveTargets {
    var arc by mutableStateOf<Offset?>(null)
    var nowPlaying by mutableStateOf<Rect?>(null)
    var map by mutableStateOf<Rect?>(null)
    var panel by mutableStateOf<Rect?>(null)
    var dock by mutableStateOf<Rect?>(null)
    /** The car marker inside the map view, px. */
    var markerInMap by mutableStateOf(Offset.Zero)
    val marker: Offset? get() = map?.let { it.topLeft + markerInMap }
    /** elapsedRealtime of lock pulses: car link on the arc, Spotify on the card, GPS on the marker. */
    var arcPulseAt by mutableLongStateOf(0L)
    var nowPlayingPulseAt by mutableLongStateOf(0L)
    /** When the map reticle should run its lock-on (cross, heading line, coordinates); 0 = not yet. */
    var acquireAt by mutableLongStateOf(0L)
}

@Composable
fun MapArea(
    c: DriveColors,
    outsideF: V?,
    locationGranted: Boolean,
    gps: GpsInfo,
    targets: DriveTargets,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val designPx = LocalDensity.current.density
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
    var lastTouchAt by remember { mutableLongStateOf(0L) }
    var northUp by remember { mutableStateOf(prefs.getBoolean("north_up", false)) }
    val headingUp = rememberUpdatedState(!northUp)
    val locationOverlay = remember(locationGranted) {
        if (!locationGranted) null
        else CarLocationOverlay(locationProvider(context), mapView, { headingUp.value }, ::carSpeedKmh, { _, loc, bearing ->
            if (gps.fix == null) {
                Boot.mark("First GPS fix")
                // Lock the reticle now if the start-up has already handed off (otherwise it locks on landing).
                if (Boot.handoffAt.value != 0L && SystemClock.elapsedRealtime() > Boot.handoffAt.value + 700) {
                    targets.acquireAt = SystemClock.elapsedRealtime()
                }
            }
            gps.fix = loc
            gps.heading = bearing
        }).apply {
            isDrawAccuracyEnabled = false
            enableMyLocation()
            enableFollowLocation()
            runOnFirstFix {
                myLocation?.let { Sun.latitude = it.latitude; Sun.longitude = it.longitude }
                AppLog.info("map", "GPS fix")
            }
        }
    }
    // The car marker, drawn in design pixels so it matches the panel (re-tinted with the theme).
    LaunchedEffect(locationOverlay, c.accent, c.panel, designPx) {
        locationOverlay ?: return@LaunchedEffect
        val arrow = markerBitmap(designPx, c, withHeading = true)
        val still = markerBitmap(designPx, c, withHeading = false)
        locationOverlay.setPersonIcon(still)
        locationOverlay.setPersonAnchor(0.5f, 0.5f)
        locationOverlay.setDirectionArrow(arrow, arrow)
        locationOverlay.setDirectionAnchor(0.5f, 0.5f)
        mapView.invalidate()
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
    // Where the car marker is on screen (or would be, while GPS is still searching), for the
    // start-up reticle and the lock-on. Cheap: one projection every 100 ms.
    LaunchedEffect(mapView) {
        while (true) {
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            if (w > 0f) {
                val fix = gps.fix
                targets.markerInMap = if (fix != null) {
                    val p = mapView.projection.toPixels(GeoPoint(fix.latitude, fix.longitude), null)
                    Offset(p.x.toFloat(), p.y.toFloat())
                } else {
                    Offset(w / 2f, h / 2f + if (headingUp.value) h * 0.22f else 0f)
                }
                gps.mapOrientation = mapView.mapOrientation
            }
            delay(100)
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
                    if (abs(dy) > 8 * density) moved = true
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
    DisposableEffect(locationGranted) {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        var lastLog = 0L
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val seen = status.satelliteCount
                val used = (0 until seen).count { status.usedInFix(it) }
                val strong = (0 until seen).count { status.getCn0DbHz(it) >= 25f }
                gps.usedSats = used
                gps.seenSats = seen
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

    Box(
        modifier.clipToBounds().background(c.background)
            .onGloballyPositioned { targets.map = it.boundsInRoot() },
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = {
                it.overlayManager.tilesOverlay.setColorFilter(if (c.isNight) NightMapFilter else DayMapFilter)
                it.invalidate()
            },
        )
        // Scan lines at night, the panel's edge shadow, and corner brackets.
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            if (c.isNight) {
                var y = 0f
                val line = Color(0x0996E6FF)
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), u)
                    y += 3 * u
                }
            }
            drawRect(Brush.horizontalGradient(listOf(c.panel, Color.Transparent), 0f, 56 * u), size = androidx.compose.ui.geometry.Size(56 * u, size.height))
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(14 * u, 46 * u); lineTo(14 * u, 14 * u); lineTo(46 * u, 14 * u)
                moveTo(w - 46 * u, 14 * u); lineTo(w - 14 * u, 14 * u); lineTo(w - 14 * u, 46 * u)
                moveTo(w - 14 * u, h - 46 * u); lineTo(w - 14 * u, h - 14 * u); lineTo(w - 46 * u, h - 14 * u)
                moveTo(46 * u, h - 14 * u); lineTo(14 * u, h - 14 * u); lineTo(14 * u, h - 46 * u)
            }
            drawPath(p, c.accent.copy(alpha = 0.55f), style = Stroke(2 * u))
        }

        MarkerLayer(c, gps, targets, locationGranted)

        HeadingTape(gps.heading, c, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))

        // Clock and outside temperature.
        var clock by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                clock++
                delay(10_000)
            }
        }
        val time = remember(clock) { LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm")) }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp).height(56.dp)
                .background(c.overlay, Filament.CutS).insetLine(c.border).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(time, style = Filament.num(28.sp, 600, c.text))
            Box(Modifier.width(1.dp).height(24.dp).background(c.border))
            BasicText(
                "${outsideF?.x?.roundToInt() ?: NO_DATA}°F",
                Modifier.alpha(if (outsideF?.dim == true) 0.45f else 1f),
                style = Filament.num(22.sp, 400, c.textDim),
            )
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 86.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.background(c.overlay, Filament.CutS).insetLine(c.border)) {
                MapButton(FIcons.Plus, "Zoom in", c.text) { mapView.controller.zoomIn() }
                Box(Modifier.padding(horizontal = 18.dp).width(36.dp).height(1.dp).background(c.border))
                MapButton(FIcons.Minus, "Zoom out", c.text) { mapView.controller.zoomOut() }
            }
            Column(
                Modifier.size(72.dp).background(c.overlay, Filament.CutS).insetLine(c.border)
                    .clickable {
                        northUp = !northUp
                        prefs.edit().putBoolean("north_up", northUp).apply()
                        AppLog.info("map", if (northUp) "North up" else "Heading up")
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The compass needle points to north: up in north-up, turned with the map in heading-up.
                Icon(
                    FIcons.Compass, if (northUp) "North up (tap for heading up)" else "Heading up (tap for north up)",
                    tint = c.accent, modifier = Modifier.size(26.dp).rotate(if (northUp) 0f else gps.mapOrientation),
                )
                BasicText(if (northUp) "N UP" else "HDG", Modifier.padding(top = 2.dp), style = Filament.label(12.sp, c.textDim, 0.08f))
            }
            Box(Modifier.background(c.overlay, Filament.CutS).insetLine(c.border)) {
                MapButton(FIcons.Recenter, "Recenter", c.text) {
                    locationOverlay?.let { o ->
                        o.enableFollowLocation()
                        o.myLocation?.let { mapView.controller.animateTo(it) }
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp).height(40.dp)
                .background(c.overlay, Filament.CutS).insetLine(c.border).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(FIcons.Gps, null, tint = if (gps.fix != null) c.accent else c.textFaint, modifier = Modifier.size(18.dp))
            BasicText(
                when {
                    !locationGranted -> "GPS · NO PERMISSION"
                    gps.fix == null -> "GPS SEARCHING"
                    else -> "GPS ±${gps.fix?.accuracy?.roundToInt() ?: 0} m"
                },
                style = Filament.label(14.sp, c.textDim, 0f),
            )
            Box(Modifier.width(1.dp).height(16.dp).background(c.border))
            BasicText("${gps.usedSats}/${gps.seenSats} SAT", style = Filament.label(14.sp, c.textDim, 0f))
        }

        BasicText(
            "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp)
                .background(c.overlay).padding(horizontal = 6.dp, vertical = 2.dp),
            style = Filament.text(11.sp, 400, c.textFaint),
        )
        if (!locationGranted) {
            BasicText(
                "Allow location to show where you are",
                Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 24.dp)
                    .background(c.overlay, Filament.CutS).padding(horizontal = 14.dp, vertical = 10.dp),
                style = Filament.text(16.sp, 500, c.text),
            )
        }
    }
}

/** 1 px inset outline following the cut-s shape, like the design's "box-shadow: inset 0 0 0 1px". */
fun Modifier.insetLine(color: Color) = this.drawWithContent {
    drawContent()
    val s = density
    val cut = 8 * s
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(cut, s / 2); lineTo(w - s / 2, s / 2); lineTo(w - s / 2, h - cut)
        lineTo(w - cut, h - s / 2); lineTo(s / 2, h - s / 2); lineTo(s / 2, cut); close()
    }
    drawPath(p, color, style = Stroke(s))
}

/**
 * The SEARCHING reticle while there's no fix, and the lock-on when a fix lands: brackets snap in,
 * a heading line, and the live coordinates (Filament start-up v2).
 */
@Composable
private fun MarkerLayer(c: DriveColors, gps: GpsInfo, targets: DriveTargets, locationGranted: Boolean) {
    val spin by rememberInfiniteTransition(label = "reticle").animateFloat(
        0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin",
    )
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    val handoff by Boot.handoffAt.collectAsState()
    // Seconds since the lock-on started (null outside it).
    var acquireT by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(targets.acquireAt) {
        val start = targets.acquireAt
        if (start == 0L) return@LaunchedEffect
        while (true) {
            val t = (SystemClock.elapsedRealtime() - start) / 1000f
            if (t > 3.2f) break
            acquireT = t.coerceAtLeast(0f)
            withFrameNanos { }
        }
        acquireT = null
    }
    val searching = locationGranted && gps.fix == null && handoff != 0L
    if (!searching && acquireT == null) return
    val fix = gps.fix
    Box(
        Modifier.size(200.dp).offset {
            val m = targets.markerInMap
            IntOffset((m.x - 100 * density).roundToInt(), (m.y - 100 * density).roundToInt())
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val ctr = Offset(100 * u, 100 * u)
            if (searching) {
                rotateAround(spin, ctr) {
                    drawCircle(c.accent.copy(alpha = 0.55f), 34 * u, ctr, style = Stroke(1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 6 * u))))
                }
                drawCircle(c.panel.copy(alpha = 0.75f), 20 * u, ctr)
                drawCircle(c.textFaint, 20 * u, ctr, style = Stroke(1.5f * u))
                drawBrackets(c.accent.copy(alpha = 0.35f * blink), u)
            }
            val t = acquireT
            if (t != null && fix != null) {
                val op = keys(t, 0f to 0f, 0.2f to 1f, 1.8f to 1f, 2.1f to 0f)
                val sc = keys(t, 0f to 1.6f, 0.2f to 1f)
                scaleAround(sc, ctr) {
                    drawCross(c.accent.copy(alpha = op), u)
                    drawBrackets(c.accent.copy(alpha = 0.6f * op), u)
                }
                // Heading line from the marker in the direction of travel.
                val hdg = gps.heading
                if (hdg != null) {
                    val screenDeg = hdg + gps.mapOrientation
                    val a = Math.toRadians(screenDeg.toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val len = keys(t, 0.15f to 0f, 0.45f to 1f)
                    drawLine(
                        Brush.linearGradient(listOf(c.accent.copy(alpha = op), Color.Transparent), ctr + dir * (36 * u), ctr + dir * (36 * u + 120 * u * len)),
                        ctr + dir * (36 * u), ctr + dir * (36 * u + 120 * u * len), 2 * u,
                    )
                }
            }
        }
        if (searching) {
            Column(Modifier.offset(x = 155.dp, y = 88.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("SEARCHING", Modifier.alpha(blink), style = Filament.label(13.sp, c.accent, 0.14f))
                BasicText("GPS · ${gps.usedSats}/${gps.seenSats} SAT", style = Filament.label(13.sp, c.textDim, 0.14f))
            }
        }
        val t = acquireT
        if (t != null && fix != null) {
            val op = keys(t, 0.1f to 0f, 0.3f to 1f, 2.6f to 1f, 3.0f to 0f)
            Column(Modifier.offset(x = 155.dp, y = 112.dp).alpha(op), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("POSITION LOCKED", style = Filament.label(13.sp, c.accent, 0.14f))
                BasicText(formatCoords(fix), style = Filament.label(13.sp, c.textDim, 0.14f))
            }
        }
    }
}

fun formatCoords(l: Location): String =
    "%.4f° %s · %.4f° %s".format(abs(l.latitude), if (l.latitude >= 0) "N" else "S", abs(l.longitude), if (l.longitude >= 0) "E" else "W")

private fun DrawScope.drawCross(color: Color, u: Float) {
    val p = Path().apply {
        moveTo(100 * u, 18 * u); lineTo(100 * u, 44 * u)
        moveTo(100 * u, 156 * u); lineTo(100 * u, 182 * u)
        moveTo(18 * u, 100 * u); lineTo(44 * u, 100 * u)
        moveTo(156 * u, 100 * u); lineTo(182 * u, 100 * u)
    }
    drawPath(p, color, style = Stroke(1.5f * u))
}

private fun DrawScope.drawBrackets(color: Color, u: Float) {
    val p = Path().apply {
        moveTo(58 * u, 48 * u); lineTo(48 * u, 48 * u); lineTo(48 * u, 58 * u)
        moveTo(142 * u, 48 * u); lineTo(152 * u, 48 * u); lineTo(152 * u, 58 * u)
        moveTo(58 * u, 152 * u); lineTo(48 * u, 152 * u); lineTo(48 * u, 142 * u)
        moveTo(142 * u, 152 * u); lineTo(152 * u, 152 * u); lineTo(152 * u, 142 * u)
    }
    drawPath(p, color, style = Stroke(1.5f * u))
}

private inline fun DrawScope.rotateAround(deg: Float, pivot: Offset, block: DrawScope.() -> Unit) =
    drawRotate(deg, pivot, block)

private inline fun DrawScope.scaleAround(s: Float, pivot: Offset, block: DrawScope.() -> Unit) =
    drawScale(s, s, pivot, block)

/** Compass tape across the top of the map: heading readout and a ±50° strip (Filament Main). */
@Composable
private fun HeadingTape(heading: Float?, c: DriveColors, modifier: Modifier) {
    val h = ((heading ?: 0f).roundToInt() % 360 + 360) % 360
    val known = heading != null
    Column(modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.height(32.dp).background(c.overlay, Filament.CutS).insetLine(c.border).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                if (known) "%03d°".format(h) else "$NO_DATA°",
                style = Filament.num(22.sp, 600, c.accent, if (c.glow > 0f) c.accent.copy(alpha = 0.5f) else null),
            )
            BasicText(if (known) C16[((h / 22.5f).roundToInt()) % 16] else "HDG", style = Filament.label(13.sp, c.textDim, 0.14f))
        }
        Box(
            Modifier.width(440.dp).height(36.dp)
                .graphicsLayer(alpha = if (known) 1f else 0.5f, compositingStrategy = CompositingStrategy.Offscreen)
                .background(c.overlay, Filament.CutS),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val u = density
                var d = ceil((h - 50) / 5f).toInt() * 5
                while (d <= h + 50) {
                    val x = (220 + (d - h) * 4.4f) * u
                    val dn = ((d % 360) + 360) % 360
                    drawLine(c.lineStrong, Offset(x, 0f), Offset(x, (if (dn % 15 == 0) 12 else 6) * u), 1.5f * u)
                    d += 5
                }
                drawPath(Path().apply { moveTo(213 * u, 0f); lineTo(227 * u, 0f); lineTo(220 * u, 8 * u); close() }, c.accent)
                // Fade both ends (design: mask-image linear-gradient 0 → 14% → 86% → 0).
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            var d = ceil((h - 50) / 5f).toInt() * 5
            while (d <= h + 50) {
                val x = 220 + (d - h) * 4.4f
                val dn = ((d % 360) + 360) % 360
                if (dn % 30 == 0 && x > 16 && x < 424) {
                    val card = mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")[dn]
                    BasicText(
                        card ?: "%03d".format(dn),
                        Modifier.centerAt(x.dp, 22.dp),
                        style = Filament.label(13.sp, if (card != null) c.accent else c.textDim, 0f),
                    )
                }
                d += 5
            }
        }
    }
}

private val C16 = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

@Composable
private fun MapButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
    }
}

/** The car's speed from OBD (km/h), if fresh. */
private fun carSpeedKmh(): Double? {
    val r = com.taycanscreen.app.obd.ObdEngine.readings.value[Taycan.SPEED.id] ?: return null
    return r.value?.takeIf { System.currentTimeMillis() - r.at < Taycan.SPEED.staleMs }
}

/** Follows the car and, in heading-up mode, turns the map so the direction of travel points up. */
private class CarLocationOverlay(
    provider: GpsMyLocationProvider,
    private val map: MapView,
    private val headingUp: () -> Boolean,
    private val carSpeedKmh: () -> Double?,
    private val onFix: (status: String, location: Location, bearing: Float?) -> Unit,
) : MyLocationNewOverlay(provider, map) {
    private var lastLogAt = 0L
    private var lastPoint: Location? = null
    private var lastBearing: Float? = null
    private var lastBearingSource = ""

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        if (location == null) {
            super.onLocationChanged(location, source)
            return
        }
        // Direction of travel. The car's GPS (passed through the box) may come without a bearing or
        // speed, so fall back to the direction between fixes, and use the car's own speed (OBD).
        val obd = carSpeedKmh()
        val moving = (location.hasSpeed() && location.speed > 1.5f) || (obd != null && obd > 5.0)
        val prev = lastPoint
        val gpsBearing = if (location.hasBearing() && (location.bearing != 0f || location.hasSpeed())) location.bearing else null
        val bearing: Float? = when {
            !moving -> null
            gpsBearing != null -> gpsBearing.also { lastBearingSource = "GPS" }
            prev != null && prev.distanceTo(location) >= 6f -> ((prev.bearingTo(location) + 360f) % 360f).also { lastBearingSource = "between fixes" }
            else -> null
        }
        if (prev == null || prev.distanceTo(location) >= 6f || !moving) lastPoint = Location(location)
        if (bearing != null) lastBearing = bearing
        // Let the arrow point the right way too (osmdroid draws it from the location's bearing).
        lastBearing?.let { location.bearing = it }
        super.onLocationChanged(location, source)
        Sun.latitude = location.latitude
        Sun.longitude = location.longitude
        val status = "GPS ±%d m · %s".format(location.accuracy.roundToInt(), location.provider ?: "?")
        val copy = Location(location)
        val heading = lastBearing
        map.post { onFix(status, copy, heading) }
        val now = System.currentTimeMillis()
        if (now - lastLogAt > 30_000) {
            lastLogAt = now
            AppLog.info(
                "map",
                "Fix from ${location.provider}: ±${location.accuracy.roundToInt()} m, " +
                    "${"%.1f".format(location.speed * 2.23694)} mph (car ${obd?.let { "%.0f".format(it * 0.621371) } ?: "?"} mph), " +
                    "bearing ${lastBearing?.roundToInt() ?: -1} from ${lastBearingSource.ifBlank { "none yet" }}",
            )
        }
        // Keep the last orientation when stopped.
        if (headingUp() && moving && bearing != null) {
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

/**
 * Filament car marker (Main board, 160×160 design px): heading cone, dashed ring, disc and arrow.
 * [px] is screen px per design px. Without heading: ring and disc with a small diamond.
 */
private fun markerBitmap(px: Float, c: DriveColors, withHeading: Boolean): Bitmap {
    val size = (160 * px).roundToInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    cv.scale(px, px)
    val accent = c.accent.toArgb()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (withHeading) {
        paint.style = Paint.Style.FILL
        paint.color = c.accent.copy(alpha = 0.14f).toArgb()
        val cone = android.graphics.Path().apply {
            moveTo(80f, 80f); lineTo(54.3f, 9.5f)
            arcTo(android.graphics.RectF(5f, 5f, 155f, 155f), -110f, 40f)
            close()
        }
        cv.drawPath(cone, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f
    paint.color = c.accent.copy(alpha = 0.55f).toArgb()
    paint.pathEffect = DashPathEffect(floatArrayOf(2f, 6f), 0f)
    cv.drawCircle(80f, 80f, 34f, paint)
    paint.pathEffect = null
    paint.style = Paint.Style.FILL
    paint.color = c.panel.copy(alpha = 0.75f).toArgb()
    cv.drawCircle(80f, 80f, 20f, paint)
    paint.style = Paint.Style.STROKE
    paint.color = accent
    cv.drawCircle(80f, 80f, 20f, paint)
    paint.style = Paint.Style.FILL
    if (withHeading) {
        val arrow = android.graphics.Path().apply {
            moveTo(80f, 65f); lineTo(91f, 92f); lineTo(80f, 86f); lineTo(69f, 92f); close()
        }
        cv.drawPath(arrow, paint)
    } else {
        val dot = android.graphics.Path().apply {
            moveTo(80f, 73f); lineTo(87f, 80f); lineTo(80f, 87f); lineTo(73f, 80f); close()
        }
        cv.drawPath(dot, paint)
    }
    return bmp
}
