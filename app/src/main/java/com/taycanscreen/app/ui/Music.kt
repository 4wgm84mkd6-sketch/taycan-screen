package com.taycanscreen.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.music.MusicItem
import com.taycanscreen.app.music.SpotifyController
import com.taycanscreen.app.music.SpotifyWeb
import kotlinx.coroutines.launch

private const val RENEW = "Renewing Spotify sign-in…"

/**
 * In-app Spotify over the map: Now playing, Browse (Spotify's car picks; playlists, albums, podcasts and
 * artists open to their songs/episodes) and Search (Web API).
 */
@Composable
fun MusicPanel(
    c: DriveColors,
    onClose: () -> Unit,
    onOpenSpotifyApp: () -> Unit,
    modifier: Modifier = Modifier,
    onViewChange: (String) -> Unit = {},
) {
    val status by SpotifyController.status.collectAsState()
    val tokenVersion by SpotifyWeb.tokenVersion.collectAsState()
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Pair<String, List<MusicItem>>>() }
    var view by remember { mutableStateOf("now") } // now | browse
    LaunchedEffect(view) { onViewChange(view) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // Spotify search uses the same large keyboard as destination search (it used the box's small one).
    var typing by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(true) }
    var numbers by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val online by com.taycanscreen.app.net.NetMonitor.online.collectAsState()

    /** Turns technical errors into something readable in the car. */
    fun friendly(msg: String?): String {
        val m = msg ?: ""
        return when {
            "UnknownHost" in m || "Unable to resolve host" in m || "ConnectException" in m || "SocketTimeout" in m || "timeout" in m.lowercase() ->
                "No internet right now. Browsing and search need a connection; what's playing keeps playing."
            "SSL" in m || "Chain validation" in m -> "The box's clock isn't set yet. Try again in a minute."
            m.startsWith("Spotify ") -> m // already written for the driver
            else -> "Couldn't load that. Try again."
        }
    }

    /** Runs a Web API call; if the sign-in needs renewing, renews it and retries once it's back. */
    fun web(title: String, call: suspend () -> List<MusicItem>) {
        loading = true
        error = null
        scope.launch {
            try {
                val items = call()
                stack.add(title to items)
                if (items.isEmpty()) error = "This list is empty."
            } catch (e: SpotifyWeb.TokenNeeded) {
                // Spotify's sign-in for search lasts an hour; its own screen renews it (a moment, after
                // this tap). If that doesn't finish, the Sign in button next to the message tries again.
                pending = { web(title, call) }
                error = RENEW
                SpotifyController.requestAuthorization()
            } catch (e: Exception) {
                error = friendly(e.message)
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(tokenVersion) { pending?.let { pending = null; it() } }

    fun loadRoot() {
        com.taycanscreen.app.log.AppLog.info("spotify", "Browse: loading Spotify home (online=${com.taycanscreen.app.net.NetMonitor.online.value})")
        loading = true
        error = null
        SpotifyController.browseRoot(
            onResult = { list -> stack.clear(); stack.add("Spotify" to list.map { MusicItem.from(it) }); loading = false },
            onError = { error = friendly(it); loading = false },
        )
    }
    LaunchedEffect(status.link, view) {
        if (view == "browse" && status.link == SpotifyController.Link.Connected && stack.isEmpty()) loadRoot()
    }

    fun open(item: MusicItem) {
        when {
            // Spotify's own categories ("Recently played", "Made for you"…) come from the in-app connection.
            item.kind == MusicItem.Kind.Category || (item.listItem != null && item.kind == MusicItem.Kind.Other) -> {
                loading = true
                error = null
                SpotifyController.browseChildren(
                    item,
                    onResult = { stack.add(item.title to it); loading = false },
                    onError = { error = friendly(it); loading = false },
                )
            }
            // Containers: ask the Spotify app first (it can list Spotify-made playlists); Web API as backup.
            item.opens -> {
                loading = true
                error = null
                SpotifyController.listViaApp(
                    item,
                    onResult = { list ->
                        loading = false
                        if (list.isNotEmpty()) stack.add(item.title to list)
                        else web(item.title) { SpotifyWeb.contents(item) }
                    },
                    onError = { loading = false; web(item.title) { SpotifyWeb.contents(item) } },
                )
            }
            else -> SpotifyController.play(item)
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        typing = false
        view = "browse"
        web("“$q”") { SpotifyWeb.search(q) }
    }

    Column(modifier.background(c.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val connected = status.link == SpotifyController.Link.Connected
        // Top row: back, title (or the search line while typing), Now playing / Browse, search, close.
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (typing) {
                QueryField(query, "Search Spotify", c, Modifier.weight(1f).fillMaxHeight()) { query = ""; shift = true }
            } else {
                if (view == "browse" && stack.size > 1) {
                    SquareButton(FIcons.ChevronLeft, "Back", c) { stack.removeAt(stack.lastIndex); error = null }
                }
                BasicText(
                    if (view == "browse") stack.lastOrNull()?.first ?: "Spotify" else "Now playing",
                    Modifier.weight(1f).padding(start = 4.dp),
                    style = Filament.text(26.sp, 600, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (connected) {
                    Tab("NOW PLAYING", view == "now", c) { view = "now" }
                    Tab("BROWSE", view == "browse", c) { view = "browse" }
                    SquareButton(FIcons.Search, "Search Spotify", c) { typing = true; view = "browse" }
                }
            }
            SquareButton(FIcons.Close, if (typing) "Close search" else "Close music", c) { if (typing) typing = false else onClose() }
        }
        when {
            !connected -> Column(Modifier.fillMaxSize().padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(status.detail.ifBlank { "Connecting to Spotify…" }, style = Filament.text(22.sp, 500, c.textDim))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (status.link) {
                        SpotifyController.Link.NeedsAuthorization -> WideButton("Allow in Spotify", c, primary = true) { SpotifyController.requestAuthorization() }
                        SpotifyController.Link.NeedsSignIn, SpotifyController.Link.NotInstalled -> WideButton("Open Spotify", c, primary = true, onClick = onOpenSpotifyApp)
                        else -> Unit
                    }
                    if (status.detail == SpotifyController.CLOCK_WAIT) {
                        // The box sets its clock by itself once it is online, but that can take minutes.
                        // In its date settings, switching automatic time off and on makes it ask again now.
                        val context = androidx.compose.ui.platform.LocalContext.current
                        WideButton("Date & time settings", c, primary = true) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { com.taycanscreen.app.log.AppLog.warn("spotify", "Couldn't open date settings: ${it.message}") }
                        }
                    }
                    if (status.link != SpotifyController.Link.NotSetUp) WideButton("Try again", c) { SpotifyController.retryNow() }
                }
            }
            view == "now" && !typing -> NowPlayingView(c, Modifier.fillMaxSize())
            else -> {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!online) BasicText("No internet. What's playing keeps playing.", style = Filament.text(17.sp, 500, c.warn))
                    if (loading) BasicText("LOADING…", Modifier.padding(start = 4.dp), style = Filament.label(13.sp, c.textDim, 0.14f))
                    error?.let { msg ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            BasicText(msg, Modifier.weight(1f, fill = false), style = Filament.text(17.sp, 500, if (msg == RENEW || msg.startsWith("This list")) c.textDim else c.warn), maxLines = 2)
                            if (msg == RENEW) WideButton("Sign in", c, primary = true) { SpotifyController.requestAuthorization() }
                            else if (!loading && !msg.startsWith("This list")) WideButton("Try again", c) {
                                if (stack.isEmpty()) loadRoot() else if (query.isNotBlank()) search() else error = null
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Quick play at the top of Spotify's home list (owner: Up First lives here, not on the Drive panel).
                        if (stack.size <= 1 && !typing) item { QuickPlayRow(com.taycanscreen.app.music.QuickPlay.UP_FIRST, "NPR · newest episode", c) }
                        items(stack.lastOrNull()?.second.orEmpty()) { item ->
                            BrowseRow(item, c, onOpen = { open(item) }, onPlay = { SpotifyController.play(item) })
                        }
                    }
                }
                if (typing) {
                    Keyboard(
                        c, shift, numbers,
                        onChar = { ch -> query += if (shift) ch.uppercase() else ch; shift = false },
                        onBackspace = { query = query.dropLast(1); if (query.isEmpty()) shift = true },
                        onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                        onShift = { shift = !shift },
                        onLayer = { numbers = !numbers },
                        onGo = { search() },
                        goLabel = "Search",
                    )
                }
            }
        }
    }
}

/** Square icon button, 72 × 64 like the one that closes destination search. */
@Composable
private fun SquareButton(icon: ImageVector, label: String, c: DriveColors, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp, 64.dp).card(c.raised, c.border).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.text, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun Tab(label: String, on: Boolean, c: DriveColors, onClick: () -> Unit) {
    Box(
        Modifier.height(64.dp).card(if (on) c.accentSoft else Color.Transparent, if (on) c.accentLine else c.border)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.label(14.sp, if (on) c.accent else c.textDim, 0.06f, 500)) }
}

@Composable
private fun WideButton(label: String, c: DriveColors, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(64.dp).widthIn(min = 150.dp).card(if (primary) c.accent else c.raised, if (primary) c.accent else c.lineStrong).clickable(onClick = onClick).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = Filament.text(20.sp, 600, if (primary) c.onAccent else c.text), maxLines = 1) }
}

