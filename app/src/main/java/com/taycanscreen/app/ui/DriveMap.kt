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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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

/**
 * Map tiles: OpenStreetMap's own (no key needed), tinted to the v3 look. CARTO's @2x tiles (builds
 * 144–149) now answer "API KEY REQUIRED" (seen in the owner's 2026-11-09 screenshot), and no key may
 * ship in the APK.
 *  - Day: calm and light (70 % desaturated, slightly lifted), close to the v3 day board.
 *  - Night: Filament's OSM night matrix (labels light, arterials lit), then 70 % desaturated to v3 grey.
 */
private val DayTiles get() = TileSourceFactory.MAPNIK
private val NightTiles get() = TileSourceFactory.MAPNIK
private val DayFilter by lazy {
    val m = ColorMatrix().apply { setSaturation(0.3f) }
    m.postConcat(ColorMatrix(floatArrayOf(
        0.93f, 0f, 0f, 0f, 14f,
        0f, 0.93f, 0f, 0f, 13f,
        0f, 0f, 0.93f, 0f, 11f,
        0f, 0f, 0f, 1f, 0f,
    )))
    ColorMatrixColorFilter(m)
}
private val NightTint by lazy {
    val m = ColorMatrix(floatArrayOf(
        0.1914f, -0.4632f, -0.3673f, 0f, 160.47f,
        0.5202f, -0.6820f, -0.6743f, 0f, 222.81f,
        0.6474f, -0.8464f, -0.6799f, 0f, 241.55f,
        0f, 0f, 0f, 1f, 0f,
    ))
    m.postConcat(ColorMatrix().apply { setSaturation(0.3f) })
    ColorMatrixColorFilter(m)
}

/**
 * Satellite (owner, 2026-11-09): Esri World Imagery, free without an account for personal use when
 * credited ("Imagery © Esri, Maxar, Earthstar Geographics"). Esri's URL order is z/y/x.
 */
private class EsriImagery : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Imagery © Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
            org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
}
private val SatelliteTiles by lazy { EsriImagery() }
/** Street and place names over the imagery: Esri's free reference layer (white text, dark halo). */
private class EsriReference : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
    "EsriWorldPlaces", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/"),
    "Labels © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
            org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
}
private val SatelliteLabels by lazy { EsriReference() }
/** Imagery is bright; dim it at night so it doesn't glare. */
private val SatelliteNight by lazy {
    ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.58f, 0.62f, 1f) })
}

/**
 * Map size (owner, 2026-11-09: Porsche's map reads better): tile scale on top of the screen density.
 * OSM tiles are 256 px, so bigger is softer; L is the readability/sharpness compromise.
 */
