package com.taycanscreen.app.music

import android.content.Context
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-tap podcasts (owner, 2026-11-09: "Up First" from NPR). The show is found once through Spotify's
 * search and remembered; each tap plays its newest episode, or the show itself if the Web API sign-in
 * has lapsed (it lasts about an hour).
 */
object QuickPlay {
    private const val TAG = "spotify"

    data class Show(val key: String, val label: String, val name: String, val publisher: String?)

    val UP_FIRST = Show("up_first", "Up First", "Up First", "NPR")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _busy = MutableStateFlow<String?>(null)
    /** Key of the show being looked up, for a "…" on its button. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    fun play(context: Context, show: Show) {
        if (_busy.value != null) return
        val prefs = context.applicationContext.getSharedPreferences("quick_play", Context.MODE_PRIVATE)
        _busy.value = show.key
        _message.value = null
        scope.launch {
            try {
                var uri = prefs.getString(show.key, null)
                if (uri == null || SpotifyWeb.hasValidToken) {
                    if (!SpotifyWeb.hasValidToken) {
                        // First time: needs the Web API sign-in (Spotify's own screen), then carries on.
                        AppLog.info(TAG, "${show.label}: asking Spotify for sign-in to look the show up")
                        val v = SpotifyWeb.tokenVersion.value
                        SpotifyController.requestAuthorization()
                        withTimeoutOrNull(60_000) { SpotifyWeb.tokenVersion.first { it != v } }
                    }
                    if (SpotifyWeb.hasValidToken) {
                        if (uri == null) {
                            uri = SpotifyWeb.findShow(show.name, show.publisher)
                            if (uri != null) prefs.edit().putString(show.key, uri).apply()
                        }
                        val ep = uri?.let { SpotifyWeb.latestEpisode(it) }
                        if (ep != null) {
                            AppLog.info(TAG, "${show.label}: playing newest episode \"${ep.title}\"")
                            SpotifyController.play(ep)
                            return@launch
                        }
                    }
                }
                if (uri != null) {
                    AppLog.info(TAG, "${show.label}: playing the show ($uri)")
                    SpotifyController.play(MusicItem(show.label, "Podcast", uri, MusicItem.Kind.Show))
                } else {
                    _message.value = "Couldn't find ${show.label} on Spotify (sign-in needed or no internet)."
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "${show.label} failed: ${e.javaClass.simpleName}: ${e.message}")
                val uri = prefs.getString(show.key, null)
                if (uri != null) SpotifyController.play(MusicItem(show.label, "Podcast", uri, MusicItem.Kind.Show))
                else _message.value = "Couldn't reach Spotify for ${show.label}: ${e.message}"
            } finally {
                _busy.value = null
            }
        }
    }
}