/** Big "now playing": cover art, title, artist, album, progress, large controls. */
@Composable
private fun NowPlayingView(c: DriveColors, modifier: Modifier) {
    val np by SpotifyController.nowPlaying.collectAsState()
    val art by SpotifyController.artwork.collectAsState()
    var now by remember { mutableLongStateOf(com.taycanscreen.app.util.Mono.ms()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = com.taycanscreen.app.util.Mono.ms()
            kotlinx.coroutines.delay(500)
        }
    }
    BoxWithConstraints(modifier) {
        val coverSize = minOf(maxHeight, maxWidth * 0.42f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Cover(art, coverSize, c)
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = np
                BasicText(p?.title ?: "Nothing playing", style = Filament.text(38.sp, 600, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    BasicText(p.artist, style = Filament.text(26.sp, 500, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(p.album, style = Filament.text(19.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Progress: Spotify reports the position at each change; move it along between updates.
                    val pos = (if (p.paused) p.positionMs else p.positionMs + (now - p.positionAt)).coerceIn(0L, p.durationMs.coerceAtLeast(0L))
                    val frac = if (p.durationMs > 0) (pos.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(c.border)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.text))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        BasicText(mmss(pos), Modifier.weight(1f), style = Filament.label(14.sp, c.textDim, 0f))
                        BasicText(mmss(p.durationMs), style = Filament.label(14.sp, c.textDim, 0f))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val playing = np?.paused == false
                    RoundButton(FIcons.Prev, "Previous track", c, 88, false) { SpotifyController.previous() }
                    RoundButton(if (playing) FIcons.Pause else FIcons.Play, if (playing) "Pause" else "Play", c, 108, true) { SpotifyController.togglePlay() }
                    RoundButton(FIcons.Next, "Next track", c, 88, false) { SpotifyController.next() }
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
private fun QuickPlayRow(show: com.taycanscreen.app.music.QuickPlay.Show, subtitle: String, c: DriveColors) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy by com.taycanscreen.app.music.QuickPlay.busy.collectAsState()
    val msg by com.taycanscreen.app.music.QuickPlay.message.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).card(c.raised, c.accentLine)
                .clickable { com.taycanscreen.app.music.QuickPlay.play(context, show) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(FIcons.Play, null, tint = c.accent, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f)) {
                BasicText(show.label, style = Filament.text(22.sp, 600, c.text), maxLines = 1)
                BasicText(subtitle, style = Filament.text(17.sp, 400, c.textDim), maxLines = 1)
            }
            if (busy == show.key) BasicText("STARTING…", style = Filament.label(13.sp, c.accent, 0.1f))
        }
        msg?.let {
            BasicText(it, Modifier.clickable { com.taycanscreen.app.music.QuickPlay.clearMessage() }.padding(8.dp), style = Filament.text(17.sp, 500, c.warn))
        }
    }
}

@Composable
private fun BrowseRow(item: MusicItem, c: DriveColors, onOpen: () -> Unit, onPlay: () -> Unit) {
    var thumb by remember(item.uri, item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri, item.imageUrl) {
        if (item.imageUrl != null) thumb = SpotifyWeb.image(item.imageUrl)
        else SpotifyController.thumbnail(item.imageUri) { thumb = it }
    }
    Row(
        Modifier.fillMaxWidth().height(80.dp).background(c.raised).clickable(onClick = onOpen).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(thumb, 56.dp, c)
        Column(Modifier.weight(1f)) {
            BasicText(item.title, style = Filament.text(22.sp, 600, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) {
                BasicText(item.subtitle, style = Filament.text(17.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.opens) {
            // Lists: tap the row to open it; the play button at the end plays all of it.
            if (item.kind != MusicItem.Kind.Category) {
                Box(Modifier.fillMaxHeight().width(88.dp).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).border(1.dp, c.accentLine, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Icon(FIcons.Play, "Play all", tint = c.accent, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Icon(FIcons.ChevronRight, null, tint = c.textDim, modifier = Modifier.padding(end = 16.dp).size(26.dp))
        } else {
            Icon(FIcons.Play, "Play", tint = c.accent, modifier = Modifier.padding(end = 22.dp).size(24.dp))
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Dp, c: DriveColors) {
    Box(Modifier.size(size).background(c.coverBg), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(FIcons.Music, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(size / 3))
        }
    }
}
