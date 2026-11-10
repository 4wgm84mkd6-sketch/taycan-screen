package com.taycanscreen.app

import android.Manifest
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.log.LogSharer
import com.taycanscreen.app.log.GitHubAuth
import com.taycanscreen.app.log.LogUploader
import com.taycanscreen.app.music.SpotifyController
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.taycanscreen.app.obd.Discovery
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdViewModel
import com.taycanscreen.app.ui.BoxPanel
import com.taycanscreen.app.ui.DriveColors
import com.taycanscreen.app.ui.DriveScreen
import com.taycanscreen.app.ui.LivePanel
import com.taycanscreen.app.ui.ThemeMode
import com.taycanscreen.app.ui.isNight
import com.taycanscreen.app.ui.Palette
import com.taycanscreen.app.ui.TaycanTheme
import com.taycanscreen.app.ui.WindowNumbers
import com.taycanscreen.app.ui.setFullScreen
import com.taycanscreen.app.ui.windowNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.taycanscreen.app.update.InstallResultReceiver
import com.taycanscreen.app.update.UpdateState
import com.taycanscreen.app.update.UpdateViewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val updates: UpdateViewModel by viewModels()
    private val obd: ObdViewModel by viewModels()
    // Phase 1 plan: run full screen (1602×720) with the box's dock hidden; toggle on the Setup tab.
    private val fullScreen = mutableStateOf(true)

    private val locationGranted = mutableStateOf(false)
    private val themeMode = mutableStateOf(ThemeMode.Auto)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        AppLog.info("app", "Permission answers: $result")
        refreshLocation()
        if (obd.hasPermission()) obd.start()
    }

    /** Spotify's one-time "allow Taycan Screen" screen (Spotify Auth library). */
    private val spotifyAuth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                response.accessToken?.let { com.taycanscreen.app.music.SpotifyWeb.setToken(it, response.expiresIn) }
                SpotifyController.onAuthorizationResult(null)
            }
            AuthorizationResponse.Type.CODE -> SpotifyController.onAuthorizationResult(null)
            AuthorizationResponse.Type.ERROR -> SpotifyController.onAuthorizationResult(response.error ?: "unknown error")
            else -> SpotifyController.onAuthorizationResult("cancelled")
        }
    }

    private fun openSpotifyAuthorization() {
        val request = AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI,
        ).setScopes(arrayOf("app-remote-control", "user-read-playback-state", "user-modify-playback-state", "playlist-read-private", "user-library-read"))
            .build()
        try {
            spotifyAuth.launch(AuthorizationClient.createLoginActivityIntent(this, request))
        } catch (e: Exception) {
            AppLog.error("spotify", "Couldn't open Spotify authorization", e)
            SpotifyController.onAuthorizationResult(e.message ?: "couldn't open")
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshLocation() {
        locationGranted.value = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Asks for Bluetooth (to reach the OBD adapter) and location (for the map) if not granted yet. */
    private fun requestBluetooth() {
        if (obd.hasPermission()) obd.start()
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.filterNot(::granted)
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) AppLog.info("app", "Screen rebuilt by Android (restored state)")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        themeMode.value = runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", "Auto")!!) }.getOrDefault(ThemeMode.Auto)
        refreshLocation()
        // Boot timing: log when our first frame actually reaches the screen.
        window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                com.taycanscreen.app.ui.Boot.markFirstFrame()
                val listener = this
                window.decorView.post { window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
            }
        })
        setContent {
            TaycanTheme {
                // Owner (2026-11-09): all text about 2 pt bigger. The Drive screen sizes its own text
                // (Filament.bump); this scales the standard screens and the Music/Audio panels.
                val d = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * 1.15f),
                ) {
                MainScreen(
                    updates = updates,
                    obd = obd,
                    onRequestBluetooth = { requestBluetooth() },
                    themeMode = themeMode.value,
                    onCycleTheme = {
                        val next = themeMode.value.next()
                        themeMode.value = next
                        uiPrefs.edit().putString("theme", next.name).apply()
                        AppLog.info("ui", "Day/night mode: ${next.label()}")
                    },
                    locationGranted = locationGranted.value,
                    fullScreen = fullScreen.value,
                    onToggleFullScreen = {
                        val on = !fullScreen.value
                        fullScreen.value = on
                        setFullScreen(on)
                        AppLog.info("screen", if (on) "Full screen ON (box bars hidden)" else "Full screen OFF")
                    },
                    readWindow = { windowNumbers() },
                    onShareLog = {
                        try {
                            if (!LogSharer.share(this)) toast("No log file yet")
                        } catch (e: Exception) {
                            AppLog.error("log", "Share failed", e)
                            toast("Couldn't share the log")
                        }
                    },
                    onChooseHomeApp = { openSafely(Intent(Settings.ACTION_HOME_SETTINGS), "home app setting") },
                    onOpenBoxLauncher = { openBoxLauncher() },
                    onOpenInstallSetting = {
                        try {
                            startActivity(updates.updater.installPermissionIntent())
                        } catch (e: Exception) {
                            AppLog.error("update", "Couldn't open install setting", e)
                            toast("Couldn't open Settings on this box")
                        }
                    },
                )
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Start reading the car straight away when the app opens.
        requestBluetooth()
        SpotifyController.authorizationLauncher = { openSpotifyAuthorization() }
        SpotifyController.start(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handled in place (manifest configChanges), so the Drive screen and start-up aren't rebuilt.
        AppLog.info("app", "Configuration changed in place: mcc ${newConfig.mcc} mnc ${newConfig.mnc} uiMode ${newConfig.uiMode} font ${newConfig.fontScale}")
    }

    override fun onPause() {
        super.onPause()
        com.taycanscreen.app.log.FrameStats.stop()
        com.taycanscreen.app.log.Screenshots.attach(null)
    }

    override fun onResume() {
        super.onResume()
        com.taycanscreen.app.log.FrameStats.start()
        com.taycanscreen.app.log.Screenshots.attach(this)
        updates.onResume()
        refreshLocation()
        if (fullScreen.value) setFullScreen(true)
    }

    private fun openSafely(intent: Intent, what: String) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.error("home", "Couldn't open $what", e)
            toast("Couldn't open $what on this box")
        }
    }

    /** Opens the box's own launcher (any other home app), e.g. to reach its app list. */
    private fun openBoxLauncher() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != packageName }
        if (other == null) {
            toast("No other home screen found")
            return
        }
        AppLog.info("home", "Opening box launcher ${other.packageName}")
        openSafely(Intent(home).setClassName(other.packageName, other.name), "the box's launcher")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

