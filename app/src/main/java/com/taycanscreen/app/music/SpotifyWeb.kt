package com.taycanscreen.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify Web API for what the in-app connection can't do: search, and listing the songs/episodes
 * inside playlists, albums, shows and artists. Uses the access token from the "Allow in Spotify" step
 * (kept on the box only, about an hour long; renewed through the same Spotify screen).
 */
object SpotifyWeb {
    private const val TAG = "spotify"
    private const val PREFS = "spotify_web"
    private const val API = "https://api.spotify.com/v1"

    class TokenNeeded : IOException("Spotify sign-in needs renewing")

    private lateinit var app: Context
    private var token: String? = null
    private var expiresAt = 0L
    private val images = LruCache<String, Bitmap>(120)
    /** Bumped when a new token arrives, so a waiting search/list can retry. */
    val tokenVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = p.getString("token", null)
        expiresAt = p.getLong("expires_at", 0L)
    }

    fun setToken(accessToken: String, expiresInSec: Int) {
        token = accessToken
        expiresAt = System.currentTimeMillis() + (expiresInSec - 60) * 1000L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", accessToken).putLong("expires_at", expiresAt).apply()
        AppLog.info(TAG, "Web API token received (valid ${expiresInSec / 60} min)")
        tokenVersion.value++
    }

    val hasValidToken: Boolean get() = token != null && System.currentTimeMillis() < expiresAt

    suspend fun search(query: String): List<MusicItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = get("$API/search?q=$q&type=track,artist,album,playlist,show,episode&limit=8")
        val out = ArrayList<MusicItem>()
        json.optJSONObject("tracks")?.optJSONArray("items")?.objects()?.forEach { out.add(track(it, null, null)) }
        json.optJSONObject("artists")?.optJSONArray("items")?.objects()?.take(4)?.forEach {
            out.add(MusicItem(it.optString("name"), "Artist", it.optString("uri"), MusicItem.Kind.Artist, imageUrl = image(it)))
        }
        json.optJSONObject("albums")?.optJSONArray("items")?.objects()?.take(5)?.forEach {
            out.add(MusicItem(it.optString("name"), "Album · " + artists(it), it.optString("uri"), MusicItem.Kind.Album, imageUrl = image(it)))
        }
        json.optJSONObject("playlists")?.optJSONArray("items")?.objects()?.take(5)?.forEach {
            out.add(MusicItem(it.optString("name"), "Playlist · " + (it.optJSONObject("owner")?.optString("display_name") ?: ""), it.optString("uri"), MusicItem.Kind.Playlist, imageUrl = image(it)))
        }
        json.optJSONObject("shows")?.optJSONArray("items")?.objects()?.take(4)?.forEach {
            out.add(MusicItem(it.optString("name"), "Podcast · " + it.optString("publisher"), it.optString("uri"), MusicItem.Kind.Show, imageUrl = image(it)))
        }
        json.optJSONObject("episodes")?.optJSONArray("items")?.objects()?.take(4)?.forEach {
            out.add(MusicItem(it.optString("name"), "Episode · " + it.optString("release_date"), it.optString("uri"), MusicItem.Kind.Episode, imageUrl = image(it)))
        }
        AppLog.info(TAG, "Search \"$query\": ${out.size} results")
        return out
    }

    /** Songs / episodes inside a playlist, album, show or artist (top tracks). */
    suspend fun contents(item: MusicItem): List<MusicItem> {
        val id = item.uri.substringAfterLast(':')
        return when (item.kind) {
            MusicItem.Kind.Playlist -> get("$API/playlists/$id/tracks?limit=50").optJSONArray("items").objects()
                .mapNotNull { it.optJSONObject("track") }
                .mapIndexed { i, t -> if (t.optString("type") == "episode") episode(t, item.uri, i) else track(t, item.uri, i) }
            MusicItem.Kind.Album -> {
                val album = get("$API/albums/$id")
                val img = image(album)
                album.optJSONObject("tracks")?.optJSONArray("items").objects()
                    .mapIndexed { i, t -> track(t, item.uri, i).copy(imageUrl = img) }
            }
            MusicItem.Kind.Show -> get("$API/shows/$id/episodes?limit=50").optJSONArray("items").objects()
                .map { episode(it, null, null) }
            MusicItem.Kind.Artist -> get("$API/artists/$id/top-tracks?market=from_token").optJSONArray("tracks").objects()
                .map { track(it, null, null) }
            else -> emptyList()
        }
    }

    suspend fun image(url: String?): Bitmap? {
        url ?: return null
        images.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    inputStream.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()?.also { images.put(url, it) }
        }
    }

    /** Finds a podcast by name (and publisher, if given) via Web API search. Returns its spotify:show: URI. */
    suspend fun findShow(name: String, publisher: String?): String? {
        val q = URLEncoder.encode(name + (publisher?.let { " $it" } ?: ""), "UTF-8")
        val shows = get("$API/search?q=$q&type=show&limit=10").optJSONObject("shows")?.optJSONArray("items")?.objects() ?: emptyList()
        val pick = shows.firstOrNull {
            it.optString("name").contains(name, ignoreCase = true) &&
                (publisher == null || it.optString("publisher").contains(publisher, ignoreCase = true))
        } ?: shows.firstOrNull { it.optString("name").contains(name, ignoreCase = true) }
        AppLog.info(TAG, "Show \"$name\": ${pick?.let { "${it.optString("name")} by ${it.optString("publisher")} (${it.optString("uri")})" } ?: "not found"}")
        return pick?.optString("uri")?.takeIf { it.startsWith("spotify:show:") }
    }

    /** Newest episode of a show (Spotify lists them newest first). */
    suspend fun latestEpisode(showUri: String): MusicItem? {
        val id = showUri.substringAfterLast(':')
        val ep = get("$API/shows/$id/episodes?limit=1").optJSONArray("items")?.objects()?.firstOrNull() ?: return null
        return episode(ep, null, null)
    }

    private fun track(t: JSONObject, contextUri: String?, index: Int?) = MusicItem(
        title = t.optString("name"),
        subtitle = artists(t) + (t.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        uri = t.optString("uri"),
        kind = MusicItem.Kind.Track,
        imageUrl = t.optJSONObject("album")?.let { image(it) },
        contextUri = contextUri,
        indexInContext = index,
    )

    private fun episode(e: JSONObject, contextUri: String?, index: Int?) = MusicItem(
        title = e.optString("name"),
        subtitle = listOf(e.optString("release_date"), e.optJSONObject("show")?.optString("name").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
        uri = e.optString("uri"),
        kind = MusicItem.Kind.Episode,
        imageUrl = image(e),
        contextUri = contextUri,
        indexInContext = index,
    )

    private fun artists(o: JSONObject): String =
        o.optJSONArray("artists").objects().joinToString(", ") { it.optString("name") }

    private fun image(o: JSONObject): String? = o.optJSONArray("images").objects().lastOrNull { it.optInt("width", 0) >= 64 }?.optString("url")
        ?: o.optJSONArray("images").objects().firstOrNull()?.optString("url")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val t = token
        if (t == null || System.currentTimeMillis() >= expiresAt) throw TokenNeeded()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $t")
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                401 -> { token = null; throw TokenNeeded() }
                else -> {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    AppLog.warn(TAG, "Web API $code for ${url.substringAfter(API)}: $body")
                    throw IOException(if (code == 403 || code == 404) "Spotify doesn't share this list with apps" else "Spotify answered $code")
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