private enum class MapSize(val scale: Float, val short: String) { Large(1.6f, "L"), Medium(1.3f, "M"), Small(1.0f, "S") }

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
            setTileSource(DayTiles)
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
    LaunchedEffect(Unit) {
        com.taycanscreen.app.nav.LocalDirections.mode.value =
            if (prefs.getString("directions", "Local") == "Compass") com.taycanscreen.app.nav.LocalDirections.Mode.Compass
            else com.taycanscreen.app.nav.LocalDirections.Mode.Local
    }
    var satellite by remember { mutableStateOf(prefs.getString("map_style", "map") == "satellite") }
    val labelsOverlay = remember(mapView) {
        org.osmdroid.views.overlay.TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, SatelliteLabels), context,
        ).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            loadingLineColor = android.graphics.Color.TRANSPARENT
        }
    }
    DisposableEffect(labelsOverlay) {
        onDispose {
            mapView.overlays.remove(labelsOverlay)
            labelsOverlay.onDetach(mapView)
        }
    }
    // Labels sit directly above the tiles (under the route and the car marker).
    LaunchedEffect(satellite) {
        mapView.overlays.remove(labelsOverlay)
        if (satellite) mapView.overlays.add(0, labelsOverlay)
        mapView.invalidate()
    }
    var mapSize by remember {
        mutableStateOf(runCatching { MapSize.valueOf(prefs.getString("map_size", "Large")!!) }.getOrDefault(MapSize.Large))
    }
    LaunchedEffect(mapSize) {
        mapView.tilesScaleFactor = mapSize.scale
        mapView.invalidate()
    }
    val headingUp = rememberUpdatedState(!northUp)
    val locationOverlay = remember(locationGranted) {
        if (!locationGranted) null
        else CarLocationOverlay(locationProvider(context), mapView, { headingUp.value }, ::carSpeedKmh, { _, loc, bearing ->
            com.taycanscreen.app.nav.Nav.onLocation(loc.latitude, loc.longitude)
            com.taycanscreen.app.car.SpeedLimit.onLocation(loc.latitude, loc.longitude, bearing, carSpeedKmh())
            if (gps.fix == null) {
                Boot.mark("First GPS fix")
                // After the hand-off, lock the reticle on now (or when ring 2 lands, if it's still flying).
                val h = Boot.handoffAt.value
                if (h != 0L && targets.acquireAt == 0L) targets.acquireAt = maxOf(SystemClock.elapsedRealtime(), h + 700)
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
    LaunchedEffect(mapView, locationOverlay) {
        while (true) {
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            if (w > 0f) {
                val fix = gps.fix
                val shown = locationOverlay?.shownPoint ?: fix?.let { GeoPoint(it.latitude, it.longitude) }
                targets.markerInMap = if (shown != null) {
                    // toPixels is in the map's unrotated space; rotate into screen space (heading-up).
                    val proj = mapView.projection
                    val p = proj.toPixels(shown, null)
                    val q = proj.rotateAndScalePoint(p.x, p.y, null)
                    Offset(q.x.toFloat(), q.y.toFloat())
                } else {
                    Offset(w / 2f, h / 2f + if (headingUp.value) h * 0.22f else 0f)
                }
                gps.mapOrientation = mapView.mapOrientation
            }
            delay(100)
        }
    }

    // Smooth tracking: move the marker and the map every frame (~30 per second) from the predicted position.
    LaunchedEffect(locationOverlay) {
        val o = locationOverlay ?: return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { }
            val now = SystemClock.elapsedRealtime()
            if (now - last < 30) continue
            last = now
            o.tick(now)
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
                val tiles = when {
                    satellite -> SatelliteTiles
                    c.isNight -> NightTiles
                    else -> DayTiles
                }
                if (it.tileProvider.tileSource.name() != tiles.name()) it.setTileSource(tiles)
                it.overlayManager.tilesOverlay.setColorFilter(
                    when {
                        satellite && c.isNight -> SatelliteNight
                        satellite -> null
                        c.isNight -> NightTint
                        else -> DayFilter
                    },
                )
                it.invalidate()
            },
        )
        // Active route (drawn under the car marker); framing a new route pauses following for 10 s.
        RouteOnMap(mapView, c) {
            locationOverlay?.disableFollowLocation()
            lastTouchAt = System.currentTimeMillis()
        }
        MarkerLayer(c, gps, targets, locationGranted)

        // v3: what's ahead and behind, in local terms (mauka / makai / Diamond Head / ʻEwa) or compass.
        // Tap to switch between the two.
        DirectionLabels(gps, northUp, c)

        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.background(c.overlay, Filament.CutS).insetLine(c.border)) {
                MapButton(FIcons.Plus, "Zoom in", c.text) { mapView.controller.zoomIn() }
                Box(Modifier.padding(horizontal = 16.dp).width(32.dp).height(1.dp).background(c.border))
                MapButton(FIcons.Minus, "Zoom out", c.text) { mapView.controller.zoomOut() }
            }
            NorthButton(northUp, gps, c) {
                northUp = !northUp
                prefs.edit().putBoolean("north_up", northUp).apply()
                AppLog.info("map", if (northUp) "North up" else "Heading up")
            }
            // Map size: L (Porsche-like, default) → M → S.
            Column(
                Modifier.size(64.dp).background(c.overlay, Filament.CutS).insetLine(c.border).clickable {
                    mapSize = MapSize.entries[(mapSize.ordinal + 1) % MapSize.entries.size]
                    prefs.edit().putString("map_size", mapSize.name).apply()
                    AppLog.info("map", "Map size ${mapSize.name}")
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText("Aa", style = Filament.text(24.sp, 600, c.accent))
                BasicText("SIZE ${mapSize.short}", style = Filament.label(11.sp, c.textDim, 0.08f))
            }
            // Map / satellite.
            Column(
                Modifier.size(64.dp).background(c.overlay, Filament.CutS).insetLine(c.border).clickable {
                    satellite = !satellite
                    prefs.edit().putString("map_style", if (satellite) "satellite" else "map").apply()
                    AppLog.info("map", if (satellite) "Satellite view" else "Map view")
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(FIcons.Layers, if (satellite) "Satellite (tap for map)" else "Map (tap for satellite)", tint = c.accent, modifier = Modifier.size(26.dp))
                BasicText(if (satellite) "SAT" else "MAP", Modifier.padding(top = 2.dp), style = Filament.label(11.sp, c.textDim, 0.08f))
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

        // GPS status only while there's no fix (v3 keeps the map clean).
        if (gps.fix == null) GpsChip(gps, locationGranted, c, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 40.dp))
        NextTurnBanner(c, Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 28.dp))
        RouteCard(c, Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 28.dp))

        BasicText(
            if (satellite) "Imagery and labels © Esri, Maxar, Earthstar Geographics"
            else "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp)
                .background(c.overlay).padding(horizontal = 6.dp, vertical = 2.dp),
            style = Filament.text(10.sp, 400, c.textFaint),
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

/** Top and bottom of the map: the direction ahead and behind (v3 board's MAUKA / MAKAI labels). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DirectionLabels(gps: GpsInfo, northUp: Boolean, c: DriveColors) {
    val context = LocalContext.current
    val mode by com.taycanscreen.app.nav.LocalDirections.mode.collectAsState()
    val at = gps.fix?.let { com.taycanscreen.app.nav.LatLon(it.latitude, it.longitude) }
    // Direction that is "up" on the map: north in north-up, else the way the map is turned.
    val up = if (northUp) 0.0 else ((-gps.mapOrientation).toDouble() % 360 + 360) % 360
    val top = com.taycanscreen.app.nav.LocalDirections.name(up, at, mode)
    val bottom = com.taycanscreen.app.nav.LocalDirections.name(up + 180, at, mode)
    val toggle = Modifier.clickable {
        val next = if (mode == com.taycanscreen.app.nav.LocalDirections.Mode.Local) com.taycanscreen.app.nav.LocalDirections.Mode.Compass
        else com.taycanscreen.app.nav.LocalDirections.Mode.Local
        com.taycanscreen.app.nav.LocalDirections.mode.value = next
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("directions", next.name).apply()
        AppLog.info("map", "Directions: ${next.name}")
    }
    BasicText(top, Modifier.align(Alignment.TopCenter).then(toggle).padding(horizontal = 20.dp, vertical = 12.dp),
        style = Filament.label(11.sp, c.textFaint, 0.18f))
    BasicText(bottom, Modifier.align(Alignment.BottomCenter).then(toggle).padding(horizontal = 20.dp, vertical = 10.dp),
        style = Filament.label(11.sp, c.textDim, 0.18f))
}

@Composable
private fun NorthButton(northUp: Boolean, gps: GpsInfo, c: DriveColors, onToggle: () -> Unit) {
    Column(
        Modifier.size(64.dp).background(c.overlay, Filament.CutS).insetLine(c.border).clickable(onClick = onToggle),
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
}

@Composable
private fun GpsChip(gps: GpsInfo, locationGranted: Boolean, c: DriveColors, modifier: Modifier) {
    val fix = gps.fix
    Row(
        modifier.height(40.dp).background(c.overlay, Filament.CutS).insetLine(c.border).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(FIcons.Gps, null, tint = if (fix != null) c.accent else c.textFaint, modifier = Modifier.size(18.dp))
        BasicText(
            when {
                !locationGranted -> "GPS · NO PERMISSION"
                fix == null -> "GPS SEARCHING"
                else -> "GPS ±${fix.accuracy.roundToInt()} m"
            },
            style = Filament.label(14.sp, c.textDim, 0f),
        )
        Box(Modifier.width(1.dp).height(16.dp).background(c.border))
        BasicText("${gps.usedSats}/${gps.seenSats} SAT", style = Filament.label(14.sp, c.textDim, 0f))
    }
}

/** 1 px inset outline following the cut-s shape, like the design's "box-shadow: inset 0 0 0 1px". */
fun Modifier.insetLine(color: Color) = this.drawWithContent {
    drawContent()
    val s = density
    val cut = 0f // v3: square corners
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
    ReticleLayer(c, gps, targets, searching, acquireT)
}

@Composable
private fun ReticleLayer(c: DriveColors, gps: GpsInfo, targets: DriveTargets, searching: Boolean, acquireT: Float?) {
    val spin by rememberInfiniteTransition(label = "reticle").animateFloat(
        0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin",
    )
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
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
private fun HeadingTape(gps: GpsInfo, c: DriveColors, modifier: Modifier) {
    val heading = gps.heading
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
    Box(Modifier.size(64.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/** The car's speed from OBD (km/h), if fresh. */
private fun carSpeedKmh(): Double? {
    return Taycan.speedKmh(com.taycanscreen.app.obd.ObdEngine.readings.value)
}

/**
 * Follows the car and, in heading-up mode, turns the map so the direction of travel points up.
 *
 * Smooth tracking: osmdroid's own follow animates the map to each fix over ~1 s, so the map was always
 * a second or more behind (plus the fix's own delay through CarPlay). Instead, [tick] runs every frame:
 * it predicts where the car is now from the last fix, its age, the direction of travel and the car's
 * own speed (OBD), eases the marker towards that, and moves/turns the map with it.
 */
private class CarLocationOverlay(
    provider: GpsMyLocationProvider,
    private val map: MapView,
    private val headingUp: () -> Boolean,
    private val carSpeedKmh: () -> Double?,
    private val onFix: (status: String, location: Location, bearing: Float?) -> Unit,
) : MyLocationNewOverlay(provider, map) {
    private var lastLogAt = 0L
    private var lastPoint: Location? = null
    @Volatile private var lastBearing: Float? = null
    private var lastBearingSource = ""
    @Volatile private var lastGpsAt = 0L

    // Main-thread state for the smoothed position.
    private var fix: Location? = null
    private var fixAtMs = 0L
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var shownBearing: Float? = null
    private var lastTickMs = 0L
    private var lastCenterLat = Double.NaN
    private var lastCenterLon = Double.NaN
    private val drawPx = android.graphics.Point()

    /** Where the marker is drawn (smoothed, predicted), or null before the first fix. */
    val shownPoint: GeoPoint? get() = if (shownLat.isNaN()) null else GeoPoint(shownLat, shownLon)

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        if (location == null) {
            super.onLocationChanged(location, source)
            return
        }
        val nowEl = SystemClock.elapsedRealtime()
        // The car's GPS comes in as "gps". While it's current, skip the coarser fused/network fixes,
        // which arrive interleaved and pulled the marker back and forth.
        if (location.provider == android.location.LocationManager.GPS_PROVIDER) {
            lastGpsAt = nowEl
        } else if (nowEl - lastGpsAt < 3_000) {
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
        lastBearing?.let { location.bearing = it }
        super.onLocationChanged(location, source)
        Sun.latitude = location.latitude
        Sun.longitude = location.longitude
        // How old the fix already is when it reaches us (the CarPlay relay adds delay).
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
            .takeIf { location.elapsedRealtimeNanos > 0 && it in 0..3_000 } ?: 0L
        val status = "GPS ±%d m · %s".format(location.accuracy.roundToInt(), location.provider ?: "?")
        val copy = Location(location)
        val heading = lastBearing
        map.post {
            fix = copy
            fixAtMs = nowEl - ageMs
            onFix(status, copy, heading)
        }
        // Every 10 s while moving (30 s parked): GPS speed vs the car's speed, fix age, bearing source.
        val now = System.currentTimeMillis()
        if (now - lastLogAt > (if (moving) 10_000 else 30_000)) {
            lastLogAt = now
            AppLog.info(
                "map",
                "Fix from ${location.provider}: ±${location.accuracy.roundToInt()} m, age $ageMs ms, " +
                    "GPS ${if (location.hasSpeed()) "%.1f".format(location.speed * 2.23694) else "?"} mph vs car " +
                    "${obd?.let { "%.1f".format(it * 0.621371) } ?: "?"} mph, " +
                    "bearing ${lastBearing?.roundToInt() ?: -1} from ${lastBearingSource.ifBlank { "none yet" }}",
            )
        }
    }

    /** osmdroid would animate the map to every fix over ~1 s; [tick] moves it every frame instead. */
    override fun setLocation(location: Location?) {
        val following = mIsFollowing
        mIsFollowing = false
        super.setLocation(location)
        mIsFollowing = following
    }

    /** Called every frame on the main thread: predict, ease, and move/turn the map. */
    fun tick(nowMs: Long) {
        val f = fix ?: return
        val dt = if (lastTickMs == 0L) 0.033 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 0.25)
        lastTickMs = nowMs
        val b = lastBearing
        val speedMps = carSpeedKmh()?.div(3.6) ?: if (f.hasSpeed()) f.speed.toDouble() else 0.0
        var pLat = f.latitude
        var pLon = f.longitude
        if (b != null && speedMps > 1.0) {
            // Ahead by the fix's age plus the easing delay, so at steady speed the marker sits on the car.
            val t = ((nowMs - fixAtMs) / 1000.0 + EASE_S).coerceIn(0.0, 2.0 + EASE_S)
            val d = speedMps * t
            val rad = Math.toRadians(b.toDouble())
            pLat += d * kotlin.math.cos(rad) / 111_320.0
            pLon += d * kotlin.math.sin(rad) / (111_320.0 * kotlin.math.cos(Math.toRadians(pLat)))
        }
        val jump = shownLat.isNaN() || metres(shownLat, shownLon, pLat, pLon) > 80.0
        if (jump) {
            shownLat = pLat
            shownLon = pLon
        } else {
            val k = 1.0 - kotlin.math.exp(-dt / EASE_S)
            shownLat += (pLat - shownLat) * k
            shownLon += (pLon - shownLon) * k
        }
        if (b != null) {
            val cur = shownBearing ?: b
            val diff = ((b - cur + 540f) % 360f) - 180f
            shownBearing = (cur + diff * (1f - kotlin.math.exp(-dt / 0.35).toFloat()) + 360f) % 360f
        }
        var changed = false
        if (mIsFollowing && (lastCenterLat.isNaN() || metres(lastCenterLat, lastCenterLon, shownLat, shownLon) > 0.05)) {
            map.controller.setCenter(GeoPoint(shownLat, shownLon))
            lastCenterLat = shownLat
            lastCenterLon = shownLon
            changed = true
        }
        val sb = shownBearing
        if (headingUp() && sb != null && abs(((-sb - map.mapOrientation + 540f) % 360f) - 180f) > 0.05f) {
            map.mapOrientation = -sb
            changed = true
        }
        if (!changed && !mIsFollowing && speedMps > 1.0) changed = true // marker still moving on a panned map
        if (changed) map.invalidate()
    }

    override fun enableFollowLocation() {
        super.enableFollowLocation()
        lastCenterLat = Double.NaN // recentre on the next frame
    }

    /** Draw the marker at the smoothed position with the smoothed heading. */
    override fun drawMyLocation(canvas: android.graphics.Canvas, pj: org.osmdroid.views.Projection, lastFix: Location) {
        if (shownLat.isNaN()) {
            super.drawMyLocation(canvas, pj, lastFix)
            return
        }
        pj.toPixels(GeoPoint(shownLat, shownLon), drawPx)
        val x = drawPx.x.toFloat()
        val y = drawPx.y.toFloat()
        canvas.save()
        val sb = shownBearing
        if (sb != null && mDirectionArrowBitmap != null) {
            canvas.rotate(sb, x, y)
            canvas.drawBitmap(mDirectionArrowBitmap, x - mDirectionArrowCenterX, y - mDirectionArrowCenterY, mPaint)
        } else if (mPersonBitmap != null) {
            canvas.rotate(-map.mapOrientation, x, y)
            canvas.drawBitmap(mPersonBitmap, x - mPersonHotspot.x, y - mPersonHotspot.y, mPaint)
        }
        canvas.restore()
    }

    private companion object {
        const val EASE_S = 0.25
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * 111_320.0
            val dx = (lon2 - lon1) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat1))
            return kotlin.math.sqrt(dx * dx + dy * dy)
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
