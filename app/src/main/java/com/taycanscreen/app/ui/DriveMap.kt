package com.taycanscreen.app.ui

import android.content.Context
import android.location.Location
import android.os.SystemClock
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.map.CarTracker
import com.taycanscreen.app.map.MapTheme
import com.taycanscreen.app.obd.Taycan
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Map size (owner, 2026-11-09: Porsche's map reads better). The vector map is drawn at
 * screen density × this, so text and roads grow and stay sharp.
 */
private data class MapSize(val level: Int) {
    /** Level 1–10 (owner, 2026-11-10: a custom size instead of L/M/S). 6 = the old Large. */
    val scale: Float get() = 0.9f + 0.2f * level
    val name: String get() = "$level (×%.1f)".format(scale)

    companion object {
        const val MIN = 1
        const val MAX = 10
        fun load(prefs: android.content.SharedPreferences): MapSize {
            val lv = prefs.getInt("map_level", -1).takeIf { it in MIN..MAX }
                ?: when (prefs.getString("map_size", "Large")) { "Medium" -> 4; "Small" -> 2; else -> 6 }
            return MapSize(lv)
        }
    }
}

/** Map buttons (owner, 2026-11-10: bigger). */
private val MAP_BTN = 80.dp

/** Where the car marker is drawn and how it's turned on screen (updated every frame). */
@Stable
private class CarMarkerState {
    var visible by mutableStateOf(false)
    var rotation by mutableFloatStateOf(0f)
    var hasHeading by mutableStateOf(false)
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

/**
 * The map (MapLibre vector map, see map/MapTheme.kt) with the car, route and v3 overlays. The camera
 * follows the predicted car position every frame; in heading-up mode the map turns with the car and
 * the car sits lower on screen so more road ahead shows. Touching the map stops following for 10 s.
 */
@Composable
fun MapArea(
    c: DriveColors,
    outsideF: V?,
    locationGranted: Boolean,
    gps: GpsInfo,
    targets: DriveTargets,
    modifier: Modifier,
    onSearch: () -> Unit = {},
    /** Home / Work tapped (or the search box pulled down: "home"). */
    onSaved: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var northUp by remember { mutableStateOf(prefs.getBoolean("north_up", false)) }
    var satellite by remember { mutableStateOf(prefs.getString("map_style", "map") == "satellite") }
    var mapSize by remember {
        mutableStateOf(MapSize.load(prefs))
    }
    // Custom size: tap Aa for − / + (the map is rebuilt once the taps stop).
    var sizeOpen by remember { mutableStateOf(false) }
    var pendingLevel by remember { mutableIntStateOf(mapSize.level) }
    var sizeTouchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pendingLevel) {
        if (pendingLevel == mapSize.level) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        mapSize = MapSize(pendingLevel)
        prefs.edit().putInt("map_level", pendingLevel).apply()
        AppLog.info("map", "Map size ${mapSize.name}")
    }
    LaunchedEffect(sizeOpen, sizeTouchedAt) {
        if (!sizeOpen) return@LaunchedEffect
        kotlinx.coroutines.delay(6_000)
        sizeOpen = false
    }
    var following by remember { mutableStateOf(true) }
    var lastTouchAt by remember { mutableLongStateOf(0L) }
    val marker = remember { CarMarkerState() }
    LaunchedEffect(Unit) {
        com.taycanscreen.app.nav.LocalDirections.mode.value =
            if (prefs.getString("directions", "Local") == "Compass") com.taycanscreen.app.nav.LocalDirections.Mode.Compass
            else com.taycanscreen.app.nav.LocalDirections.Mode.Local
    }

    // Car position (Android location service) with prediction for smooth tracking.
    val tracker = remember(locationGranted) {
        if (!locationGranted) null
        else CarTracker(context, ::carSpeedKmh) { _, loc, bearing ->
            com.taycanscreen.app.nav.Nav.onLocation(loc.latitude, loc.longitude)
            com.taycanscreen.app.car.SpeedLimit.onLocation(loc.latitude, loc.longitude, bearing, carSpeedKmh())
            Sun.latitude = loc.latitude
            Sun.longitude = loc.longitude
            if (gps.fix == null) {
                Boot.mark("First GPS fix")
                // After the hand-off, lock the reticle on now (or when ring 2 lands, if it's still flying).
                val h = Boot.handoffAt.value
                if (h != 0L && targets.acquireAt == 0L) targets.acquireAt = maxOf(SystemClock.elapsedRealtime(), h + 700)
            }
            gps.fix = loc
            gps.heading = bearing
        }
    }
    DisposableEffect(tracker) {
        tracker?.start()
        onDispose { tracker?.stop() }
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
    // Touching the map stops following; it resumes 10 s after the last touch, like car navs do.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            if (!following && System.currentTimeMillis() - lastTouchAt > 10_000) {
                following = true
                AppLog.info("map", "Following the car again")
            }
        }
    }

    Box(
        modifier.clipToBounds().background(c.background)
            .onGloballyPositioned { targets.map = it.boundsInRoot() },
    ) {
        // A new map for each size (the vector map's drawing scale is fixed when it's created).
        key(mapSize) {
            VectorMap(
                c, satellite, northUp, mapSize, tracker, gps, targets, marker,
                followingState = rememberUpdatedState(following),
                onUserMove = {
                    following = false
                    lastTouchAt = System.currentTimeMillis()
                },
            )
        }
        CarMarker(c, targets, marker)
        MarkerLayer(c, gps, targets, locationGranted)

        // v3: what's ahead and behind, in local terms (mauka / makai / Diamond Head / ʻEwa) or compass.
        // Tap to switch between the two.
        DirectionLabels(gps, northUp, c)

        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Column(Modifier.background(c.overlay, Filament.CutS).insetLine(c.border)) {
                MapButton(FIcons.Plus, "Zoom in", c.text) { MapHolder.map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                Box(Modifier.padding(horizontal = 20.dp).width(40.dp).height(1.dp).background(c.border))
                MapButton(FIcons.Minus, "Zoom out", c.text) { MapHolder.map?.animateCamera(CameraUpdateFactory.zoomOut()) }
            }
            NorthButton(northUp, gps, c) {
                northUp = !northUp
                prefs.edit().putBoolean("north_up", northUp).apply()
                AppLog.info("map", if (northUp) "North up" else "Heading up")
            }
            // Map size, custom (owner, 2026-11-10): tap Aa, then − / + (levels 1–10).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sizeOpen) {
                    Row(Modifier.height(MAP_BTN).background(c.overlay, Filament.CutS).insetLine(c.accentLine), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(MAP_BTN).clickable { pendingLevel = (pendingLevel - 1).coerceAtLeast(MapSize.MIN); sizeTouchedAt = System.currentTimeMillis() },
                            contentAlignment = Alignment.Center,
                        ) { BasicText("A−", style = Filament.text(22.sp, 600, if (pendingLevel > MapSize.MIN) c.text else c.textFaint)) }
                        BasicText("$pendingLevel", Modifier.width(44.dp), style = Filament.num(28.sp, 500, c.accent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                        Box(
                            Modifier.size(MAP_BTN).clickable { pendingLevel = (pendingLevel + 1).coerceAtMost(MapSize.MAX); sizeTouchedAt = System.currentTimeMillis() },
                            contentAlignment = Alignment.Center,
                        ) { BasicText("A+", style = Filament.text(26.sp, 600, if (pendingLevel < MapSize.MAX) c.text else c.textFaint)) }
                    }
                }
                Column(
                    Modifier.size(MAP_BTN).background(c.overlay, Filament.CutS).insetLine(if (sizeOpen) c.accentLine else c.border).clickable {
                        sizeOpen = !sizeOpen
                        sizeTouchedAt = System.currentTimeMillis()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText("Aa", style = Filament.text(28.sp, 600, c.accent))
                    BasicText("SIZE ${mapSize.level}", style = Filament.label(12.sp, c.textDim, 0.08f))
                }
            }
            // Map / satellite.
            Column(
                Modifier.size(MAP_BTN).background(c.overlay, Filament.CutS).insetLine(c.border).clickable {
                    satellite = !satellite
                    prefs.edit().putString("map_style", if (satellite) "satellite" else "map").apply()
                    AppLog.info("map", if (satellite) "Satellite view" else "Map view")
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(FIcons.Layers, if (satellite) "Satellite (tap for map)" else "Map (tap for satellite)", tint = c.accent, modifier = Modifier.size(32.dp))
                BasicText(if (satellite) "SAT" else "MAP", Modifier.padding(top = 2.dp), style = Filament.label(12.sp, c.textDim, 0.08f))
            }
            Box(Modifier.background(c.overlay, Filament.CutS).insetLine(c.border)) {
                MapButton(FIcons.Recenter, "Recenter", c.text) {
                    following = true
                    AppLog.info("map", "Recenter")
                }
            }
        }

        // GPS status only while there's no fix (v3 keeps the map clean).
        if (gps.fix == null) GpsChip(gps, locationGranted, c, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 40.dp))
        // "Search for destination" box, top left (owner, 2026-11-09, like Porsche's); the turn card takes
        // its place during a route.
        val activeRoute by com.taycanscreen.app.nav.Nav.active.collectAsState()
        val saved by com.taycanscreen.app.nav.Nav.saved.collectAsState()
        // Top left: the search box with Home and Work (owner, 2026-11-10; pull the box down to go home),
        // or during a route the next turn with the trip details under it.
        Column(Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Appear(activeRoute == null, enter = Motion.FadeIn, exit = Motion.FadeOut) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    var pull by remember { mutableFloatStateOf(0f) }
                    val pullDp = with(LocalDensity.current) { pull.toDp() }
                    Row(
                        Modifier.offset(y = (pullDp * 0.5f).coerceAtMost(48.dp)).width(420.dp).height(72.dp)
                            .background(c.overlay).insetLine(if (pull > 90f) c.accentLine else c.border)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { pull = 0f },
                                    onDragEnd = {
                                        if (pull > 90f * density) onSaved("home")
                                        pull = 0f
                                    },
                                    onDragCancel = { pull = 0f },
                                    onVerticalDrag = { ch, dy -> ch.consume(); pull = (pull + dy).coerceAtLeast(0f) },
                                )
                            }
                            .clickable(onClick = onSearch).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val ready = pull > 90f * LocalDensity.current.density
                        Icon(if (pull > 0f) FIcons.Home else FIcons.Search, null, tint = c.accent, modifier = Modifier.size(30.dp))
                        BasicText(
                            when {
                                pull <= 0f -> "Search for destination"
                                saved["home"] == null -> "Pull down to set Home"
                                ready -> "Release to go home"
                                else -> "Pull down to go home"
                            },
                            style = Filament.text(20.sp, 500, if (ready) c.accent else c.textDim), maxLines = 1,
                        )
                    }
                    for (kind in listOf("home", "work")) {
                        Column(
                            Modifier.size(72.dp).background(c.overlay).insetLine(c.border).clickable { onSaved(kind) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(if (kind == "home") FIcons.Home else FIcons.Work, if (kind == "home") "Home" else "Work", tint = if (saved[kind] != null) c.accent else c.textFaint, modifier = Modifier.size(30.dp))
                            BasicText(kind.uppercase(), Modifier.padding(top = 2.dp), style = Filament.label(11.sp, c.textDim, 0.08f))
                        }
                    }
                }
            }
            Appear(activeRoute != null, enter = Motion.CardIn, exit = Motion.FadeOut) { NextTurnBanner(c, Modifier.width(460.dp)) }
            RouteCard(c, Modifier.width(460.dp))
        }

        BasicText(
            if (satellite) MapTheme.SATELLITE_ATTRIBUTION else MapTheme.ATTRIBUTION,
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

/** The current map, for the zoom buttons. */
private object MapHolder {
    var map: MapLibreMap? = null
}

/**
 * The MapLibre view: style (recoloured to v3), route, and the camera that follows the car each frame.
 */
@Composable
private fun VectorMap(
    c: DriveColors,
    satellite: Boolean,
    northUp: Boolean,
    size: MapSize,
    tracker: CarTracker?,
    gps: GpsInfo,
    targets: DriveTargets,
    marker: CarMarkerState,
    followingState: androidx.compose.runtime.State<Boolean>,
    onUserMove: () -> Unit,
) {
    if (UiEvents.preview) {
        Box(Modifier.fillMaxSize().background(if (c.isNight) Color(0xFF15181A) else Color(0xFFE9E7E2)))
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true) // a normal view: overlays, clipping and screenshots behave
            .pixelRatio(context.resources.displayMetrics.density * size.scale)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val moved = rememberUpdatedState(onUserMove)

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.addOnDidFailLoadingMapListener { msg -> AppLog.warn("map", "Map style failed to load: $msg") }
        mapView.getMapAsync { m ->
            m.uiSettings.setRotateGesturesEnabled(false)
            m.uiSettings.setTiltGesturesEnabled(false)
            m.uiSettings.setCompassEnabled(false)
            m.uiSettings.setLogoEnabled(false)
            m.uiSettings.setAttributionEnabled(false)
            m.uiSettings.setQuickZoomGesturesEnabled(true) // one finger: double-tap and slide
            m.setMaxZoomPreference(19.0)
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(21.2969, -157.8171)).zoom(15.0).build(), // Honolulu until GPS
                ),
            )
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = moved.value()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) moved.value()
            }
            map = m
            MapHolder.map = m
            m.setStyle(Style.Builder().fromUri(MapTheme.STYLE_URL)) { s ->
                AppLog.info("map", "Vector map ready (${s.layers.size} layers, size ${size.name})")
                style = s
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (MapHolder.map == map) MapHolder.map = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(style, c.isNight, satellite) {
        style?.let { MapTheme.apply(it, c, satellite) }
    }

    // Until the map is ready, the "searching" reticle sits where the car will be (not in the corner).
    LaunchedEffect(map, northUp) {
        if (map != null) return@LaunchedEffect
        while (true) {
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            if (w > 0f) targets.markerInMap = Offset(w / 2f, h / 2f + if (northUp) 0f else h * 0.22f)
            delay(100)
        }
    }
    // Every frame: follow the predicted car position, and tell the overlays where the car is.
    LaunchedEffect(map, tracker, northUp) {
        val m = map ?: return@LaunchedEffect
        var lastTarget: LatLng? = null
        var lastBearing = Double.NaN
        while (true) {
            // Every frame (it used to skip every other one, which capped the map at 30 a second).
            withFrameNanos { }
            val now = SystemClock.elapsedRealtime()
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            if (w <= 0f) continue
            val shown = tracker?.tick(now)
            val camBearing = m.cameraPosition.bearing
            if (shown != null && followingState.value) {
                val target = LatLng(shown.lat, shown.lon)
                val bearing = if (northUp) 0.0 else shown.bearing?.toDouble() ?: camBearing
                val changed = lastTarget == null || abs(target.latitude - lastTarget!!.latitude) > 1e-7 ||
                    abs(target.longitude - lastTarget!!.longitude) > 1e-7 || abs(bearing - lastBearing) > 0.05
                if (changed) {
                    // Heading-up: the car sits 22 % below the centre (padding pushes the focus point down).
                    val top = if (northUp) 0.0 else h * 0.44
                    m.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(target).bearing(bearing).padding(0.0, top, 0.0, 0.0).build(),
                        ),
                    )
                    lastTarget = target
                    lastBearing = bearing
                }
            }
            // Overlays: marker position and rotation on screen, map orientation for the labels/compass.
            val camB = m.cameraPosition.bearing
            gps.mapOrientation = (-camB).toFloat()
            if (shown != null) {
                val pt = m.projection.toScreenLocation(LatLng(shown.lat, shown.lon))
                targets.markerInMap = Offset(pt.x, pt.y)
                marker.visible = true
                marker.hasHeading = shown.bearing != null
                marker.rotation = ((shown.bearing ?: 0f) - camB.toFloat())
            } else {
                marker.visible = false
                targets.markerInMap = Offset(w / 2f, h / 2f + if (northUp) 0f else h * 0.22f)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    RouteOnMap(map, style, c) { onUserMove() }
}

/** The car: heading cone, dashed ring, disc and arrow (Filament marker), drawn over the map. */
@Composable
private fun CarMarker(c: DriveColors, targets: DriveTargets, m: CarMarkerState) {
    if (!m.visible) return
    Canvas(
        Modifier.size(160.dp)
            .offset { IntOffset((targets.markerInMap.x - 80 * density).roundToInt(), (targets.markerInMap.y - 80 * density).roundToInt()) }
            .graphicsLayer { rotationZ = if (m.hasHeading) m.rotation else 0f },
    ) {
        val u = density
        val ctr = Offset(80 * u, 80 * u)
        if (m.hasHeading) {
            val cone = Path().apply {
                moveTo(ctr.x, ctr.y)
                arcTo(Rect(ctr, 75 * u), -110f, 40f, false)
                close()
            }
            drawPath(cone, c.accent.copy(alpha = 0.14f))
        }
        drawCircle(c.accent.copy(alpha = 0.55f), 34 * u, ctr, style = Stroke(1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 6 * u))))
        drawCircle(c.panel.copy(alpha = 0.85f), 20 * u, ctr)
        drawCircle(c.accent, 20 * u, ctr, style = Stroke(1.5f * u))
        if (m.hasHeading) {
            drawPath(Path().apply {
                moveTo(80 * u, 65 * u); lineTo(91 * u, 92 * u); lineTo(80 * u, 86 * u); lineTo(69 * u, 92 * u); close()
            }, c.accent)
        } else {
            drawPath(Path().apply {
                moveTo(80 * u, 73 * u); lineTo(87 * u, 80 * u); lineTo(80 * u, 87 * u); lineTo(73 * u, 80 * u); close()
            }, c.accent)
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
    // In the margin above the search row (it ran into the Work button, seen in the car on build 171).
    BasicText(top, Modifier.align(Alignment.TopCenter).then(toggle).padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 12.dp),
        style = Filament.label(11.sp, c.textFaint, 0.18f))
    BasicText(bottom, Modifier.align(Alignment.BottomCenter).then(toggle).padding(horizontal = 20.dp, vertical = 10.dp),
        style = Filament.label(11.sp, c.textDim, 0.18f))
}

@Composable
private fun NorthButton(northUp: Boolean, gps: GpsInfo, c: DriveColors, onToggle: () -> Unit) {
    Column(
        Modifier.size(MAP_BTN).background(c.overlay, Filament.CutS).insetLine(c.border).clickable(onClick = onToggle),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The compass needle points to north: up in north-up, turned with the map in heading-up.
        Icon(
            FIcons.Compass, if (northUp) "North up (tap for heading up)" else "Heading up (tap for north up)",
            tint = c.accent, modifier = Modifier.size(32.dp).rotate(if (northUp) 0f else gps.mapOrientation),
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

@Composable
private fun MapButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(MAP_BTN).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(34.dp))
    }
}

/** The car's speed from OBD (km/h), if fresh. */
private fun carSpeedKmh(): Double? {
    return Taycan.speedKmh(com.taycanscreen.app.obd.ObdEngine.readings.value)
}

