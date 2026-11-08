package com.taycanscreen.app.music

import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem

/**
 * One row in the music browser, from either Spotify's in-app connection (App Remote, [listItem]) or
 * the Web API ([imageUrl]).
 */
data class MusicItem(
    val title: String,
    val subtitle: String,
    val uri: String,
    /** spotify:track / episode → play; playlist / album / show / artist → open; category → open via App Remote. */
    val kind: Kind,
    val imageUri: ImageUri? = null,
    val imageUrl: String? = null,
    val listItem: ListItem? = null,
    /** For a track inside a playlist/album: play it there so the rest follows. */
    val contextUri: String? = null,
    val indexInContext: Int? = null,
) {
    enum class Kind { Track, Episode, Playlist, Album, Show, Artist, Category, Other }

    val opens: Boolean get() = kind in setOf(Kind.Playlist, Kind.Album, Kind.Show, Kind.Artist, Kind.Category)

    companion object {
        fun kindOf(uri: String?): Kind = when {
            uri == null -> Kind.Other
            uri.startsWith("spotify:track:") -> Kind.Track
            uri.startsWith("spotify:episode:") -> Kind.Episode
            uri.contains(":playlist:") -> Kind.Playlist
            uri.startsWith("spotify:album:") -> Kind.Album
            uri.startsWith("spotify:show:") -> Kind.Show
            uri.startsWith("spotify:artist:") -> Kind.Artist
            else -> Kind.Other
        }

        fun from(li: ListItem): MusicItem {
            val kind = kindOf(li.uri).let { if (it == Kind.Other && li.hasChildren) Kind.Category else it }
            return MusicItem(
                title = li.title ?: "",
                subtitle = li.subtitle ?: "",
                uri = li.uri ?: "",
                kind = kind,
                imageUri = li.imageUri,
                listItem = li,
            )
        }
    }
}
