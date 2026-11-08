package com.taycanscreen.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.protocol.types.ListItem
import com.taycanscreen.app.music.SpotifyController

/** Design's "Now playing" card: cover, track, artist, previous / play-pause / next. Tap to browse. */
@Composable
fun NowPlayingCard(c: DriveColors, onOpenMusic: () -> Unit) {
    val status by SpotifyController.status.collectAsState()
    val np by SpotifyController.nowPlaying.collectAsState()
    val art by SpotifyController.artwork.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.chip)
            .clickable(onClick = onOpenMusic).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(art, 52.dp, c)
            Column(Modifier.weight(1f)) {
                val connected = status.link == SpotifyController.Link.Connected
                Text(
                    if (connected) np?.title ?: "Nothing playing" else "Spotify",
                    color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (connected) np?.artist?.ifBlank { null } ?: "Tap to browse" else status.detail.ifBlank { "Connecting…" },
                    color = c.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundControl(DesignIcons.Previous, "Previous track", c, filled = false) { SpotifyController.previous() }
            RoundControl(if (np?.paused == false) DesignIcons.Pause else DesignIcons.Play, if (np?.paused == false) "Pause" else "Play", c, filled = true) {
                SpotifyController.togglePlay()
            }
            RoundControl(DesignIcons.Next, "Next track", c, filled = false) { SpotifyController.next() }
        }
    }
}

/** In-app Spotify browser shown over the map: Spotify's car recommendations, drill into playlists, tap to play. */
@Composable
fun MusicPanel(c: DriveColors, onClose: () -> Unit, onOpenSpotifyApp: () -> Unit, modifier: Modifier = Modifier) {
    val status by SpotifyController.status.collectAsState()
    val stack = remember { mutableStateListOf<Pair<String, List<ListItem>>>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadRoot() {
        loading = true
        error = null
        SpotifyController.browseRoot(
            onResult = { stack.clear(); stack.add("Spotify" to it); loading = false },
            onError = { error = it; loading = false },
        )
    }
    LaunchedEffect(status.link) {
        if (status.link == SpotifyController.Link.Connected && stack.isEmpty()) loadRoot()
    }

    Column(modifier.background(c.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stack.size > 1) {
                RoundControl(DesignIcons.ChevronLeft, "Back", c, filled = false) { stack.removeAt(stack.lastIndex) }
            }
            Text(
                stack.lastOrNull()?.first ?: "Spotify",
                color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                "Open Spotify app", color = c.accent, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenSpotifyApp).padding(10.dp),
            )
            RoundControl(DesignIcons.Close, "Close music", c, filled = false, onClick = onClose)
        }
        when {
            status.link != SpotifyController.Link.Connected -> {
                Text(status.detail.ifBlank { "Connecting to Spotify…" }, color = c.textDim, fontSize = 17.sp)
                when (status.link) {
                    SpotifyController.Link.NeedsAuthorization -> Text(
                        "Allow in Spotify", color = c.accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { SpotifyController.requestAuthorization() }.padding(12.dp),
                    )
                    SpotifyController.Link.NeedsSignIn -> Text(
                        "Open Spotify to sign in", color = c.accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenSpotifyApp).padding(12.dp),
                    )
                    else -> Unit
                }
                if (status.link == SpotifyController.Link.Failed || status.link == SpotifyController.Link.NeedsSignIn) {
                    Text(
                        "Try again", color = c.accent, fontSize = 17.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { SpotifyController.retryNow() }.padding(10.dp),
                    )
                }
            }
            loading && stack.isEmpty() -> Text("Loading…", color = c.textDim, fontSize = 17.sp)
            error != null -> Text(error!!, color = c.danger, fontSize = 17.sp)
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(stack.lastOrNull()?.second.orEmpty()) { item ->
                    BrowseRow(item, c) {
                        if (item.hasChildren) {
                            loading = true
                            SpotifyController.browseChildren(
                                item,
                                onResult = { stack.add((item.title ?: "") to it); loading = false },
                                onError = { error = it; loading = false },
                            )
                        } else if (item.playable) {
                            SpotifyController.play(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(item: ListItem, c: DriveColors, onClick: () -> Unit) {
    var thumb by remember(item.imageUri?.raw) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUri?.raw) { SpotifyController.thumbnail(item.imageUri) { thumb = it } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(thumb, 52.dp, c)
        Column(Modifier.weight(1f)) {
            Text(item.title ?: "", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!item.subtitle.isNullOrBlank()) {
                Text(item.subtitle, color = c.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.playable && !item.hasChildren) {
            Icon(DesignIcons.Play, contentDescription = "Play", tint = c.accent, modifier = Modifier.size(22.dp))
        } else if (item.hasChildren) {
            Icon(DesignIcons.ChevronRight, contentDescription = null, tint = c.textDim, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Dp, c: DriveColors) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.track),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(DesignIcons.Media, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, description: String, c: DriveColors, filled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) c.text else Color.Transparent,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = if (filled) c.panel else c.text, modifier = Modifier.size(22.dp))
        }
    }
}
