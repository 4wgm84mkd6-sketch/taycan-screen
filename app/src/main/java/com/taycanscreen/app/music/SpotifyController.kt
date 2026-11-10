package com.taycanscreen.app.music

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import com.taycanscreen.app.BuildConfig
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify inside Taycan Screen via Spotify's App Remote SDK: now playing, playback controls and
 * browsing, while the Spotify app plays the audio in the background (no app switching).
 * One instance for the whole process. All SDK callbacks arrive on the main thread.
 */
object SpotifyController {
    private const val TAG = "spotify"
    private const val RETRY_MS = 15_000L
    /** Spotify refusing an app it allowed before (cold start, clock not set, no internet yet): keep asking quietly. */
    private const val AUTH_RETRY_MS = 20_000L

    enum class Link { NotSetUp, NotInstalled, NeedsSignIn, NeedsAuthorization, Connecting, Connected, Failed }
    data class Status(val link: Link, val detail: String = "")

    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String,
        val paused: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val positionAt: Long,
        val imageUri: ImageUri?,
    )

    private val _status = MutableStateFlow(Status(Link.Connecting))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
    private val _artwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

    private lateinit var appContext: Context
    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private var artUri: String? = null
    /** The cover being fetched, so player updates don't start a second fetch of the same one. */
    private var artLoading: String? = null
    private var globalEqScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(80)
    private val retry = Runnable { connect(showAuth = false) }

    /** One pending reconnect at a time (several overlapping retry chains were piling up). */
    private fun scheduleRetry(ms: Long) {
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, ms)
    }

    val configured: Boolean get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    /** Call once from the activity; safe to call again (e.g. after the owner taps Connect). */
    fun start(context: Context) {
        val first = !::appContext.isInitialized
        appContext = context.applicationContext
        connect(showAuth = true)
        if (first) {
            // Cold start: the box often has no internet for the first minute, and Spotify can't
            // authenticate offline. Try again the moment the connection comes up.
            kotlinx.coroutines.MainScope().launch {
                com.taycanscreen.app.net.NetMonitor.online.collect { online ->
                    if (online && remote?.isConnected != true && !connecting) {
                        AppLog.info(TAG, "Internet is up: reconnecting to Spotify")
                        handler.removeCallbacks(retry)
                        connect(showAuth = _status.value.link == Link.NeedsAuthorization && authorizedBefore())
                    }
                }
            }
        }
    }

    private fun prefs() = appContext.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    // Default true: this box has been allowed before (2026-11-08); a fresh install would need the same screen anyway.
    private fun authorizedBefore() = prefs().getBoolean("authorized_before", true)
    private var lastAutoAuthAt = 0L

    /** Set by the activity: opens Spotify's own "allow Taycan Screen" screen. */
    var authorizationLauncher: (() -> Unit)? = null

    fun requestAuthorization() {
        AppLog.info(TAG, "Opening Spotify authorization")
        authorizationLauncher?.invoke()
    }

    /** Result of Spotify's authorization screen ([error] null = allowed). */
    fun onAuthorizationResult(error: String?) {
        if (error == null) {
            AppLog.info(TAG, "Spotify authorization granted")
            connect(showAuth = false)
        } else {
            AppLog.warn(TAG, "Spotify authorization failed: $error")
            // A lapsed search sign-in mustn't hide a player that is connected and working.
            if (remote?.isConnected != true) _status.value = Status(Link.NeedsAuthorization, "Spotify needs your OK. Tap Allow.")
        }
    }

    fun retryNow() {
        if (::appContext.isInitialized) connect(showAuth = true)
    }

    private fun connect(showAuth: Boolean) {
        if (!configured) {
            _status.value = Status(Link.NotSetUp, "Spotify isn't available in this version")
            return
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(appContext)) {
            _status.value = Status(Link.NotInstalled, "Install Spotify on the box and sign in")
            return
        }
        if (remote?.isConnected == true) {
            _status.value = Status(Link.Connected)
            return
        }
        if (connecting) return
        drop()
        connecting = true
        _status.value = Status(Link.Connecting, "Connecting to Spotify…")
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(showAuth)
            .build()
        SpotifyAppRemote.connect(appContext, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                connecting = false
                remote = r
                _status.value = Status(Link.Connected)
                AppLog.info(TAG, "Connected to Spotify")
                prefs().edit().putBoolean("authorized_before", true).apply()
                com.taycanscreen.app.ui.Boot.mark("Spotify connected")
                subscribe(r)
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                drop()
                val name = error.javaClass.simpleName
                AppLog.warn(TAG, "Spotify connect failed: $name: ${error.message}")
                val msg = (error.message ?: "").lowercase()
                _status.value = when {
                    name == "NotLoggedInException" || "log-in" in msg || "logged in" in msg ->
                        Status(Link.NeedsSignIn, "Open the Spotify app on the box and sign in")
                    name == "UserNotAuthorizedException" || "authorization" in msg ->
                        Status(Link.NeedsAuthorization, "Tap Allow to let Taycan Screen use Spotify")
                    name == "CouldNotFindSpotifyApp" -> Status(Link.NotInstalled, "Install Spotify on the box and sign in")
                    else -> Status(Link.Failed, "Spotify isn't responding. Trying again…")
                }
                // Spotify sometimes asks again for an app the owner already allowed (seen after a cold
                // start). Its own screen passes an already-allowed app straight through, so show it
                // automatically (at most every 10 minutes, and only with internet).
                if (_status.value.link == Link.NeedsAuthorization) {
                    val now = com.taycanscreen.app.util.Mono.ms()
                    // Never put Spotify's screen in front of the driver: only while the car is known to be standing.
                    val standing = com.taycanscreen.app.obd.Taycan.speedKmh(com.taycanscreen.app.obd.ObdEngine.readings.value)?.let { it < 1.0 } == true
                    if (authorizedBefore() && standing && com.taycanscreen.app.net.NetMonitor.online.value &&
                        (lastAutoAuthAt == 0L || now - lastAutoAuthAt > 600_000)
                    ) {
                        lastAutoAuthAt = now
                        AppLog.info(TAG, "Spotify asks to allow the app again; it was allowed before and the car is standing, so retrying with Spotify's own screen")
                        handler.postDelayed({ connect(showAuth = true) }, 2_000)
                    } else if (authorizedBefore()) {
                        // After a cold start Spotify refused three times out of three and nothing tried
                        // again. Keep asking quietly; it comes good once Spotify itself is online.
                        _status.value = Status(Link.Connecting, "Connecting to Spotify…")
                        scheduleRetry(AUTH_RETRY_MS)
                    }
                } else if (_status.value.link != Link.NotInstalled) {
                    scheduleRetry(RETRY_MS)
                }
            }
        })
    }

    private fun subscribe(r: SpotifyAppRemote) {
        r.playerApi.subscribeToPlayerState()
            .setEventCallback { ps ->
                val t = ps.track
                // If Spotify plays but never announced its audio session, fall back to the global EQ.
                if (!ps.isPaused && !globalEqScheduled) {
                    globalEqScheduled = true
                    handler.postDelayed({ com.taycanscreen.app.audio.EqEngine.tryGlobal() }, 15_000L)
                }
                _nowPlaying.value = if (t == null) null else NowPlaying(
                    title = t.name ?: "",
                    artist = t.artist?.name ?: t.artists?.joinToString { it.name } ?: "",
                    album = t.album?.name ?: "",
                    paused = ps.isPaused,
                    durationMs = t.duration,
                    positionMs = ps.playbackPosition,
                    positionAt = com.taycanscreen.app.util.Mono.ms(),
                    imageUri = t.imageUri,
                )
                val uri = t?.imageUri
                if (uri == null) {
                    artUri = null
                    _artwork.value = null
                } else if (uri.raw != artUri || (_artwork.value == null && artLoading != uri.raw)) {
                    artUri = uri.raw
                    artLoading = uri.raw
                    _artwork.value = null // don't show the previous song's cover meanwhile
                    loadArtwork(r, uri, attempt = 1)
                }
            }
            .setErrorCallback { e ->
                AppLog.warn(TAG, "Spotify player updates stopped: ${e.message}; reconnecting")
                drop()
                _status.value = Status(Link.Failed, "Spotify isn't responding. Trying again…")
                scheduleRetry(3_000L)
            }
    }

    /** Lets go of the Spotify link properly (it used to be forgotten while still open). */
    private fun drop() {
        remote?.let { r -> runCatching { SpotifyAppRemote.disconnect(r) } }
        remote = null
    }

    /** Cover art for the current track, retried a few times (the image call can fail while Spotify is busy). */
    private fun loadArtwork(r: SpotifyAppRemote, uri: ImageUri, attempt: Int) {
        r.imagesApi.getImage(uri, Image.Dimension.LARGE)
            .setResultCallback { bmp ->
                if (artUri == uri.raw) _artwork.value = bmp
                if (artLoading == uri.raw) artLoading = null
            }
            .setErrorCallback { e ->
                AppLog.warn(TAG, "Artwork failed (try $attempt): ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < 4 && artUri == uri.raw) {
                    handler.postDelayed({ remote?.let { loadArtwork(it, uri, attempt + 1) } ?: run { artLoading = null } }, 1_500L * attempt)
                } else if (artLoading == uri.raw) {
                    artLoading = null
                }
            }
    }

    private fun withRemote(what: String, onFail: ((String) -> Unit)? = null, block: (SpotifyAppRemote) -> Unit) {
        val r = remote
        if (r == null || !r.isConnected) {
            AppLog.warn(TAG, "$what: not connected")
            retryNow()
            onFail?.invoke("Spotify isn't connected yet. Try again in a moment.")
            return
        }
        block(r)
    }

    fun togglePlay() = withRemote("play/pause") { r ->
        if (_nowPlaying.value?.paused != false) r.playerApi.resume() else r.playerApi.pause()
    }

    fun next() = withRemote("next") { it.playerApi.skipNext() }
    fun previous() = withRemote("previous") { it.playerApi.skipPrevious() }

    /** Spotify's own car-friendly starting points (recently played, playlists, made for you…). */
    fun browseRoot(onResult: (List<ListItem>) -> Unit, onError: (String) -> Unit) = withRemote("browse", onError) { r ->
        r.contentApi.getRecommendedContentItems(ContentApi.ContentType.AUTOMOTIVE)
            .setResultCallback { onResult(it.items?.toList().orEmpty()) }
            .setErrorCallback { onError(it.message ?: "Couldn't load Spotify") }
    }

    fun browseChildren(item: ListItem, onResult: (List<ListItem>) -> Unit, onError: (String) -> Unit) =
        withRemote("browse ${item.title}", onError) { r ->
            r.contentApi.getChildrenOfItem(item, 50, 0)
                .setResultCallback { onResult(it.items?.toList().orEmpty()) }
                .setErrorCallback { onError(it.message ?: "Couldn't load ${item.title}") }
        }

    /** Plays a song/episode (inside its playlist/album when known) or a whole playlist/album/show/artist. */
    fun play(item: MusicItem) = withRemote("play ${item.title}") { r ->
        AppLog.info(TAG, "Play ${item.title} (${item.uri}${item.contextUri?.let { " in $it #${item.indexInContext}" } ?: ""})")
        val call = when {
            item.contextUri != null && item.indexInContext != null -> r.playerApi.skipToIndex(item.contextUri, item.indexInContext)
            // Items that came from the Spotify app's own lists play there, keeping their playlist/album.
            item.listItem != null && item.kind != MusicItem.Kind.Playlist && item.kind != MusicItem.Kind.Album &&
                item.kind != MusicItem.Kind.Show && item.kind != MusicItem.Kind.Artist -> r.contentApi.playContentItem(item.listItem)
            else -> r.playerApi.play(item.uri)
        }
        call.setErrorCallback { AppLog.warn(TAG, "Play failed: ${it.message}") }
    }

    /**
     * Lists a playlist/album/show through the Spotify app on the box (not the Web API, which refuses
     * Spotify-made playlists). Builds a browse item for any URI; the app may or may not answer.
     */
    fun listViaApp(item: MusicItem, onResult: (List<MusicItem>) -> Unit, onError: (String) -> Unit) {
        val li = item.listItem ?: ListItem(item.uri, item.uri, item.imageUri, item.title, item.subtitle, true, true)
        browseChildren(li, { list -> onResult(list.map { MusicItem.from(it) }) }, onError)
    }

    /** Children through the in-app connection (Spotify's own browse tree). */
    fun browseChildren(item: MusicItem, onResult: (List<MusicItem>) -> Unit, onError: (String) -> Unit) {
        val li = item.listItem ?: return onResult(emptyList())
        browseChildren(li, { list -> onResult(list.map { MusicItem.from(it) }) }, onError)
    }

    /** Small cover image for a list row; cached. */
    fun thumbnail(uri: ImageUri?, onResult: (Bitmap) -> Unit) {
        val key = uri?.raw ?: return
        thumbs.get(key)?.let { onResult(it); return }
        val r = remote ?: return
        r.imagesApi.getImage(uri, Image.Dimension.THUMBNAIL)
            .setResultCallback { bmp -> thumbs.put(key, bmp); onResult(bmp) }
            .setErrorCallback { }
    }
}
