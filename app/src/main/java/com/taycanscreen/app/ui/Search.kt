package com.taycanscreen.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.nav.LatLon
import com.taycanscreen.app.nav.Nav
import com.taycanscreen.app.nav.NavApi
import com.taycanscreen.app.nav.Place
import com.taycanscreen.app.nav.RouteMath
import kotlinx.coroutines.delay

/**
 * Destination search over the map (design px): a search line, results as you type (Photon, biased
 * to the car's position), recent places, and our own large on-screen keyboard.
 *
 * Owner's decision (2026-11-10): every feature works at any speed. The keyboard is never locked.
 */
@Composable
fun SearchPanel(
    c: DriveColors,
    near: LatLon?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** "home" / "work": the next place picked is saved as that instead of routed to. */
    initialSaveAs: String? = null,
) {
    var saveAs by remember(initialSaveAs) { mutableStateOf(initialSaveAs) }
    val saved by Nav.saved.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf(true) }
    var numbers by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val recents by Nav.recents.collectAsState()
    val online by com.taycanscreen.app.net.NetMonitor.online.collectAsState()

    LaunchedEffect(query) {
        searchError = null
        val q = query.trim()
        if (q.length < 3) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(350) // wait for a pause in typing
        searching = true
        try {
            results = NavApi.search(q, near)
            AppLog.info("nav", "Search \"$q\": ${results.size} result(s)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn("nav", "Search failed: ${e.javaClass.simpleName}: ${e.message}")
            searchError = Nav.friendly(e)
        } finally {
            searching = false
        }
    }

    fun pick(p: Place) {
        saveAs?.let { kind ->
            Nav.save(kind, p)
            android.widget.Toast.makeText(context, "${kind.replaceFirstChar { it.uppercase() }} set to ${p.name}", android.widget.Toast.LENGTH_LONG).show()
            onClose()
            return
        }
        AppLog.info("nav", "Destination picked: ${p.name} (${p.detail})")
        Nav.start(p, near ?: Nav.lastPosition())
        onClose()
    }

    Column(modifier.background(c.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QueryField(query, "Where to?", c, Modifier.weight(1f).fillMaxHeight()) { query = ""; shift = true }
            Box(
                Modifier.size(72.dp, 64.dp).background(c.raised, Filament.CutS).insetLine(c.border).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(FIcons.Close, "Close search", tint = c.text, modifier = Modifier.size(28.dp)) }
        }
        val message = when {
            saveAs != null -> null
            !online -> "No internet right now. Search and routes need a connection."
            searchError != null -> searchError
            near == null -> "Waiting for GPS: results and routes start from the car's position."
            else -> null
        }
        if (message != null) BasicText(message, style = Filament.text(16.sp, 500, c.warn))
        saveAs?.let { kind ->
            BasicText(
                "Set ${kind.replaceFirstChar { it.uppercase() }}: search for the address, or pick a recent place.",
                style = Filament.text(18.sp, 600, c.accent),
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val showRecents = query.trim().length < 3
            val list = if (showRecents) recents else results
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Home and Work for one tap (owner, 2026-11-10), side by side so recent places still fit
                // above the keyboard. EDIT picks a new place.
                if (showRecents && saveAs == null) {
                    item(key = "saved") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (kind in listOf("home", "work")) {
                                SavedRow(kind, saved[kind], near, c, Modifier.weight(1f), onGo = { saved[kind]?.let { pick(it) } ?: run { saveAs = kind } }, onEdit = { saveAs = kind })
                            }
                        }
                    }
                }
                // A caption only when it says something the rows don't.
                val caption = when {
                    showRecents -> if (recents.isEmpty()) null else "RECENT"
                    searching -> "SEARCHING…"
                    results.isEmpty() -> "NO MATCHES"
                    else -> null
                }
                if (caption != null) item(key = "caption") {
                    BasicText(caption, Modifier.padding(start = 4.dp, top = 2.dp), style = Filament.label(13.sp, c.textDim, 0.14f))
                }
                items(list, key = { "${it.name}|${it.lat}|${it.lon}" }) { p ->
                    PlaceRow(p, if (showRecents) FIcons.Recent else FIcons.Pin, near, c) { pick(p) }
                }
            }
        }

        run {
            Keyboard(
                c, shift, numbers,
                onChar = { ch ->
                    query += if (shift) ch.uppercase() else ch
                    shift = false
                },
                onBackspace = {
                    query = query.dropLast(1)
                    if (query.isEmpty()) shift = true
                },
                onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                onShift = { shift = !shift },
                onLayer = { numbers = !numbers },
                onGo = { results.firstOrNull()?.let { pick(it) } },
            )
        }
    }
}