private val LargeButtonHeight = 72.dp

@Composable
private fun MainScreen(
    updates: UpdateViewModel,
    obd: ObdViewModel,
    onRequestBluetooth: () -> Unit,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    locationGranted: Boolean,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    readWindow: () -> WindowNumbers,
    onShareLog: () -> Unit,
    onChooseHomeApp: () -> Unit,
    onOpenBoxLauncher: () -> Unit,
    onOpenInstallSetting: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("drive") }
    val panelRequest by com.taycanscreen.app.ui.UiEvents.openPanel.collectAsState()
    LaunchedEffect(panelRequest) { if (panelRequest != null) tab = "drive" }
    var minuteTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    // As the home app, Back must never close the app: it returns to the Drive screen instead.
    BackHandler(enabled = true) { if (tab != "drive") tab = "drive" }
    if (tab == "drive") {
        val night = remember(themeMode, minuteTick) { themeMode.isNight() }
        DriveScreen(
            obd = obd,
            colors = if (night) DriveColors.Night else DriveColors.Day,
            themeMode = themeMode,
            locationGranted = locationGranted,
            onCycleTheme = onCycleTheme,
            onOpenVehicle = { tab = "live" },
            onOpenSetup = { tab = "setup" },
        )
        return
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(16.dp),
    ) {
        val contentW = maxWidth
        val contentH = maxHeight
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier
                    .width(150.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Taycan Screen", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Build ${BuildConfig.VERSION_CODE}", color = Palette.TextDim, fontSize = 16.sp)
                TabButton("Drive", false) { tab = "drive" }
                TabButton("Vehicle", tab == "live") { tab = "live" }
                TabButton("Setup", tab == "setup") { tab = "setup" }
                TabButton("Box", tab == "box") { tab = "box" }
                TabButton("Tools", tab == "tools") { tab = "tools" }
            }
            if (tab == "box") {
                BoxPanel(Modifier.fillMaxSize())
            } else if (tab == "live") {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LivePanel(obd, onRequestBluetooth, Modifier.fillMaxWidth())
                    LogViewer(Modifier.fillMaxWidth().weight(1f))
                }
            } else if (tab == "tools") {
                // Engineering tools, kept away from the everyday Setup page.
                Column(
                    Modifier
                        .width(minOf(420.dp, contentW * 0.42f))
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DiscoveryPanel(obd)
                    BigButton("Share log", onClick = onShareLog)
                    ScreenPanel(readWindow, contentW, contentH, fullScreen, onToggleFullScreen)
                }
                LogViewer(Modifier.fillMaxSize())
            } else {
                // Setup: what the owner uses (update, send log, home app) and how things are right now.
                Column(
                    Modifier
                        .width(minOf(420.dp, contentW * 0.42f))
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UpdatePanel(updates, onOpenInstallSetting)
                    SendLogPanel()
                    Panel {
                        Text("Home screen", color = Palette.TextDim, fontSize = 16.sp)
                        Text(
                            "Taycan Screen can be the box's home app, so it opens by itself after start-up.",
                            color = Palette.Text, fontSize = 16.sp,
                        )
                        BigButton("Choose home app", onClick = onChooseHomeApp)
                        BigButton("Open the box's launcher", onClick = onOpenBoxLauncher)
                    }
                }
                StatusPanel(obd, Modifier.fillMaxSize())
            }
        }
    }
}

