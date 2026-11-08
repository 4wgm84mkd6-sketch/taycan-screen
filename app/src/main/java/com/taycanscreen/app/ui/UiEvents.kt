package com.taycanscreen.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Requests from outside the screen (e.g. Spotify's Equalizer menu) to show a panel. */
object UiEvents {
    val openPanel = MutableStateFlow<String?>(null)
}