/** The typed text with a blinking caret and a clear button (destination search and Spotify search). */
@Composable
internal fun QueryField(query: String, placeholder: String, c: DriveColors, modifier: Modifier, onClear: () -> Unit) {
    val caret by rememberInfiniteTransition(label = "caret").animateFloat(
        0f, 1f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    Row(
        modifier.background(c.raised, Filament.CutS).insetLine(c.accentLine).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(FIcons.Search, null, tint = c.accent, modifier = Modifier.size(28.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (query.isEmpty()) {
                Box(Modifier.width(3.dp).height(32.dp).alpha(if (caret > 0.5f) 1f else 0f).background(c.accent))
                BasicText("  $placeholder", style = Filament.text(26.sp, 400, c.textFaint))
            } else {
                BasicText(query, Modifier.weight(1f, fill = false), style = Filament.text(26.sp, 500, c.text), maxLines = 1, overflow = TextOverflow.Clip)
                Box(Modifier.width(3.dp).height(32.dp).alpha(if (caret > 0.5f) 1f else 0f).background(c.accent))
            }
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(56.dp).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(FIcons.Close, "Clear", tint = c.textDim, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SavedRow(kind: String, place: Place?, near: LatLon?, c: DriveColors, modifier: Modifier, onGo: () -> Unit, onEdit: () -> Unit) {
    val title = kind.replaceFirstChar { it.uppercase() }
    Row(
        modifier.height(68.dp).background(c.raised, Filament.CutS).clickable(onClick = onGo).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(if (kind == "home") FIcons.Home else FIcons.Work, null, tint = c.accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Filament.text(21.sp, 600, c.text), maxLines = 1)
            BasicText(
                place?.let { listOf(it.name, it.detail).filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: "Tap to set",
                style = Filament.text(16.sp, 400, if (place == null) c.accent else c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            near?.let {
                BasicText(RouteMath.formatDistance(RouteMath.metres(it, LatLon(place.lat, place.lon))), style = Filament.label(15.sp, c.textDim, 0f))
            }
            Box(Modifier.fillMaxHeight().width(96.dp).clickable(onClick = onEdit), contentAlignment = Alignment.Center) {
                BasicText("EDIT", style = Filament.label(14.sp, c.textDim, 0.12f))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun PlaceRow(p: Place, icon: ImageVector, near: LatLon?, c: DriveColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(c.raised, Filament.CutS).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            BasicText(p.name, style = Filament.text(21.sp, 600, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.detail.isNotBlank()) BasicText(p.detail, style = Filament.text(16.sp, 400, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        near?.let {
            BasicText(RouteMath.formatDistance(RouteMath.metres(it, LatLon(p.lat, p.lon))), style = Filament.label(15.sp, c.textDim, 0f))
        }
    }
}

/** Large on-screen keyboard (letters / numbers), Apple-like: light letter keys, darker function keys. */
@Composable
internal fun Keyboard(
    c: DriveColors,
    shift: Boolean,
    numbers: Boolean,
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onShift: () -> Unit,
    onLayer: () -> Unit,
    onGo: () -> Unit,
    goLabel: String = "Go",
) {
    val rows = if (numbers) listOf("1234567890", "-/:;()&@'", ".,#!?") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyRow { rows[0].forEach { ch -> CharKey(ch, shift, c, onChar) } }
        KeyRow {
            Spacer(Modifier.weight(0.5f))
            rows[1].forEach { ch -> CharKey(ch, shift, c, onChar) }
            Spacer(Modifier.weight(0.5f))
        }
        KeyRow {
            if (numbers) Spacer(Modifier.weight(1.5f))
            else FnKey(Modifier.weight(1.5f), c, highlighted = shift, onClick = onShift) {
                Icon(FIcons.Shift, "Shift", tint = if (shift) c.accent else c.text, modifier = Modifier.size(28.dp))
            }
            if (numbers) Spacer(Modifier.weight(1f))
            rows[2].forEach { ch -> CharKey(ch, shift, c, onChar) }
            if (numbers) Spacer(Modifier.weight(1f))
            FnKey(Modifier.weight(1.5f), c, onClick = onBackspace) {
                Icon(FIcons.Backspace, "Delete", tint = c.text, modifier = Modifier.size(30.dp))
            }
        }
        KeyRow {
            FnKey(Modifier.weight(2f), c, onClick = onLayer) {
                BasicText(if (numbers) "ABC" else "123", style = Filament.label(20.sp, c.text, 0.06f, 500))
            }
            FnKey(Modifier.weight(5.5f), c, letter = true, onClick = onSpace) {
                BasicText("space", style = Filament.text(20.sp, 400, c.textDim))
            }
            FnKey(Modifier.weight(2.5f), c, accent = true, onClick = onGo) {
                BasicText(goLabel, style = Filament.text(24.sp, 600, c.onAccent))
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.CharKey(ch: Char, shift: Boolean, c: DriveColors, onChar: (String) -> Unit) {
    val label = if (shift) ch.uppercaseChar().toString() else ch.toString()
    FnKey(Modifier.weight(1f), c, letter = true, onClick = { onChar(ch.toString()) }) {
        BasicText(label, style = Filament.text(28.sp, 500, c.text))
    }
}

@Composable
private fun FnKey(
    modifier: Modifier,
    c: DriveColors,
    letter: Boolean = false,
    accent: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        accent -> c.accent
        pressed || highlighted -> c.accentSoft
        letter -> c.raised
        else -> c.coverBg
    }
    Box(
        modifier.fillMaxHeight().background(bg, shape)
            .border(1.dp, if (pressed) c.accentLine else c.border, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