/** "How things are right now", in plain words: the first place to look when something seems off. */
@Composable
private fun StatusPanel(obd: ObdViewModel, modifier: Modifier) {
    val car by obd.status.collectAsState()
    val spotify by SpotifyController.status.collectAsState()
    val net by com.taycanscreen.app.net.NetMonitor.link.collectAsState()
    val eq by com.taycanscreen.app.audio.EqEngine.status.collectAsState()
    val limit by com.taycanscreen.app.car.SpeedLimit.limit.collectAsState()
    Column(
        modifier.background(Palette.Surface, RoundedCornerShape(12.dp)).padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Right now", color = Palette.TextDim, fontSize = 18.sp)
        @Composable
        fun row(name: String, ok: Boolean?, text: String) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(name, color = Palette.TextDim, fontSize = 19.sp, modifier = Modifier.width(120.dp))
                Text(text, color = when (ok) { true -> Palette.Accent; false -> Palette.Warn; null -> Palette.Text }, fontSize = 19.sp, modifier = Modifier.weight(1f))
            }
        }
        row(
            "Car", when (car.state) { LinkState.Live -> true; LinkState.CarAsleep, LinkState.Stopped -> null; else -> false },
            when (car.state) {
                LinkState.Live -> "Connected, reading the car" + (car.adapterName?.let { " through $it" } ?: "")
                LinkState.CarAsleep -> "The car is off. Reading resumes when it's switched on."
                else -> car.detail.ifBlank { "Not connected" }
            },
        )
        row(
            "Internet", net.online,
            if (net.online) "Online" + (net.transport.takeIf { it.isNotBlank() && it != "none" }?.let { " through $it" } ?: "")
            else "Offline. Search, routes, updates and sending logs need a connection.",
        )
        row(
            "Spotify", spotify.link == SpotifyController.Link.Connected,
            if (spotify.link == SpotifyController.Link.Connected) "Connected" else spotify.detail.ifBlank { "Connecting…" },
        )
        row("Equalizer", if (eq.attachedTo != null) true else null, eq.attachedTo?.let { "On" } ?: eq.detail)
        row("Speed limit", null, limit?.let { "${it.mph} mph" + (it.road?.let { r -> " on $r" } ?: "") } ?: "None known for this road")
        Text(
            "If something stays amber, tap Send log to Claude and say what you saw.",
            color = Palette.TextDim, fontSize = 16.sp,
        )
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Palette.Accent else Palette.SurfaceHigh,
            contentColor = if (selected) Color.Black else Palette.Text,
        ),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun DiscoveryPanel(obd: ObdViewModel) {
    val state by obd.discovery.collectAsState()
    val link by obd.status.collectAsState()
    LaunchedEffect(Unit) { obd.refreshDiscovery() }
    val live = link.state == LinkState.Live
    Panel {
        Text("Blinker & gear discovery", color = Palette.TextDim, fontSize = 16.sp)
        StatusText("Read-only test: parked, car switched on. It only asks the car for values and changes nothing.")
        if (!live && state !is Discovery.State.Scanning && state !is Discovery.State.Recording) {
            StatusText("Waiting for the car connection (switch the car on)…", Palette.Warn)
        }
        when (val s = state) {
            Discovery.State.Idle ->
                BigButton("1. Scan (up to ~30 min)", enabled = live) { obd.requestDiscovery(Discovery.Request.Scan) }
            is Discovery.State.Scanning -> {
                StatusText("Scanning ${s.module}: ${s.done} of ${s.total} · found ${s.found}")
                LinearProgressIndicator(
                    progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Palette.Accent, trackColor = Palette.SurfaceHigh,
                )
                BigButton("Stop scan") { obd.cancelDiscovery() }
            }
            is Discovery.State.Recording -> {
                StatusText("Recording \"${s.step}\" (round ${s.round} of ${Discovery.ROUNDS_PER_STEP}). Hold this state until it finishes.", Palette.Warn)
                LinearProgressIndicator(
                    progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Palette.Accent, trackColor = Palette.SurfaceHigh,
                )
                BigButton("Stop") { obd.cancelDiscovery() }
            }
            is Discovery.State.Ready -> {
                if (!s.scanComplete) {
                    StatusText("The scan isn't finished (found ${s.found} so far). Continue it first:", Palette.Warn)
                    BigButton("1. Continue scan", enabled = live) { obd.requestDiscovery(Discovery.Request.Scan) }
                    return@Panel
                }
                StatusText("Scan found ${s.found} values. Now set up each state, tap its button, and hold it until recording finishes:", Palette.Accent)
                Discovery.STEPS.forEachIndexed { i, step ->
                    val done = step in s.recorded
                    BigButton("${i + 2}. $step" + if (done) "  ✓" else "", enabled = live) {
                        obd.requestDiscovery(Discovery.Request.Record(step))
                    }
                }
                StatusText("When all are ticked, tap Send log to Claude.")
                BigButton("Scan again from scratch", enabled = live) { obd.requestDiscovery(Discovery.Request.RestartScan) }
            }
        }
    }
}

@Composable
private fun SendLogPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(GitHubAuth.token(context) != null) }
    var code by remember { mutableStateOf<GitHubAuth.DeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        busy = true
        result = null
        scope.launch {
            try {
                val c = GitHubAuth.start()
                code = c
                GitHubAuth.waitForToken(context, c)
                signedIn = true
                result = "Signed in. You can send logs now."
            } catch (e: Exception) {
                AppLog.error("log", "GitHub sign-in failed", e)
                result = com.taycanscreen.app.util.Friendly.net(e, "Sign-in didn't finish. Tap Sign in to GitHub to try again.")
            } finally {
                code = null
                busy = false
            }
        }
    }

    Panel {
        Text("Send log to Claude", color = Palette.TextDim, fontSize = 16.sp)
        when {
            !GitHubAuth.configured ->
                StatusText("Not set up in this build yet (the GitHub App's Client ID is missing).", Palette.Warn)
            code != null -> {
                StatusText("On your iPhone, open github.com/login/device and enter:")
                Text(code!!.userCode, color = Palette.Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                StatusText("Waiting for you to approve…", Palette.TextDim)
            }
            !signedIn -> BigButton(if (busy) "Starting…" else "Sign in to GitHub", enabled = !busy) { signIn() }
            else -> {
                result?.let { StatusText(it, if (it.startsWith("Sent") || it.startsWith("Signed")) Palette.Accent else Palette.Error) }
                BigButton(if (busy) "Sending…" else "Send log to Claude", enabled = !busy) {
                    busy = true
                    result = null
                    scope.launch {
                        result = try {
                            LogUploader.upload(context)
                        } catch (e: LogUploader.SignInNeeded) {
                            GitHubAuth.signOut(context)
                            signedIn = false
                            e.message
                        }
                        busy = false
                    }
                }
                BigButton("Sign out of GitHub", enabled = !busy) {
                    GitHubAuth.signOut(context)
                    signedIn = false
                    result = null
                }
            }
        }
        if (!signedIn && code == null) result?.let { StatusText(it, Palette.Error) }
    }
}

@Composable
private fun UpdatePanel(updates: UpdateViewModel, onOpenInstallSetting: () -> Unit) {
    val state by updates.state.collectAsState()
    val installResult by InstallResultReceiver.lastResult.collectAsState()

    Panel {
        when (val s = state) {
            is UpdateState.Available -> {
                StatusText("Build ${s.release.versionCode} is available.", Palette.Accent)
                BigButton("Install build ${s.release.versionCode}") { updates.install(s.release) }
            }
            UpdateState.NeedsPermission -> {
                StatusText(
                    "Android needs your OK first: tap the button, turn on \"Allow from this source\", then come back.",
                    Palette.Warn,
                )
                BigButton("Open install setting", onClick = onOpenInstallSetting)
            }
            is UpdateState.Downloading -> {
                StatusText("Downloading… ${(s.progress * 100).roundToInt()}%")
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
            UpdateState.Installing -> {
                StatusText(installResult ?: "Installing…")
                // Android has answered (confirm, or a failure): never leave the panel without a way on.
                if (installResult != null) BigButton("Check for update") { updates.check() }
            }
            else -> {
                when (s) {
                    UpdateState.Checking -> StatusText("Checking…")
                    is UpdateState.UpToDate -> StatusText("Up to date (latest is build ${s.latest}).", Palette.Accent)
                    is UpdateState.Failed -> StatusText(s.message, Palette.Warn)
                    else -> if (installResult != null) StatusText(installResult!!)
                }
                BigButton("Check for update", enabled = s != UpdateState.Checking) { updates.check() }
            }
        }
    }
}

@Composable
private fun ScreenPanel(
    readWindow: () -> WindowNumbers,
    contentW: Dp,
    contentH: Dp,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    // Re-read whenever the app area changes (e.g. after hiding the box's bars).
    // A second read shortly after start/toggle catches insets Android reports late.
    var settle by remember { mutableStateOf(0) }
    LaunchedEffect(fullScreen) {
        delay(800)
        settle++
    }
    val window = remember(contentW, contentH, fullScreen, settle) { readWindow() }
    val wPx = with(density) { contentW.toPx() }.roundToInt()
    val hPx = with(density) { contentH.toPx() }.roundToInt()
    val summary = "App area ${contentW.value.roundToInt()}×${contentH.value.roundToInt()} dp " +
        "($wPx×$hPx px, inside 16 dp padding) · " +
        "window ${window.windowWidthPx}×${window.windowHeightPx} px · " +
        "max ${window.maxWidthPx}×${window.maxHeightPx} px · " +
        "box bars L${window.barsLeft} T${window.barsTop} R${window.barsRight} B${window.barsBottom} px · " +
        "${config.densityDpi} dpi (scale ${"%.2f".format(density.density)}) · " +
        "font scale ${"%.2f".format(density.fontScale)}"
    var logged by remember { mutableStateOf("") }
    LaunchedEffect(summary) {
        if (summary != logged) {
            AppLog.info("screen", summary)
            logged = summary
        }
    }
    Panel {
        Text("Screen", color = Palette.TextDim, fontSize = 16.sp)
        Text(summary, color = Palette.Text, fontSize = 16.sp)
        BigButton(if (fullScreen) "Full screen: ON (tap to show bars)" else "Full screen: OFF (tap to hide bars)",
            onClick = onToggleFullScreen)
    }
}

@Composable
private fun LogViewer(modifier: Modifier) {
    val lines by AppLog.lines.collectAsState()
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Log (${lines.size} lines)", color = Palette.TextDim, fontSize = 18.sp)
            Button(
                onClick = { follow = !follow },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
            ) { Text(if (follow) "Following newest" else "Paused: tap to follow", fontSize = 16.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        line,
                        color = lineColor(line),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

private fun lineColor(line: String): Color {
    val kind = line.substringAfter(' ').substringAfter(' ').take(4).trim()
    return when (kind) {
        "ERR" -> Palette.Error
        "WARN" -> Palette.Warn
        "TX" -> Color(0xFF7CC4FF)
        "RX" -> Palette.Accent
        else -> Palette.Text
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun StatusText(text: String, color: Color = Palette.Text) {
    Text(text, color = color, fontSize = 18.sp)
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = LargeButtonHeight),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.SurfaceHigh,
            contentColor = Palette.Text,
            disabledContainerColor = Palette.Surface,
            disabledContentColor = Palette.TextDim,
        ),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}
